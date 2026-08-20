# hugmesh-wiki-bff-api

`hugmesh-wiki-bff-api` is the **write-path backend-for-frontend (BFF)** for the optional
Wiki feature of the [HugMesh](https://github.com/hugmesh) platform. It owns the
**writable Postgres content store** that wiki authors write to, enforces role-gated read
and edit access, and renders Markdown to HTML at save time. That rendered HTML is the
**source of truth** that a (planned) Tier 2 ZIM rebuild job packages for `kiwix-serve` to
serve to readers — see [Three-tier architecture](#three-tier-architecture-write-path-vs-kiwixs-read-only-serving)
below.

It follows the same conventions as the platform's other backend-for-frontend services —
scaffolded from [`hugmesh-forum-bff-api`](https://github.com/hugmesh/hugmesh-forum-bff-api)
(Java 25 / Spring WebFlux / R2DBC Postgres / Liquibase / Casdoor JWT resource server / the
canonical `CasdoorAuthorities` converter + `DomainRegistry`/`DomainScope`/`Roles`
multi-brand spine), and built GraalVM-native capable. The security/tenancy spine is now
sourced from the shared [`hugmesh-commons`](https://github.com/hugmesh/hugmesh-commons)
library — see [Shared commons library](#shared-commons-library-hugmesh-parent--hugmesh-commons)
— while the per-repo `PrincipalDomain` scope resolver stays local to this service.

> **State legend** — ✅ **shipped** (merged to `main`) · 🚧 **in progress** (code on an open
> branch/PR, not yet merged) · 🔭 **planned** (designed, no code yet). Everything below is
> ✅ shipped unless tagged otherwise; unbuilt work lives in
> [Roadmap](#roadmap--out-of-repo).

## Current State

- **Dependency baseline:** Spring Boot **4.0.7** / Spring Cloud **2025.1.2**, **Java 25
  LTS** (`eclipse-temurin:25`), on the shared Maven artifacts
  `net.hugmesh:hugmesh-parent:2.8.0` ([parent POM](https://github.com/hugmesh/hugmesh-parent))
  and `net.hugmesh:hugmesh-commons:2.1.0` (shared security/tenancy,
  `net.hugmesh.commons.*`), with Testcontainers **2.0.5** in the test path. The full
  suite is green: **239/239 tests**, run against a real Postgres via Testcontainers rather
  than an in-memory substitute.
- **Place in the platform auth model.** This BFF is a **pure Casdoor-JWT OAuth2 resource
  server**. The browser only ever holds an **opaque `bff_`-prefixed session** (issued and
  refreshed by `user-bff`, which performs the Casdoor OIDC code→token exchange) — the user
  never sees a JWT. The **fe-gateway** runs a pre-security exchange filter that swaps the
  `bff_` session for the internal custody JWT **before** this service sees the request, so
  this resource server's JWT validation and role gate run exactly as documented below. The
  shared [`hugmesh-auth-core`](https://github.com/hugmesh/hugmesh-auth-core) library
  (account/session/MFA/Casdoor-admin) is consumed only by `user-bff` and `admin-bff` — it
  does not touch this repo, and login/account/session logic never lives here.
- **Authorization is gateway-fronted + locally re-checked.** The gateway enforces the
  data-driven `gateway_route_access` floor (`ROLE_WIKI`); this service still does its own
  defence-in-depth path gate + method `@PreAuthorize` + the load-bearing
  `WikiAccessGuard` overlay decision (see below).
- **Data access** is Spring Data R2DBC reactive repositories (no raw SQL strings / inline
  `@Query`), per the platform's data-access convention.
- **CI runs on GitLab.** The GitHub repo is pull-mirrored into the platform's in-cluster
  GitLab instance, which runs the pipeline described in
  [CI / security pipeline](#ci--security-pipeline). Deployments run SELinux-enforcing, use
  a per-app SCRAM Postgres role, and communicate over internal mTLS.

## Three-tier architecture (write path vs. Kiwix's read-only serving)

Wiki content has a **write side** and a **read side** with different operational needs:
authors need a live, versioned, access-controlled store; readers need something that stays
up even if the write-side service is down, and `kiwix-serve` — the offline-first reader
this platform serves wiki pages through — has no concept of authentication of its own.
Splitting the two into tiers resolves that: only **Tier 1** lives in this repo and is
shipped. Tiers 2 and 3 are 🔭 planned infrastructure work outside this repo (see
[Roadmap](#roadmap--out-of-repo)). The publish-state columns already on `main` are the
handoff point: only pages with `is_published=true` get packaged by the future ZIM build.

```mermaid
flowchart LR
    author(["Author (ADMIN / SUPERADMIN)"])
    reader(["Reader (USER / MOD+WIKI / granted)"])

    subgraph shipped["Tier 1 — WRITE (this repo, shipped)"]
        bff["wiki-bff /bff/wiki/**<br/>markdown -> HTML at write"]
        store[("wiki_page Postgres<br/>content store")]
        bff --> store
    end

    subgraph roadmap["Tiers 2 and 3 (planned, out of repo)"]
        rebuild["ZIM (re)build CronJob<br/>packages PUBLISHED pages"]
        pvc[("snapshots PVC<br/>.zim")]
        kiwix["kiwix-serve (read-only)"]
        rebuild --> pvc --> kiwix
    end

    author -->|"create / update / publish"| bff
    store -.->|"published pages only"| rebuild
    reader -->|"live read"| bff
    reader -.->|"last published ZIM"| kiwix

    classDef planned stroke-dasharray:4,stroke:#888
    class roadmap,rebuild,pvc,kiwix planned
```

| Tier | What | Owner | State |
| --- | --- | --- | --- |
| **1 — WRITE** | `wiki_page` Postgres content store (markdown authored, HTML rendered at write). Versioned, audited, per-domain. | **this repo** | ✅ shipped |
| **2 — (RE)BUILD** | a CronJob renders the store → packages a ZIM (zim-tools, multi-arch) to a snapshots PVC | out of repo | 🔭 planned |
| **3 — READ** | `kiwix-serve` mounts the PVC read-only and serves it (never the write path) | out of repo | 🔭 planned |

The tiers fail **independently**: a `kiwix-serve` outage never blocks an author from
editing; a BFF outage leaves readers serving from the last successfully published ZIM.

## What this service does today

The shipped surface is well beyond a skeleton. The content store, Markdown renderer, live
grant/timeout/ban access overlay, per-page attachments, draft→published lifecycle, and
audit trail are all on `main`.

- **Liquibase schema** (`db.changelog-master.xml` includes all six changesets):
  - `001` — `wiki_page` (`id` UUID PK, `domain` NOT NULL, `slug`, `title`,
    `content_markdown`, `content_html`, `author_subject`, `version`, `created_at`,
    `updated_at`, **UNIQUE(domain, slug)**).
  - `002` — `wiki_attachment` (`page_id` FK ON DELETE CASCADE, `filename`, `mime_type`,
    `data` BYTEA; `domain` denormalised for scoping).
  - `003` — `wiki_audit_log` (this BFF's own content-edit trail; domain-scoped).
  - `004` — `wiki_access` (the grant/timeout/ban OVERLAY desired-state: `state`
    active→revoked rows are never hard-deleted, so history is auditable; `expires_at` NULL
    = permanent, future = a timeout that auto-restores on expiry; PARTIAL UNIQUE on
    `(domain, user_subject, access_type) WHERE state='active'`, domain-scoped).
  - `005` — published-state columns on `wiki_page` (`is_published` NOT NULL DEFAULT false,
    `published_at`).
  - `006` — **DB-generated UUID primary keys** (a platform-wide convention): installs
    `pgcrypto` and adds a `(gen_random_uuid())::text` column DEFAULT to every PK
    (`wiki_page` / `wiki_attachment` / `wiki_audit_log` / `wiki_access`), so the database —
    not the app — mints the id; the repositories INSERT without an id and read it back via
    `RETURNING id`.
- **R2DBC repositories** — domain-scoped reads, DB-generated-UUID inserts (INSERT without
  an id, `RETURNING id`), and the **version-guarded conditional UPDATE** (optimistic
  concurrency).
- **Casdoor JWT resource server** — the canonical converter (reserved-prefix injection
  guard + issuer-derived `DOMAIN_<x>` + fail-closed `domainOf`), single- and (opt-in)
  multi-issuer.
- **Markdown → HTML at write** (CommonMark + GFM tables, raw HTML escaped = XSS-safe).
- **`WikiController`** — read (list/get) + edit (create/update) + publish + **delete**, all
  domain-scoped, author tracked from the JWT subject.
- **`WikiAttachmentController`** — per-page binary attachments: upload, download, list,
  delete.
- **`WikiAccessController` + `WikiAccessGuard`** — the grant/timeout/ban overlay, enforced
  at request time on every read.
- **OpenAPI / springdoc** at `/v3/api-docs` — the source for the paired
  [`hugmesh-wiki-ui`](https://github.com/hugmesh/hugmesh-wiki-ui) (✅ shipped:
  ng-openapi-gen typed client, Playwright/BDD e2e harness, Dockerfile+nginx, CI pipeline;
  its federation contract is wired to this BFF's `/v3/api-docs`).

### Publish lifecycle

A page is created in **DRAFT** (`is_published=false`). An explicit
`POST /bff/wiki/admin/pages/{slug}/publish` flips `is_published` and stamps `published_at`;
both are recorded in the audit log. The future ZIM (re)build (🔭 planned) will package
**only published pages** and use `published_at` to detect pages republished since the last
build.

## Role model (platform cascade SUPERADMIN > ADMIN > MOD > USER)

```
WIKI_READ   = hasRole('ADMIN') or hasRole('SUPERADMIN') or (hasRole('MOD') and hasRole('WIKI'))
WIKI_WRITE  = hasRole('ADMIN') or hasRole('SUPERADMIN')
WIKI_MANAGE = hasRole('SUPERADMIN')
```

**Defence in depth** runs in layers: the fe-gateway route-access (data-driven
`gateway_route_access`) enforces the `ROLE_WIKI` floor; `SecurityConfig` gates the paths
(`/bff/wiki/superadmin/**` SUPERADMIN, `/bff/wiki/admin/**` ADMIN|SUPERADMIN, the rest
`.authenticated()`); and the controllers re-check at the method level via `@PreAuthorize`.

The **write/manage** endpoints use **static SpEL** composites at the method level
(`WIKI_WRITE` = ADMIN|SUPERADMIN, `WIKI_MANAGE` = SUPERADMIN). The role hierarchy edge
`ROLE_SUPERADMIN > ROLE_ADMIN` means a cross-domain SUPERADMIN can reach ADMIN-gated write
endpoints without being listed explicitly.

The **read** endpoints are different. Their `@PreAuthorize` gate is
`WIKI_READ_AUTHENTICATED = "isAuthenticated()"` — deliberately weaker than a static
`WIKI_READ` check, because a static check would deny a plain USER who holds a grant before
the overlay could be consulted. The load-bearing read decision is therefore made
**programmatically** inside `WikiAccessGuard.assertCanRead(auth)`, which evaluates: the
`WIKI_READ` role composite **OR** an active grant, **AND NOT** an active ban or timeout,
with **SUPERADMIN always exempt**. A denied request never reaches the content store — the
repository call is wrapped in `Mono.defer` and only executed if the guard allows it.

### Grant / timeout / ban overlay (`wiki_access`)

The `wiki_access` table sits on top of the role cascade and is the sole source of truth for
per-principal read overrides:

- **grant / timeout / ban / restore** are `WIKI_WRITE` operations served under
  `/bff/wiki/admin/access/**`. A scoped **ADMIN** always writes within their own
  issuer-derived brand (a `targetDomain` body field, if sent, is inert). A **SUPERADMIN**
  is cross-domain: the write lands in the `targetDomain` brand (validated against the
  trusted `DomainRegistry`) so it lands where `WikiAccessGuard`'s read actually looks. In
  multi-issuer mode a SUPERADMIN write with a missing/unknown `targetDomain` **fails
  closed (400)** rather than silently stamping an inert `default` row; in single-issuer
  mode every principal is `default`, so behaviour there is unchanged. This closed a real
  gap (fixed in #39): a SUPERADMIN cross-brand ban used to be stamped to `default` while
  the read guard queried the victim's own brand, so the ban silently never took effect.
- **list-active / history** are `WIKI_MANAGE` operations (SUPERADMIN only), served under
  `/bff/wiki/superadmin/access`.
- Rows transition from `active` to `revoked` but are **never hard-deleted** — the full
  history is retained for audit. A re-grant after revocation inserts a new row.
- Precedence in `WikiAccessGuard`: an active ban or timeout **denies even a role-holder or
  active grantee** (the ban always wins); SUPERADMIN is checked first and is always exempt
  (no DB read required); an expired timeout auto-restores on the next request without any
  write.

```mermaid
flowchart TD
    req(["GET /bff/wiki/pages or .../attachments"])
    gw["fe-gateway route-access<br/>ROLE_WIKI floor"]
    sc["SecurityConfig path gate<br/>.authenticated()"]
    pa["@PreAuthorize WIKI_READ_AUTHENTICATED<br/>= isAuthenticated()"]
    guard["WikiAccessGuard.assertCanRead<br/>(load-bearing dynamic decision)"]

    req --> gw --> sc --> pa --> guard

    guard --> sa{"SUPERADMIN?"}
    sa -->|"yes"| allow(["ALLOW (overlay-exempt)"])
    sa -->|"no"| ban{"active ban / timeout?"}
    ban -->|"yes"| deny(["403 DENY (ban wins)"])
    ban -->|"no"| qual{"role qualifies<br/>OR active grant?"}
    qual -->|"yes"| allow
    qual -->|"no"| deny

    classDef terminal stroke-width:2px
    class allow,deny terminal
```

## Endpoints (`/bff/wiki/**`)

| Method | Path | Gate |
| --- | --- | --- |
| GET | `/bff/wiki/pages` | authenticated → WikiAccessGuard (WIKI_READ ∨ grant, ¬ban) |
| GET | `/bff/wiki/pages/{slug}` | authenticated → WikiAccessGuard |
| GET | `/bff/wiki/pages/{slug}/attachments` | authenticated → WikiAccessGuard |
| GET | `/bff/wiki/pages/{slug}/attachments/{filename}` | authenticated → WikiAccessGuard |
| POST | `/bff/wiki/admin/pages` | WIKI_WRITE |
| PUT | `/bff/wiki/admin/pages/{slug}` | WIKI_WRITE |
| POST | `/bff/wiki/admin/pages/{slug}/publish` | WIKI_WRITE |
| DELETE | `/bff/wiki/admin/pages/{slug}` | WIKI_WRITE (cascades to attachments) |
| POST | `/bff/wiki/admin/pages/{slug}/attachments` | WIKI_WRITE (multipart `file`) |
| DELETE | `/bff/wiki/admin/pages/{slug}/attachments/{filename}` | WIKI_WRITE |
| POST | `/bff/wiki/admin/access/grant` | WIKI_WRITE — ADMIN own-brand, SUPERADMIN cross-brand via `targetDomain` |
| POST | `/bff/wiki/admin/access/timeout` | WIKI_WRITE — ADMIN own-brand, SUPERADMIN cross-brand via `targetDomain` |
| POST | `/bff/wiki/admin/access/ban` | WIKI_WRITE — ADMIN own-brand, SUPERADMIN cross-brand via `targetDomain` |
| DELETE | `/bff/wiki/admin/access/{accessType}/{userSubject}` | WIKI_WRITE (restore/revoke) — ADMIN own-brand, SUPERADMIN cross-brand |
| GET | `/bff/wiki/superadmin/access` | WIKI_MANAGE (list active) |
| GET | `/bff/wiki/superadmin/access/{userSubject}/history` | WIKI_MANAGE |
| GET | `/bff/wiki/superadmin/audit?limit=` | WIKI_MANAGE (bounded newest-first read, `limit` clamped to `[1, 1000]`, default 200) |

## Multi-brand domain scoping

Every data surface is per-domain. The `domain` value used in all database queries is
derived **server-side** from the validated JWT issuer via `PrincipalDomain` — it is never
supplied as a request parameter and cannot be spoofed by the caller. This means a scoped
ADMIN of brand X can read and write **only brand X's pages**; SUPERADMIN is the
cross-domain bypass.

If the JWT issuer cannot be resolved to a known domain (e.g., a token from an unconfigured
issuer), `domainOf` returns the sentinel string `__deny__` rather than throwing. Because
`__deny__` matches no real domain in the database, the request returns an empty result or a
404 rather than leaking another domain's data. This is the fail-closed behaviour: an
unresolvable domain produces a safe empty result, not an error that could be exploited to
enumerate other tenants' content.

**Multi-issuer is opt-in and backward-compatible.** When `app.tenancy.issuers` is empty
(i.e. `APP_TENANCY_ISSUERS` is unset), the resource server uses a single
`oauth2ResourceServer.jwt(...)` issuer-uri — behaviour is byte-for-byte unchanged from a
plain single-tenant setup. Supplying comma-separated `issuerUri=domainName` pairs causes
`TenancyResourceServerConfig` to build a per-issuer `ReactiveAuthenticationManagerResolver`.
`SecurityConfig` switches to that resolver only when it exists **and**
`DomainRegistry.isMultiIssuer()` returns true — each per-brand Casdoor then validates only
its own tokens, and any token with an unknown `iss` claim is rejected with **401
(fail-closed)**.

> **Example:** to enable two brands in the same cluster, set:
> ```
> APP_TENANCY_ISSUERS=https://casdoor.brand-a.example.com=brand-a,https://casdoor.brand-b.example.com=brand-b
> ```
> Leave the variable unset for a single-brand deployment.

> Note on the platform auth model: the JWT this service validates is the **internal custody
> JWT**. The browser holds an **opaque `bff_`** session, which the fe-gateway swaps for the
> custody JWT in a pre-security filter before forwarding — so the resource-server
> validation above is unaffected by that opaque-session model.

## Security posture

- **Hardened reactive HTTP response headers** (OWASP secure-headers / NIST 800-228 / ASVS
  V14.4), applied to **every** `SecurityWebFilterChain`. As a pure-JSON resource server
  behind the gateway it re-asserts only the **API-defensive minimum**: a no-document CSP
  (`default-src 'none'; frame-ancestors 'none'`) plus Spring Security's reactive secure
  defaults (X-Content-Type-Options `nosniff`, X-Frame-Options `DENY`, Cache-Control
  `no-store`, Referrer-Policy `no-referrer`). HSTS and Permissions-Policy are deliberately
  omitted here (emitted once at the TLS edge / gateway). This is additive — it changes no
  authz rule, issuer validation, or CSRF posture.
- **API-to-API mTLS (server side).** `server.ssl.*` defaults are **off** for dev/CI
  (`SSL_ENABLED=false`, `client-auth=none`, plaintext) so local tests are unaffected.
  In-cluster the deployment flips `SSL_ENABLED=true` / `SSL_CLIENT_AUTH=need` and points
  the key-store/trust-store at the mounted PKCS12 stores (Vault-PKI identity + cluster CA),
  with a **TLSv1.3** floor — so a caller not deployed and signed by the platform's PKI is
  rejected at the handshake.
- **OpenAPI gating.** `/v3/api-docs` and Swagger UI are **public under the `dev`/`test`
  profiles** (so `gen:fetch` / ng-openapi-gen and the docs pipeline can fetch the spec
  without a token) but require **ROLE_ADMIN|SUPERADMIN in prod** (the `!dev & !test` filter
  chain), so the API contract is never publicly reachable in a production deployment.

## CI / security pipeline

CI runs on **GitLab only**. The GitHub repo is pull-mirrored into the platform's in-cluster
GitLab instance (`mirror_trigger_builds=true`), and `.gitlab-ci.yml` runs on every push or
mirror-sync using a privileged x86 runner. A manual-only `release.yml` GitHub Actions
workflow (`workflow_dispatch`) is also included, for building and pushing the image to GHCR
once the required repo secrets are configured — see the workflow file for details.

```mermaid
flowchart LR
    push(["git push / mirror sync"])
    test["test<br/>./mvnw -B -s settings.xml test<br/>(docker:dind + Testcontainers Postgres,<br/>JUnit surefire reports)"]
    sca["pentest:sca<br/>OWASP Dependency-Check SCA"]
    dast["pentest:dast<br/>ZAP + nuclei + escalation probes"]
    build["build:image<br/>push to GitLab registry<br/>(SHA tag + branch tag)"]

    push --> test --> sca
    push --> test --> dast
    test --> build

    style sca stroke-dasharray:4,stroke:#888
    style dast stroke-dasharray:4,stroke:#888
```

- **test** — runs `./mvnw -B -s settings.xml test` inside `maven:3.9-eclipse-temurin-25`,
  with a `docker:27-dind` service attached so the full-context (`@SpringBootTest`) tests
  can spin up a real Postgres via Testcontainers (the commons `PostgresTestSupport` base),
  and publishes surefire JUnit reports.
- **build:image** — needs only `test` (not the pentest jobs); builds and pushes the
  container image to the in-cluster GitLab registry with both an immutable SHA tag and a
  moving branch tag. Images carry no key material — all key material is supplied at first
  boot from external storage.
- **pentest:sca / pentest:dast** — each `needs: [test]` and run in parallel (not
  sequentially), via `extends:` from the centralized stage template in
  [`hugmesh-generator`](https://github.com/hugmesh/hugmesh-generator)
  (`/tests/pentest/ci/pentest-stage.gitlab-ci.yml`). `.pentest-sca` runs OWASP
  Dependency-Check SCA, and `.pentest-dast-baseline` runs the shared `run-pentest.sh`
  harness (environment stand-up, persona-seeded authenticated ZAP baseline, custom
  escalation/DoS probes, and nuclei). Both jobs are currently set `allow_failure: true` —
  they are **advisory, not gating**. Non-advisory enforcement and k6 load testing are part
  of the platform's broader pen-test rollout — see [Roadmap](#roadmap--out-of-repo).
- **Local reproduction (`Makefile`)** — `make pentest` runs the same two checks locally:
  `make pentest-sca` (OWASP Dependency-Check via Docker, falling back to `./mvnw` when
  Docker is unavailable) and `make pentest-dast` (the shared `run-pentest.sh` harness
  against `DAST_TARGET_URL`, skipped non-fatally when no target/harness is available).
  Both targets are self-contained and exit `0` even with nothing configured, so they're
  safe to run on any dev machine without CI credentials.

## Build / test

```sh
./mvnw test                          # the full suite: unit + slice + full-context tests
./mvnw -s settings.xml test          # same, resolving hugmesh-commons from GitLab Maven
                                     # (only needed if it isn't already in ~/.m2)
```

The full-context (`@SpringBootTest`) tests run against a **real Postgres via
Testcontainers** (the commons `PostgresTestSupport` base) — rather than an in-memory H2
instance — with the real Liquibase changelog applied, so the schema seen by tests matches
production. A local Docker daemon (or a `TESTCONTAINERS_HOST_OVERRIDE`-reachable one) is
required for the complete suite. On newer JDKs, build hosts need the surefire
`-Dnet.bytebuddy.experimental=true` flag, which is already configured in
[`hugmesh-parent`](https://github.com/hugmesh/hugmesh-parent)'s plugin management
(target is JDK 25).

Tests follow the platform's standard categories:

- **User-situational** — a single authenticated user performs a valid action and the
  response is correct.
- **Cross-user ALLOW** — a user with sufficient role can read or modify content that
  another user authored.
- **Cross-user DENY** — a user without sufficient role cannot read or modify another
  user's content (asserts 403, not 404, so access denial is not masked as a missing
  resource).
- **Domain isolation** — a token issued for domain X cannot retrieve or mutate pages
  belonging to domain Y.
- **Concurrent-edit version bump** — two concurrent updates to the same page with the
  same version number: exactly one succeeds and the other is rejected, verifying
  optimistic concurrency.

## Shared commons library (`hugmesh-parent` + `hugmesh-commons`)

This service adopts the platform's shared Maven artifacts, removing duplicated security and
tenancy code. There are two de-duplication moves:

1. **Parent POM →** `net.hugmesh:hugmesh-parent:2.8.0` (itself a child of
   `spring-boot-starter-parent:4.0.7`, so the whole Spring Boot dependency/plugin
   management is inherited transitively). The parent consolidates the reactive-BFF
   `<dependencies>` (webflux/actuator/security/oauth2-resource-server/validation,
   R2DBC + Liquibase + JDBC, springdoc, the full test stack) and the
   `dependencyManagement` for `hugmesh-commons`. Only the **wiki-specific** dependencies
   stay in this POM: `commonmark` + `commonmark-ext-gfm-tables`.

2. **Shared security/tenancy/web →** `net.hugmesh:hugmesh-commons:2.1.0`.
   The local copies of `security.{Roles,CasdoorAuthorities,DomainRegistry,DomainScope}`,
   `config.TenancyResourceServerConfig`, and `web.ApiException` were deleted and replaced
   by the commons equivalents:
   - `commons.security.Roles` / `commons.security.CasdoorAuthorities` /
     `commons.security.DomainRegistry` (security package).
   - `commons.tenancy.DomainScope` — the static `isUnscoped(...)` utility.
   - `commons.config.TenancyResourceServerConfig` — the config-gated multi-issuer wiring.
   - `commons.web.ApiException`.

   **What stays per-repo:** `security.PrincipalDomain` is a `@Component` with a
   constructor-injected `DomainRegistry` and the fail-closed `__deny__` sentinel logic in
   `forScope(jwt)`. It is a distinct class from the commons `PrincipalDomain` value object
   and is intentionally kept here. `config.SecurityConfig` (route matchers, CSRF/cookie/
   header policy) and `config.SecuritySliceSupport` for tests are also local.

   > Scope note: `hugmesh-commons` 2.1.0 carries shared **security/tenancy/web** only. The
   > newer [`hugmesh-auth-core`](https://github.com/hugmesh/hugmesh-auth-core) library
   > (shared account/session/MFA/Casdoor-admin infra) is consumed only by `user-bff` and
   > `admin-bff` — this resource server does **not** depend on it and has no
   > account/session/login surface.

   **Consumer-wiring step:** `config.CommonsSecurityConfig` `@Import`s the commons
   `DomainRegistry` + `TenancyResourceServerConfig` beans (the `@SpringBootApplication`
   scan over `net.hugmesh.wikibff` does not cover `net.hugmesh.commons.*`). The
   `@Import` preserves `TenancyResourceServerConfig`'s `@ConditionalOnExpression` gate.

### Resolving the private `hugmesh-commons` artifact

`hugmesh-commons` is **not on Maven Central** — it lives in the platform's GitLab Maven
Package Registry. There are two resolution paths; neither requires secrets in git:

- **Local dev:** `hugmesh-parent` + `hugmesh-commons` are `mvn install`ed into `~/.m2`, so
  a plain `./mvnw test` resolves them locally.
- **CI / Docker:** the committed [`settings.xml`](settings.xml) declares the
  `gitlab-maven` group registry, authenticated by the GitLab `CI_JOB_TOKEN`. That token is
  injected by GitLab at runtime and is never committed. The [`Dockerfile`](Dockerfile)
  multi-stage build runs `./mvnw -s settings.xml package` with the token supplied as a
  **BuildKit secret** (`--mount=type=secret`), so it never appears in the image layer
  history. The CI `test` job passes the token as an environment variable into the build
  container; the `build:image` job passes `--secret id=ci_job_token,env=CI_JOB_TOKEN`
  along with the `CI_API_V4_URL` and `GITLAB_GROUP_ID` build args.

## Security fixes

These were real defects found in this repo and are now fixed on `main`. They're kept here
for transparency rather than deleted from the history:

- ~~**No upload size limit on attachment uploads.**~~ **Fixed (#40):**
  `WikiAttachmentController.readCappedBytes` now reads the multipart part into a bounded
  `byte[]` capped at `app.wiki.attachment.max-bytes` (default 25 MiB) and rejects an
  oversize part with **413** at the `DataBufferUtils.join` boundary, so a single request
  can no longer drive unbounded heap usage.
- ~~**`GET /bff/wiki/superadmin/audit` drained the entire `wiki_audit_log` table.**~~
  **Fixed (#41):** `WikiAuditLogRepository.list` now takes a `limit`, clamped to
  `[1, MAX_LIMIT=1000]` (non-positive/unset falls back to `DEFAULT_LIMIT=200`), and the
  underlying `WikiAuditLogDataRepository` finders carry a runtime `LIMIT` in their `@Query`
  (a derived Spring Data finder can't express a caller-supplied bound), so the read is a
  bounded newest-first scan, never a full-table drain.
- ~~**A SUPERADMIN cross-brand ban/timeout was stamped to `default` instead of the
  victim's brand.**~~ **Fixed (#39):** `WikiAccessService.writeDomain` now resolves a
  SUPERADMIN write's brand from the request's `targetDomain` field against the trusted
  `DomainRegistry` (see [Grant / timeout / ban overlay](#grant--timeout--ban-overlay-wiki_access)
  above), and fails closed (400) in multi-issuer mode when no known target brand is given,
  instead of silently writing an override `WikiAccessGuard` would never match.

## Roadmap / out of repo

The items below are clearly marked future work. **None of this is shipped in this repo.**

- 🔭 **TIER 2 — ZIM (re)build CronJob** (out of repo): reads the `wiki_page` store and
  packages only published pages into a ZIM using zim-tools (multi-arch), writing the
  result to a snapshots PVC. The `is_published` and `published_at` columns already on
  `main` are the intended feed for this job.
- 🔭 **TIER 3 — `kiwix-serve` read path** (out of repo): mounts the PVC read-only and
  serves it (never the write path). Kiwix has no native auth, so the gateway is its
  authorization.
- ✅ **[`hugmesh-wiki-ui`](https://github.com/hugmesh/hugmesh-wiki-ui)** (out of repo,
  shipped on main): the paired Angular client — ng-openapi-gen typed client generated from
  this BFF's `/v3/api-docs`, federation contract wired, Playwright/BDD e2e harness,
  Dockerfile+nginx, CI pipeline.
- 🚧 **Pen-test standard maturation**: the SCA + ZAP DAST stages are present but
  **advisory** (`allow_failure`) and the DAST baseline is only meaningful against a
  stood-up test environment; non-advisory gating + k6 load testing are part of the
  platform's pen-test rollout, still in flight across the platform's service repos.
- 🔭 **Platform security backlog / step-up**: the platform-wide item to move UI bearer
  tokens out of `localStorage`, and Casdoor MFA / step-up enforcement
  (`gateway_route_access.mfa_required`, `amr`/`acr` → `MFA_SATISFIED`), are
  design-complete or in-flight platform features. If this BFF later participates in
  step-up-gated routes it will be noted here — it is **not** wired today.

## Scope and current limitations

No `TODO`/`FIXME` markers, stub methods, or hardcoded fake responses exist anywhere in
`src/main/java` or `src/test/java` — everything described above (content store, access
overlay, attachments) is implemented and covered by tests. The items below are scope
boundaries of this repo, not incomplete code within it:

- **Tier 2 (ZIM rebuild CronJob) is not in this repo** — this repo's `Dockerfile` builds
  only the BFF service itself; no Kubernetes CronJob manifest or `zim-tools` invocation
  exists here. The Tier 2/3 entries above describe planned external jobs/deployments, not
  code in this repository.
- **Tier 3 (`kiwix-serve` devops config) is not in this repo** — same as above, purely a
  downstream consumer of the Tier 1 content this service produces.
- **`hugmesh-wiki-ui` (the paired Angular frontend) is a separate repository** — this
  service exposes an OpenAPI contract at `/v3/api-docs` for it to consume, but no UI code
  lives here.
- **PMD / Checkstyle / SpotBugs are configured** (inherited from `hugmesh-parent`, no
  local config needed), **but none of the three currently gate this repo's CI.** Running
  them directly against the code in this repo:
  - `./mvnw checkstyle:check` and `./mvnw pmd:check` both pass clean (0 violations).
  - `./mvnw spotbugs:check` reports **4** `EI_EXPOSE_REP2` findings
    (`WikiAccessDto$AccessList`, `WikiAccessGuard`, `WikiAttachment`, `WikiDto$PageList`).
    These are the same benign constructor-injection / record-accessor pattern the parent's
    exclude filter already tolerates elsewhere in the platform; they show up here because
    this repo organizes code by **feature** (`wikibff.access`, `wikibff.wiki`) rather than
    by **layer**, and the shared filter's class-name-suffix rule doesn't cover a
    `Guard`-suffixed class, nor DTOs that live directly in a feature package rather than a
    `.dto`/`.entity`/`.model` subpackage.
  - All three analyzers bind to the Maven **`verify`** phase. This repo's CI
    (`.gitlab-ci.yml`) only runs `./mvnw test`, and the Docker build only runs
    `./mvnw package -DskipTests` — neither reaches `verify`, so none of the three run in
    CI today. Wiring `verify` in would keep PMD/Checkstyle passing but would make the
    SpotBugs job fail on the 4 findings above.
  - There's no ESLint/Prettier/Spotless in this repo — it's Java-only, with no frontend
    code, and no repo-local override of the inherited rulesets.

## Contributing

Issues and pull requests are welcome. This service follows the same stack and conventions
as the rest of the HugMesh platform's backend-for-frontend repos (Java 25, Spring
WebFlux, R2DBC, Liquibase, Casdoor JWT) — see [Build / test](#build--test) to get a local
environment running. Please include tests for any behavioral change: the suite runs
against a real Postgres via Testcontainers rather than mocks, so it's meant to catch real
regressions.

## License

[MIT](LICENSE)
