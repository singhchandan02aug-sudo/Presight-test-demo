# AI Interaction & Testing Approach

## Agents & Tools Used

### 1. **GitHub Copilot (Claude Haiku 4.5)** — Primary Development Agent
- **Role:** Code generation, test structure, dependency management, file creation
- **Usage:** Generated integration test scaffolds, helped debug Gradle build configuration, created Docker/k6 artifacts
- **Effectiveness:** High for boilerplate and Spring Boot testing patterns; required manual fixes for:
  - JUnit 4 classpath conflicts (initially excluded globally, had to refine)
  - AssertJ generics type-checking (`.contains()` varargs vs. List<String> mismatch)
  - Spring Boot 3.3 import path corrections (`LocalServerPort` moved from `org.springframework.boot.web.server` to `org.springframework.boot.test.web.server`)

### 2. **Manual Gradle/Environment Debugging**
- **Issue:** Java 26 from VS Code extension broke Kotlin Gradle DSL compiler
- **Resolution:** Located Java 22 locally and set `JAVA_HOME` to bypass the issue
- **Lesson:** Environment setup is critical; AI agents don't catch JVM version incompatibilities well

### 3. **Subagent (Search)** — Brief Exploration
- **Role:** Located test files and API controller patterns in codebase
- **Effectiveness:** Useful for initial codebase mapping; not used extensively since direct grep/file inspection was faster

## What Worked Well

1. **Testcontainers Integration**
   - Copilot correctly suggested `@Testcontainers`, `@Container`, `@DynamicPropertySource` pattern
   - PostgreSQL 15-alpine container setup was accurate on first try
   - No manual SQL or container orchestration needed

2. **Spring Boot Test Context Setup**
   - `@SpringBootTest(webEnvironment = RANDOM_PORT)` suggestion was correct
   - `TestRestTemplate` with error handler pattern worked as suggested
   - No debugging required for Spring context lifecycle

3. **Docker & Docker Compose**
   - Generated working Dockerfile with multi-stage build
   - Docker Compose service definitions were production-ready (minimal fixes needed)
   - k6 load test configuration was sensible

4. **Dependency Management**
   - Added Testcontainers + PostgreSQL to gradle/libs.versions.toml correctly
   - Understood module structure (realworld = server/api project name)

## What Required Manual Correction

1. **Import Path Errors** (Spring Boot 3.3)
   ```
   ❌ org.springframework.boot.web.server.LocalServerPort
   ✅ org.springframework.boot.test.web.server.LocalServerPort
   ```
   - Agent generated outdated import; had to research and correct

2. **JUnit 4 vs JUnit 5 Conflict**
   - AI suggested adding `junit:junit:4.13.2` without understanding it would be excluded by global config
   - Initially tried `configurations.all { exclude(group = "junit", module = "junit") }` with a `!name.contains("test")` condition
   - Root cause: Testcontainers legacy code requires JUnit 4 classes (`org.junit.rules.TestRule`) for container lifecycle
   - Final fix: Removed global exclusion entirely (only needed for transitive deps, not direct test usage)

3. **AssertJ Generics**
   ```
   ❌ assertThat(comments).extracting(...).contains("value")  // varargs issue with List<?>
   ✅ assertThat(comments.stream().map(...).toList()).contains("value")  // List<String>
   ```
   - Copilot initially used `.extracting()` without understanding AssertJ's varargs limitations
   - Manual refactor to explicit `.stream().map().toList()` resolved type issues

4. **Response Error Handling**
   - Agent's first approach didn't allow 4xx assertions (TestRestTemplate throws on non-2xx)
   - Manually added `NoOpResponseErrorHandler` to permit capturing 400/401/404 responses for assertion

## Decision: Why I Overrode Agent's Suggestion

**Issue:** Agent suggested using `ResponseErrorHandler` with `@Override` on non-existent methods.

**Problem:** 
```java
private static class NoOpResponseErrorHandler implements ResponseErrorHandler {
    @Override  // ❌ These methods don't exist in Spring 6.x
    public boolean hasError(ClientHttpResponse response) { ... }
}
```

**Why I Changed It:**
- The interface was imported incorrectly (wrong package in Spring Boot 3.3)
- Spring 6.x moved/refactored `ResponseErrorHandler`
- Rather than debug the interface, I switched to:
  ```java
  restTemplate.getRestTemplate().setErrorHandler(new org.springframework.web.client.ResponseErrorHandler() {
      // ... actual Spring 6 implementation
  });
  ```

**Better Approach in Retrospect:**
- Use Spring's built-in `DefaultResponseErrorHandler` with a custom wrapper
- Or use `TestRestTemplate.exchange()` which has better non-2xx handling built-in

## Key Lessons for Future AI-Assisted Testing

1. **Spring Framework Version Matters**
   - Always verify API paths/interfaces against the exact Spring Boot version (3.3.0 here)
   - Generics in test assertions require explicit type casting or stream operations
   - AI defaults to patterns from older versions; need to validate against docs

2. **Gradle Build System Complexity**
   - Global exclusions (`configurations.all { exclude(...) }`) have wide scope; use sparingly
   - Test configurations may need different rules than main configurations
   - Dependency resolution can fail silently; use `./gradlew dependencies` to debug

3. **Testcontainers + JUnit Integration**
   - Requires both JUnit 5 (for `@Test`) and JUnit 4 (for container rules) on classpath
   - This is a known wart in the Testcontainers library; document it

4. **Error Handling in Integration Tests**
   - Never assume the test client will catch HTTP errors gracefully
   - Always test both happy paths and error responses
   - Set up error handlers BEFORE assertions, not after

## Tools & Agents Summary

| Tool | Used | Effectiveness | Notes |
|------|------|----------------|-------|
| GitHub Copilot | Yes | 8/10 | Good for scaffolding; needs version-aware corrections |
| Testcontainers | Yes | 10/10 | Near-perfect setup by AI; no runtime issues |
| Docker/Compose | Yes | 8/10 | Working configs; some best-practices tweaks needed |
| Gradle Deps | Yes | 6/10 | Needs manual debugging; AI doesn't understand conflicts well |
| Spring Boot Test Patterns | Yes | 7/10 | Good suggestions; outdated import paths are common |

## Test Coverage Strategy

**Focus:** Auth + Article flows with Testcontainers + PostgreSQL

**3 Test Classes:**
1. `AuthenticationIntegrationTest` — signup, login, token validation, profile updates
2. `ArticleCrudIntegrationTest` — create, read, update, delete articles with auth checks
3. `ArticleInteractionIntegrationTest` — comments, favorites, follows

**Running Tests:**
```bash
# Auth tests only
./gradlew :realworld:test --tests "io.zhc1.realworld.e2e.Auth*"

# Article CRUD only
./gradlew :realworld:test --tests "io.zhc1.realworld.e2e.ArticleCrud*"

# All focused tests
./gradlew :realworld:test --tests "io.zhc1.realworld.e2e.*"
```

**CI/CD:** GitHub Actions runs all tests on push/PR; reports pass/fail in workflow logs.

## Bugs Discovered

See [open-bugs.md](open-bugs.md) for non-author article delete returning 400 instead of 403.
