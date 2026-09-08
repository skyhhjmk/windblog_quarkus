# Repository Guidelines

## Project Structure & Module Organization

WindBlog uses Java 21 and Quarkus. Backend code lives in `src/main/java/com/biliwind/blog/`, organized into controllers, services, repositories, models, and configuration. Tests mirror these packages under `src/test/java/`. Qute templates live in `src/main/resources/templates/`; public assets are under `src/main/resources/META-INF/resources/`; database migrations are under `src/main/resources/db/`.

`admin-flutter/` is the Flutter administration UI, with code in `lib/`, assets in `assets/`, and tests in `test/`. `codex-creator/` is an independent Quarkus AI orchestration service with its own database. Both are Git submodules, not parent Maven modules. Deployment resources live in `docker/`, `deploy/`, and `k8s/`; operational guidance lives in `docs/`.

## Build, Test, and Development Commands

Run backend commands from the repository root:

- `bash scripts/run-local-dev.sh`: load local environment configuration and start Quarkus development mode.
- `bash mvnw test`: run backend tests.
- `bash mvnw -Dtest=AdminAuthorizationPolicyTest test`: run a focused test class.
- `bash mvnw package`: test and build the JVM application.
- `bash mvnw verify -DskipITs=false`: include integration tests.
- `bash mvnw test -Pcoverage`: generate coverage reports and enforce thresholds.
- `bash scripts/local-test.sh up`: start the isolated container stack; see `docs/local-test-environment.md` for prerequisites and endpoints.

Run `bash mvnw test` inside `codex-creator/` separately. Inside `admin-flutter/`, use `flutter pub get`, `flutter analyze`, and `flutter test`.

## Coding Style & Naming Conventions

Follow surrounding Java style: four-space indentation, `PascalCase` classes, `camelCase` methods and fields, and lowercase packages. Keep controllers focused on HTTP handling and business logic in services. For Dart, use two-space indentation, `snake_case.dart` filenames, and `dart format lib test`; analysis uses `flutter_lints`.

## Testing Guidelines

Use JUnit Jupiter and Quarkus testing support. Name Java unit tests `*Test` and integration tests `*IT`; Flutter tests use `*_test.dart`. Cover changed behavior and relevant failure paths. The backend coverage profile requires at least 10% line and 5% branch coverage, excluding configured model, DTO, and type packages.

## Commit & Pull Request Guidelines

Recent history uses `fix:`, `feat:`, and `dev:` prefixes alongside concise Chinese descriptions. Write focused, descriptive commits. Commit changed submodules before updating parent gitlinks; preserve unrelated working changes.

PRs should explain the problem, resulting behavior, validation performed, and configuration or migration impact. Link relevant issues and include screenshots for UI changes. Run `git diff --check` before submission.

## Security & Configuration

Use `.env.example` as a configuration reference. Never commit credentials, `.env`, private keys, uploads, logs, or database volumes. Keep Codex Creator's database separate from WindBlog's.
