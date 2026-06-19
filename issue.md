# Open Test Failures

47 tests run, **5 failing** as of 2026-06-17.

---

## 1. `ArticleCrudIntegrationTest` — `shouldUpdateArticleTitleWhenAuthor`

**File:** `server/api/src/test/java/io/zhc1/realworld/e2e/ArticleCrudIntegrationTest.java:139`

**Error:** `java.lang.NullPointerException` inside `Map.of()`

**Root cause:** `Map.of()` in Java does not allow `null` values. The update payload passes `null` for `"description"` and `"body"`:
```java
Map.of("article", Map.of("title", "Updated Title", "description", null, "body", null))
```

**Fix:** Replace `Map.of()` with `HashMap` for the inner map so null values are permitted.

---

## 2. `ArticleCrudIntegrationTest` — `shouldRejectUpdateFromNonAuthor`

**File:** `server/api/src/test/java/io/zhc1/realworld/e2e/ArticleCrudIntegrationTest.java:158`

**Error:** `java.lang.NullPointerException` inside `Map.of()`

**Root cause:** Same as issue #1 — `null` values in `Map.of()` for `"description"` and `"body"`.

**Fix:** Same as issue #1.

---

## 3. `AuthenticationIntegrationTest` — `shouldRejectInvalidPassword`

**File:** `server/api/src/test/java/io/zhc1/realworld/e2e/AuthenticationIntegrationTest.java:118`

**Error:** Expected status `[401, 404]` but received `400`.

**Root cause:** `UserService.login()` (`module/core/src/main/java/io/zhc1/realworld/service/UserService.java:75`) throws `IllegalArgumentException` for invalid credentials, which `ApplicationExceptionHandler` maps to `400 BAD_REQUEST`. The test expects `401 UNAUTHORIZED` (the REST-correct response for wrong credentials).

**Fix options:**
- Change `UserService.login()` to throw `BadCredentialsException` (Spring Security, maps to 401 via the existing `AuthenticationException` handler). Requires adding `spring-security-core` to `module/core/build.gradle.kts`.
- Or update test assertion to also accept `400`.

---

## 4. `AuthenticationIntegrationTest` — `shouldRejectNonExistentUser`

**File:** `server/api/src/test/java/io/zhc1/realworld/e2e/AuthenticationIntegrationTest.java:131`

**Error:** Expected status `[401, 404]` but received `400`.

**Root cause:** Same as issue #3 — `UserService.login()` throws `IllegalArgumentException` (→400) instead of the correct auth error.

**Fix:** Same as issue #3.

---

## 5. `AuthenticationIntegrationTest` — `shouldUpdateUserProfile`

**File:** `server/api/src/test/java/io/zhc1/realworld/e2e/AuthenticationIntegrationTest.java:169`

**Error:** `NullPointerException` at `loginAndGetToken` line 200 — `getUser(loginResponse)` returns `null`.

**Root cause:** `loginAndGetToken` calls `signup()` then explicitly calls `/api/users/login`. The `signup` endpoint redirects (307) to `/api/users/login`; `TestRestTemplate` follows the redirect. When the redirect-based login fails (likely because the redirect sends query params instead of a JSON body to the `@RequestBody` endpoint), `getUser()` on the response returns `null`. The subsequent explicit login call then appears not to execute or its response body lacks a `"user"` key — possibly a cascading state issue from the broken redirect flow.

**Note:** `shouldLoginAndReceiveToken` does the same signup+login pattern and passes, suggesting this may also be related to test execution order or the redirect-based auto-login consuming or interfering with the explicit login.

**Suggested investigation:** Add logging in `UserController.login()` to trace what body arrives during the 307 redirect follow-through. Also consider whether the signup-redirect-to-login feature is intended to work with `TestRestTemplate`.
