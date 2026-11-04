# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository shape

- Root Maven module: the Java 21 / Quarkus application — public blog pages, public content APIs, user APIs, admin APIs, gRPC edge sync.
- `admin-flutter/`: Flutter admin UI (git submodule, separate repo).
- `codex-creator/`: retired AI-orchestration submodule. It is no longer deployed, called, or imported by WindBlog; all AI work uses configured AI providers.
- `src/main/resources/templates/`: Qute templates; `src/main/resources/META-INF/resources/assets/`: public CSS/JS/vendor; `src/main/resources/db/migration/`: Liquibase changesets.
- Deployment: `docker-compose.yml` (full stack), `docker-compose.local-test.yml`, `docker-compose.edge.yml`, `k8s/`, `deploy/nginx/`, `observability/`.
- `AGENTS.md` holds the mandatory `bt-main` release procedure; follow it exactly when asked to release.

The working tree also contains ignored runtime data (`postgres-data/`, `uploads/`, `logs/`, `*-data/`) and local secrets (`.env`, `certs/`, `rsa-keys/`, `codex-auth/`). Never stage or print these.

## Commands

Backend commands run from the repository root:

```bash
bash scripts/run-local-dev.sh            # loads .env, maps POSTGRES_*/REDIS_* to DB_*/REDIS_URL, runs quarkus:dev
bash mvnw test                           # all tests; requires a reachable PostgreSQL (Dev Services are disabled)
bash mvnw -Dtest=AdminAuthorizationPolicyTest test   # focused test class
bash mvnw verify -DskipITs=false         # integration tests (*IT, @QuarkusIntegrationTest) against the packaged app
bash mvnw test -Pcoverage                # JaCoCo report + threshold check (>=10% line, >=5% branch; excludes model/dto/type)
bash mvnw package                        # JVM build -> target/quarkus-app/quarkus-run.jar
bash mvnw -Dquarkus.container-image.build=false package   # skip the container-image build
bash mvnw package -Dnative [-Dquarkus.native.container-build=true]   # native executable
```

The local multi-container stack (isolated names, Podman or Docker):

```bash
bash scripts/local-test.sh up|status|down
bash scripts/local-test.sh logs 200 windblog
```

Entry points: `http://127.0.0.1:58080` (same-origin edge), `58081` (direct WindBlog), `59000` (gRPC). See `docs/local-test-environment.md`. `.env` may pin `WINDBLOG_IMAGE=localhost/windblog:local-native` with `WINDBLOG_PULL_POLICY=never`; rebuild before expecting container behavior to change.

Submodules use their own build:

```bash
cd admin-flutter && flutter pub get && flutter analyze && flutter test
```

## Architecture

### Rendering: Qute + PJAX

Public pages are served by JAX-RS controllers that inject two templates — a full document (`blog/post.html`, extends `base.html`) and a fragment (`blog/post.content.html`) — and select one with `PjaxHelper.isPjaxRequest` (`X-PJAX` header). **A new public page needs both templates**, and `PjaxResponseFilter` marks all HTML responses `no-store` with `Vary: X-PJAX` because fragment and document share one URL.

`base.html` is the only shell: it defines the layout, injects `window.i18n`, the CSP nonce, and loads every script globally. PJAX replaces `#pjax-container` without re-running scripts, so page-specific script splitting is not possible without first wiring loading into the PJAX lifecycle. Article list → detail uses a manual FLIP shared-element animation (see `.agents/skills/windblog-pjax-article-transitions/SKILL.md`).

There is no Node build step. Tailwind is loaded at runtime from `assets/vendor/tailwind/tailwind-cdn.min.js` and configured inline; theme colors must be channel triples so opacity utilities work. Asset URLs carry `?v=` cache-busting tokens — bump the token when editing an asset. `system/error.html` duplicates the Tailwind config and must be kept in sync.

`QuteGlobalProvider` exposes globals used across templates: `{config:...}` (DB-backed settings), `{i18n}`, `{csrfToken}`, `{cspNonce}`. `MarkdownNegotiationFilter` converts rendered HTML to Markdown for `Accept: text/markdown`.

### Request filter chain

Filters are `@Priority`-ordered `ContainerRequestFilter`s in `filter/` plus admin auth in `controller/api/admin/`. They cover CSP/security headers, CSRF (double-submit `XSRF-TOKEN` cookie), honeypot, public read rate limiting, language and region resolution, SEO redirects, edge write routing, post-password gating, and protected-content caching.

Any path under `/api/admin` automatically passes `AdminJwtAuthFilter` → `AdminAuthorizationFilter`, which maps the path to a resource/action through `AdminAuthorizationPolicy` and checks `AdminPermissionService`; sensitive actions also require an `X-Admin-Step-Up` token and an `Idempotency-Key` header. **When adding an admin endpoint, update `AdminAuthorizationPolicy`** or it will be denied. Admin login/logout, step-up, install, and docs paths are exempted inside the filter.

### Dynamic configuration

Two layers:

1. Static `application.properties` values with `${ENV:default}` placeholders (profiles: `dev`, `test`, `prod`, `edge`).
2. Database-backed `SystemSetting` rows seeded at startup by `ConfigInitializer`, cached in memory by `ConfigManager` (L1) over `SystemConfigService` (Redis) and exposed to Qute as `{config:key}`. Admin edits broadcast `ConfigChangedEvent` for cache refresh.

`SecurityConfigurationWarning` fails startup in `prod` when defaults/weak values are used for JWT secrets, the security event hash, secure cookies, CORS, RabbitMQ credentials, HSTS, or Swagger exposure — a local `prod` run needs a complete `.env`.

### Messaging and the outbox

Business writes and their event rows commit in the same transaction (`OutboxEventService`); a scheduled publisher leases pending rows and emits them to RabbitMQ via SmallRye channels. Configured fanout exchanges/DLQs in `application.properties`:

- `es-sync-tasks` → Elasticsearch index consumers
- `storage-sync-tasks` → storage replica consumers
- `ai-summary-tasks`, `ai-audit-tasks` → AI workers
- each with `*-dead-letter` queues feeding `dead_letter_messages`, managed through `/api/admin/dead-letters` and `/api/admin/queues`

Native builds freeze channel topology, so the Quarkus profile (`prod` for primary, `edge` for edge) must match between build and runtime. Under `%edge` all RabbitMQ channels, the outbox, and log shipping are disabled.

### Primary vs. edge nodes

`windblog.node.role` selects `PRIMARY` or `EDGE` (`NodeRoleService`). Edge nodes serve public reads from a read-only local state (`EdgeReadOnlyState`) and route writes back to the primary: `EdgeWriteRoutingFilter` intercepts non-allowlisted write methods and forwards them over the persistent gRPC channel or the WESP active-poll/peer protocol (`service/edge/`, `docs/edge-sync-protocol-design.md`). Edge nodes do not run RabbitMQ consumers, so anything that assumes an outbox consumer must be primary-only.

### Media and storage

`StorageClass` implementations (`LocalFsStorageClass`, `AliyunOssStorageClass`) sit behind `StorageService`; uploads produce variants, optionally go through ClamAV (`MediaVirusScanService`), and are replicated through storage sync messages. Protected downloads are gated by `MediaAccessPolicy`/`ContentAccessTicketService`; backup tooling (`BackupDecryptTool`, `EncryptedBackupCodec`) is also in `service/storage`.

### AI integration

`AiService` adapters (OpenAI, ChatGLM, Cerebras, Ollama, mock) are selected by `AiManager`/`AiSelectionService` from DB-backed provider configs, with `AiRateLimiter` and reliable task retries. Topic discovery, the topic pool, review and article drafting all run through this AI provider layer; the retired Codex Creator service must not be reintroduced. Topic and draft runs are asynchronous, recover stale `QUEUED`/`RUNNING` rows on a schedule, and store their state in `ai_topic_pool_runs` / `ai_article_draft_runs`. See `docs/ai-topic-automation.md`.

### Database

Schema changes are Liquibase only: add a numbered `.sql` under `db/migration/` and include it in `db/db.changelog-master.xml` (`migrate-at-start=true`). Entities are Panache active-record classes in `model/`; `repository/` holds the few query repositories.

### Errors

Errors use stable `WB-*` codes from `ErrorCodeCatalog`, returned as JSON `errorCode` and the `X-WindBlog-Error-Code` header, and documented in `docs/error-codes.md`. Add the doc row when introducing a code.

## Testing conventions

- `*Test` for unit and `@QuarkusTest` suites (same package as the class under test); `*IT` for `@QuarkusIntegrationTest` against the packaged app.
- There is no `src/test/resources`: tests boot the real configuration, so PostgreSQL must be reachable and external services are stubbed in code (e.g. `FakeAiService`, mock storage/AI services).
- Prefer focused classes while iterating; `-Pcoverage` enforces the project threshold.

## Documentation index

- `docs/local-test-environment.md` — isolated local stack and endpoints
- `docs/2026-08-02-windblog-home-cloud-runbook.md` — production/security requirements
- `docs/observability-gateway.md` — production Grafana/observability stack and APISIX embed route
- `docs/edge-sync-protocol-design.md` — WESP multi-node design and status
- `docs/ai-topic-automation.md`, `docs/fake-ai-api.md` — AI topic/draft automation and test double
- `docs/admin-api.md` — admin API overview and Swagger/OpenAPI locations
- `docs/web-serial.md` — browser-only Web Serial toolbox (xterm, browser support, PJAX notes)
- `docs/error-codes.md`, `docs/markdown-negotiation.md`, `docs/regional-canonical.md`, `docs/seo-management.md`, `docs/structured-data-seo.md`, `docs/article-reference-cards.md`
