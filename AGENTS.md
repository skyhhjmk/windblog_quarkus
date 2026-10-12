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

After any code update, update the corresponding local development environment before considering the work complete. For Quarkus services, rebuild or restart the affected service/container and verify its health or startup logs; for Flutter, restart or hot-reload the active development app and verify that the changed behavior is loaded. Do not report a code change as completed while the local runtime is still using stale artifacts.

## Coding Style & Naming Conventions

Follow surrounding Java style: four-space indentation, `PascalCase` classes, `camelCase` methods and fields, and lowercase packages. Keep controllers focused on HTTP handling and business logic in services. For Dart, use two-space indentation, `snake_case.dart` filenames, and `dart format lib test`; analysis uses `flutter_lints`.

## Testing Guidelines

Use JUnit Jupiter and Quarkus testing support. Name Java unit tests `*Test` and integration tests `*IT`; Flutter tests use `*_test.dart`. Cover changed behavior and relevant failure paths. The backend coverage profile requires at least 10% line and 5% branch coverage, excluding configured model, DTO, and type packages.

## Commit & Pull Request Guidelines

Recent history uses `fix:`, `feat:`, and `dev:` prefixes alongside concise Chinese descriptions. Write focused, descriptive commits. Commit changed submodules before updating parent gitlinks; preserve unrelated working changes.

PRs should explain the problem, resulting behavior, validation performed, and configuration or migration impact. Link relevant issues and include screenshots for UI changes. Run `git diff --check` before submission.

## GitHub Actions & bt-main Releases

For a user-requested release to `bt-main`, follow this order:

1. Inspect the worktree and stage only the intended files. Preserve unrelated changes and never stage `.env`, credentials, private keys, uploads, logs, or database volumes.
2. Commit the scoped change, push the intended branch to GitHub, and record the commit SHA.
3. Find the GitHub Actions run for that SHA and wait until it is `completed` with a `success` conclusion. Confirm the workflow published the immutable image tag. If the run fails, fix the cause, make and push a new scoped commit, and wait for that run; do not update `bt-main` while a build or registry push is pending or failed.
4. Before changing the remote service, back up `/root/windblog_quarkus/.env`, `docker-compose.yml`, and the current WindBlog container/image state in a protected, timestamped release directory. Do not print secret values. Keep the healthy old container and a rollback image available until the new image passes checks.
5. On `bt-main`, pull the CI-published immutable native image, currently available through `docker.1ms.run/hhjmk/windblog_quarkus:sha-<commit>-native`. Confirm `WINDBLOG_PULL_POLICY=always`, update only `WINDBLOG_IMAGE`, validate the Compose configuration, then run `docker compose --env-file .env pull windblog` followed by `docker compose --env-file .env up -d --no-deps --pull always windblog`. Do not rebuild or upload a local image for this registry-pull flow, and do not recreate dependencies or volumes.
6. Verify that the running container uses the requested tag/digest, is healthy with zero restarts, and that `curl -fsS http://127.0.0.1:58080/q/health/ready` reports readiness. Check startup logs and the requested behavior through the exact public hostname; health or a generic HTTP 200 alone does not prove the changed feature works.
7. Report the commit SHA, Actions run result, pulled image tag/digest, remote health/readiness, and public behavior separately. Leave unrelated worktree changes untouched.

## Security & Configuration

Use `.env.example` as a configuration reference. Never commit credentials, `.env`, private keys, uploads, logs, or database volumes. Keep Codex Creator's database separate from WindBlog's.
