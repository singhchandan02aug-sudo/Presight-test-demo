# API Test Suite

This folder contains end-to-end integration tests for the RealWorld Java Spring Boot API.

## What is included

- Spring Boot integration tests that exercise the live API on a random port.
- Testcontainers-powered PostgreSQL backend for consistent database state.
- Core flows: signup, login, article publish, comments, favorites, follows, and authorization checks.

## Run locally

```bash
./gradlew test --tests "io.zhc1.realworld.e2e.*"
```

## Notes

- Tests are intentionally isolated and use a PostgreSQL container rather than the default in-memory H2 configuration.
- If you need to inspect a failing test, run it with `--info` or `--stacktrace`.
