package net.hugmesh.wikibff.wiki;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import net.hugmesh.wikibff.access.WikiAccessGuard;
import net.hugmesh.wikibff.config.SecurityConfig;
import net.hugmesh.commons.security.DomainRegistry;
import net.hugmesh.wikibff.security.PrincipalDomain;
import net.hugmesh.wikibff.web.ApiExceptionHandler;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * WAVE-2 HTTP-LAYER AUDIT-SCOPING (SUPERADMIN cross-domain vs ADMIN own-domain) for the
 * SUPERADMIN audit surface {@code GET /bff/wiki/superadmin/audit} + the list read
 * {@code GET /bff/wiki/pages} ({@link WikiController}).
 *
 * <p><b>Why this is MUTATION-RESISTANT (the v1 fix).</b> The first cut of this test
 * MOCKED {@link WikiService}, then RE-DERIVED the expected scope in-test with a
 * test-local {@code PrincipalDomain.forScope(capturedJwt)}. That was CIRCULAR — the
 * production {@link WikiService#scopeOf}/{@link PrincipalDomain#forScope} never ran, so
 * the assertion would have passed even if prod scoping were broken (it asserted the
 * test's own re-implementation against itself).
 *
 * <p>This rewrite exercises the REAL derivation end-to-end: the service is the REAL
 * {@link WikiService} wired with the REAL {@link PrincipalDomain} + a MULTI-BRAND
 * {@link DomainRegistry}; ONLY the repositories are mocked. We capture the
 * {@code domain} STRING the repo actually receives ({@code audit.list(domain)} /
 * {@code pages.list(domain)}) — the value PRODUCED by {@code WikiService.scopeOf ->
 * PrincipalDomain.forScope}. So if {@code forScope} were broken (e.g. returned the
 * {@code ?domain} param, or the wrong brand, or the {@code null} bypass for a scoped
 * ADMIN), the CAPTURED domain would change and the test would FAIL. The test no longer
 * re-implements any production logic.
 *
 * <p>Stories under test:
 * <ul>
 *   <li><b>SUPERADMIN ⇒ {@code null} (cross-domain bypass)</b> — the audit read reaches
 *       the repo with a {@code null} domain (NO predicate), derived by the REAL service
 *       from the super-role, even though the token carries a concrete brand {@code iss}
 *       and a forged {@code ?domain=brandy};</li>
 *   <li><b>brand-X ADMIN ⇒ {@code brandx}</b> — a brand-X ADMIN's list read reaches the
 *       repo scoped to {@code brandx} (issuer-derived), never the wider/other brand and
 *       never the {@code ?domain} param;</li>
 *   <li><b>brand-Y ADMIN ⇒ {@code brandy}</b> — own-domain scoping for the other brand,
 *       and a REAL cross-domain isolation case (brand-Y's row is NOT returned to a
 *       brand-X reader because the repo is queried with {@code domain=brandx});</li>
 *   <li><b>ADMIN/MOD 403 + anonymous 401</b> on the SUPERADMIN-only audit surface (least
 *       privilege; KEPT from v1 — these were already solid).</li>
 * </ul>
 */
@WebFluxTest(controllers = WikiController.class, properties = {
        // Drive the SecurityConfig-imported DomainRegistry into MULTI-BRAND mode via the
        // SAME @Value properties production reads, so brandx/brandy issuers resolve to
        // concrete brands. No bean override needed — the imported registry IS multi-brand.
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://id.brandx.example",
        "app.tenancy.issuers=https://id.brandx.example=brandx,https://id.brandy.example=brandy"
})
@Import({SecurityConfig.class, ApiExceptionHandler.class,
        WikiAuditScopingControllerTest.RealServiceConfig.class})
class WikiAuditScopingControllerTest {

    private static final String BRANDX_ISS = "https://id.brandx.example";
    private static final String BRANDY_ISS = "https://id.brandy.example";

    /**
     * The REAL {@link WikiService} over MOCKED repositories, consuming the REAL
     * {@link PrincipalDomain}/{@link DomainRegistry} that {@link SecurityConfig} imports
     * (driven multi-brand by the slice {@code properties} above). {@code WikiService} is a
     * {@code @Service} (not scanned by {@code @WebFluxTest}), so we declare it here; the
     * {@code WikiService.scopeOf -> PrincipalDomain.forScope} derivation is genuinely
     * exercised (NOT re-implemented in-test).
     */
    @TestConfiguration
    static class RealServiceConfig {
        @Bean
        WikiService wikiService(WikiPageRepository pages, WikiAuditLogRepository audit,
                                PrincipalDomain principalDomain) {
            return new WikiService(pages, audit, new MarkdownRenderer(), principalDomain);
        }
    }

    @Autowired ApplicationContext context;

    WebTestClient web;

    // Spring Security 7: mockJwt()'s SecurityContext is only honoured when the
    // springSecurity() configurer's MutatorFilter is in the chain. A WebTestClient
    // autowired from @WebFluxTest lacks that configurer (mutator dropped -> 401), so
    // build it from the ApplicationContext with .apply(springSecurity()). Anonymous
    // legs still resolve to 401 exactly as before.
    @BeforeEach
    void setUpSecuredClient() {
        web = WebTestClient.bindToApplicationContext(context).apply(springSecurity()).build();
    }

    // The REAL service is wired by RealServiceConfig; ONLY the repositories are mocked, so
    // the captured `domain` arg is the value the REAL WikiService.scopeOf produced.
    @MockitoBean WikiPageRepository pages;
    @MockitoBean WikiAuditLogRepository auditRepo;
    @MockitoBean WikiAccessGuard accessGuard;
    @MockitoBean ReactiveJwtDecoder jwtDecoder;

    private static Consumer<Jwt.Builder> jwt(String issuer, List<String> roles) {
        return b -> b.claim("iss", issuer).issuer(issuer).subject("actor-1")
                .claim("roles", roles)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600));
    }

    /** mockJwt carrying BOTH a real issuer claim (so the REAL PrincipalDomain resolves a
     *  brand) AND the role authorities (so the @PreAuthorize/path gates evaluate). */
    private WebTestClient as(String issuer, String role) {
        String roleAuthority = "ROLE_" + role.toUpperCase();
        return web.mutateWith(mockJwt()
                .jwt(jwt(issuer, List.of(role)))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"),
                        new org.springframework.security.core.authority.SimpleGrantedAuthority(roleAuthority)));
    }

    /** Capture the {@code domain} STRING the audit repo was queried with — the value the
     *  REAL {@code WikiService.scopeOf -> PrincipalDomain.forScope} produced. */
    private String capturedAuditDomain() {
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        // The bounded read forwards a clamped limit alongside the domain (list(domain, limit));
        // only the domain is asserted on here — the cap is exercised in WikiAuditTrailCapTest.
        verify(auditRepo).list(captor.capture(), anyInt());
        return captor.getValue();
    }

    private String capturedListDomain() {
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(pages).list(captor.capture());
        return captor.getValue();
    }

    private static WikiAuditLog auditRow(String domain, String action, String target) {
        return new WikiAuditLog("a-1", domain, "actor-1", action, target, "v1", Instant.now());
    }

    private static WikiPage pageRow(String domain, String slug) {
        return pageRow(domain, slug, "id-" + slug);
    }

    /** A row with an EXPLICIT id — the delete/update/publish tests pin the write to the
     *  resolved row's id, so two same-slug rows in different brands must be distinguishable
     *  by id. */
    private static WikiPage pageRow(String domain, String slug, String id) {
        return pageRow(domain, slug, id, 1L);
    }

    /** A row with an EXPLICIT id AND version — for stale-version-conflict cases where the
     *  current stored version differs from what the caller expected. */
    private static WikiPage pageRow(String domain, String slug, String id, long version) {
        return new WikiPage(id, domain, slug, "Title", "# md", "<h1>md</h1>",
                "author-1", version, Instant.now(), Instant.now(), false, null);
    }

    /** Capture the {@code domain} the delete audit row was stamped with — produced by the
     *  REAL {@code WikiService.delete} from the RESOLVED page, not from the caller's scope. */
    private String capturedDeleteAuditDomain() {
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(auditRepo).record(any(), eq("wiki.page.delete"), any(), any(), captor.capture());
        return captor.getValue();
    }

    // ── SUPERADMIN ⇒ cross-domain (null) bypass, derived by the REAL service ──

    @Test
    void superadmin_auditTrail_repoQueriedWithNullCrossDomainBypass_notParam() {
        // The cross-domain bypass: the repo, queried with a null domain, returns EVERY
        // brand's rows. The REAL WikiService.scopeOf must produce null for a SUPERADMIN.
        when(auditRepo.list(any(), anyInt())).thenReturn(Flux.just(
                auditRow("brandx", "wiki.page.create", "home"),
                auditRow("brandy", "wiki.page.update", "faq")));

        // SUPERADMIN token whose iss is brand-X, ALSO carrying a forged ?domain=brandy.
        as(BRANDX_ISS, "superadmin").get().uri("/bff/wiki/superadmin/audit?domain=brandy")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.actions.length()").isEqualTo(2);

        // MUTATION-RESISTANT: the domain the REAL service forwarded to the repo is null —
        // the SUPERADMIN cross-domain bypass — NOT the brand the issuer maps to and NOT
        // the ?domain=brandy query param. If forScope were broken (e.g. returned "brandx"
        // or "brandy"), this capture would NOT be null and the test would FAIL.
        assertThat(capturedAuditDomain())
                .as("the REAL WikiService.scopeOf produced null (cross-domain bypass) for a "
                        + "SUPERADMIN — derived from the super-role, never the ?domain query param")
                .isNull();
    }

    @Test
    void superadmin_issuerIsBrandY_repoStillQueriedWithNull_paramInert() {
        when(auditRepo.list(any(), anyInt())).thenReturn(Flux.empty());

        // A SUPERADMIN minted by brand-Y's Casdoor, with a forged ?domain=brandx param.
        as(BRANDY_ISS, "superadmin").get().uri("/bff/wiki/superadmin/audit?domain=brandx")
                .exchange().expectStatus().isOk();

        // SUPERADMIN remains cross-domain regardless of which brand issued it; the
        // ?domain=brandx param is inert — the REAL derivation keys off the super-role.
        assertThat(capturedAuditDomain())
                .as("a brand-Y-issued SUPERADMIN STILL hits the repo with null; the param cannot narrow it")
                .isNull();
    }

    // ── ADMIN is path-gated OUT of the SUPERADMIN manage surface (403) — KEPT ──

    @Test
    void admin_forbiddenOnSuperadminAuditSurface_manageIsSuperadminOnly() {
        // Defence-in-depth: an ADMIN (even of the issuing brand) cannot read the audit
        // trail — /bff/wiki/superadmin/** is SUPERADMIN-only end-to-end. The read is
        // never forwarded to the repo.
        as(BRANDX_ISS, "admin").get().uri("/bff/wiki/superadmin/audit")
                .exchange().expectStatus().isForbidden();

        assertThat(mockingDetails(auditRepo).getInvocations())
                .as("a 403'd ADMIN never reaches the audit read")
                .noneMatch(i -> i.getMethod().getName().equals("list"));
    }

    @Test
    void mod_forbiddenOnSuperadminAuditSurface() {
        as(BRANDX_ISS, "mod").get().uri("/bff/wiki/superadmin/audit")
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void anonymous_unauthorizedOnSuperadminAuditSurface() {
        web.get().uri("/bff/wiki/superadmin/audit").exchange().expectStatus().isUnauthorized();
    }

    // ── ADMIN own-domain scoping: brand-X ADMIN ⇒ brandx (issuer-derived) ─────

    @Test
    void list_brandXAdmin_repoQueriedWithBrandX_derivedFromIssuer_notParam() {
        // The .authenticated() read floor; the guard ALLOWs; the role is admin so the role
        // half qualifies. The REAL WikiService.scopeOf must produce "brandx" from the iss.
        when(accessGuard.assertCanRead(any())).thenReturn(Mono.empty());
        when(pages.list(any())).thenReturn(Flux.just(pageRow("brandx", "home")));

        as(BRANDX_ISS, "admin").get().uri("/bff/wiki/pages?domain=brandy")
                .exchange().expectStatus().isOk();

        // MUTATION-RESISTANT: the repo is queried with "brandx" (issuer-derived) — never
        // the ?domain=brandy param. A broken forScope (param-keyed, or the null bypass for
        // a scoped ADMIN) would change this captured value and FAIL the test.
        assertThat(capturedListDomain())
                .as("a brand-X ADMIN's list read hits the repo scoped to brandx (issuer-derived), "
                        + "never the ?domain param and never the null bypass")
                .isEqualTo("brandx");
    }

    // ── ADMIN own-domain scoping: brand-Y ADMIN ⇒ brandy (the OTHER brand) ────

    @Test
    void list_brandYAdmin_repoQueriedWithBrandY_ownDomain() {
        when(accessGuard.assertCanRead(any())).thenReturn(Mono.empty());
        when(pages.list(any())).thenReturn(Flux.just(pageRow("brandy", "faq")));

        as(BRANDY_ISS, "admin").get().uri("/bff/wiki/pages")
                .exchange().expectStatus().isOk();

        assertThat(capturedListDomain())
                .as("a brand-Y ADMIN's list read hits the repo scoped to brandy (its OWN brand)")
                .isEqualTo("brandy");
    }

    // ── REAL cross-domain isolation: a brand-Y row is NOT visible to a brand-X reader ──

    @Test
    void list_brandXAdmin_brandYRowExists_notReturned_repoScopedToBrandX() {
        // The repo holds a brand-Y row, but a brand-X reader queries the repo scoped to
        // "brandx". We model the REAL repo behaviour (the brand-X query returns ONLY
        // brand-X rows) by stubbing the brandx-scoped call to return empty — the brand-Y
        // row is invisible because the SQL predicate is domain=brandx, not because the
        // test hides it after the fact.
        when(accessGuard.assertCanRead(any())).thenReturn(Mono.empty());
        when(pages.list("brandx")).thenReturn(Flux.empty());           // brand-X query: no brand-Y leak
        when(pages.list("brandy")).thenReturn(Flux.just(pageRow("brandy", "secret-y")));

        as(BRANDX_ISS, "admin").get().uri("/bff/wiki/pages")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.pages.length()").isEqualTo(0);

        // MUTATION-RESISTANT cross-domain isolation: the brand-X reader queried the repo
        // with "brandx" (so the brand-Y row was never selected). If forScope leaked the
        // wrong brand ("brandy") or the null bypass, the stub would return the brand-Y row
        // and the body length assertion (==0) would FAIL.
        assertThat(capturedListDomain())
                .as("the brand-X reader queried the repo with brandx — the brand-Y row is "
                        + "invisible at the SQL predicate, not hidden after the fact")
                .isEqualTo("brandx");
    }

    // ── WRITE SEAM (create/update/publish/delete) — scope + author captured at the repo ──
    // The /bff/wiki/admin/** write methods flow through the SAME WikiService.scopeOf ->
    // PrincipalDomain.forScope + WikiService.subject(jwt) derivation. Un-mocking the service
    // and capturing the repo's `domain` + `authorSubject` args makes the write seam's
    // cross-domain + owner==subject assertions mutation-resistant too — a body cannot widen
    // the scope or change the author.

    @Test
    void create_brandXAdmin_repoCreateScopedToBrandX_authorFromJwtSub() {
        // No pre-existing slug -> the service inserts. create() flows domain + author into
        // pages.create(slug, title, md, html, author, domain).
        when(pages.findBySlug(eq("new-page"), any())).thenReturn(Mono.empty());
        when(pages.create(eq("new-page"), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(pageRow("brandx", "new-page")));
        when(auditRepo.record(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(auditRow("brandx", "wiki.page.create", "new-page")));

        as(BRANDX_ISS, "admin").post().uri("/bff/wiki/admin/pages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"slug\":\"new-page\",\"title\":\"New\",\"contentMarkdown\":\"# New\"}")
                .exchange().expectStatus().isOk();

        // MUTATION-RESISTANT: the repo create got domain=brandx (issuer-derived) +
        // author=actor-1 (validated JWT sub), never a body/param value.
        org.mockito.ArgumentCaptor<String> author = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> domain = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(pages).create(eq("new-page"), any(), any(), any(), author.capture(), domain.capture());
        assertThat(domain.getValue()).as("create scoped to brandx (issuer-derived)").isEqualTo("brandx");
        assertThat(author.getValue()).as("author==subject is the validated JWT sub").isEqualTo("actor-1");
        // The duplicate-slug pre-check is ALSO brand-X scoped (a brand-Y slug is invisible).
        verify(pages).findBySlug("new-page", "brandx");
    }

    @Test
    void update_brandYAdmin_resolvesInBrandY_updatesThatRowsId_brandXRowUnreachable() {
        // update() first findBySlug(slug, brandy) — a brand-X page must be invisible here,
        // so a brand-Y admin can never update another brand's page. Mirrors the delete
        // rewrite below: prove BOTH that the scoped lookup is brandy AND that the update
        // is pinned to the id that lookup returned.
        when(pages.findBySlug(eq("home"), eq("brandy"))).thenReturn(Mono.just(pageRow("brandy", "home", "id-y-home")));
        when(pages.findBySlug(eq("home"), eq("brandx"))).thenReturn(Mono.just(pageRow("brandx", "home", "id-x-home")));
        when(pages.updateById(eq("id-y-home"), any(), any(), any(), any(), eq(1L)))
                .thenReturn(Mono.just(pageRow("brandy", "home", "id-y-home")));
        when(auditRepo.record(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(auditRow("brandy", "wiki.page.update", "home")));

        as(BRANDY_ISS, "admin").put().uri("/bff/wiki/admin/pages/home")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"title\":\"T\",\"contentMarkdown\":\"b\",\"expectedVersion\":1}")
                .exchange().expectStatus().isOk();

        // MUTATION-RESISTANT: the resolve is brand-Y scoped and the brand-X lookup is
        // never consulted; the update is by the RESOLVED row's id, so brand-X's same-slug
        // row (a different id) is structurally unreachable — there is no domain left to
        // coerce or leak.
        org.mockito.ArgumentCaptor<String> author = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(pages).findBySlug("home", "brandy");
        verify(pages, never()).findBySlug("home", "brandx");
        verify(pages).updateById(eq("id-y-home"), any(), any(), any(), author.capture(), eq(1L));
        verify(pages, never()).updateById(eq("id-x-home"), any(), any(), any(), any(), anyLong());
        assertThat(author.getValue()).as("re-stamped author==subject is the validated JWT sub").isEqualTo("actor-1");
        org.mockito.ArgumentCaptor<String> auditDomain = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(auditRepo).record(any(), eq("wiki.page.update"), any(), any(), auditDomain.capture());
        assertThat(auditDomain.getValue()).as("the update audit lands in the brand the page lived in").isEqualTo("brandy");
    }

    // ── SUPERADMIN update/publish (issue #33 / decision 59): resolve cross-brand, act on
    //    THAT row's id, audit in ITS brand — reproduces #17's measured probe exactly ──

    @Test
    void update_superadmin_pageInNonDefaultBrand_updatesResolvedRowById_auditsInPageBrand() {
        // Issue #33's probe: a SUPERADMIN's scope is null (cross-brand resolve) and the
        // OLD update coerced that null to "default" — UPDATE ... WHERE slug=? AND
        // domain='default' AND version=1 — which never matched brandy's row, and the
        // secondary read that built the 409 message was ITSELF cross-brand, so it echoed
        // the resolved row's OWN version back as "stale" against itself: a
        // self-contradicting "page is at version 1, not 1". The fix updates by the
        // resolved id, so this now succeeds.
        when(pages.findBySlug(eq("runbook"), isNull()))
                .thenReturn(Mono.just(pageRow("brandy", "runbook", "id-y-runbook")));
        when(pages.updateById(eq("id-y-runbook"), any(), any(), any(), any(), eq(1L)))
                .thenReturn(Mono.just(pageRow("brandy", "runbook", "id-y-runbook", 2L)));
        when(auditRepo.record(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(auditRow("brandy", "wiki.page.update", "runbook")));

        as(BRANDX_ISS, "superadmin").put().uri("/bff/wiki/admin/pages/runbook")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"title\":\"T\",\"contentMarkdown\":\"b\",\"expectedVersion\":1}")
                .exchange().expectStatus().isOk();

        verify(pages).findBySlug("runbook", null);
        verify(pages).updateById(eq("id-y-runbook"), any(), any(), any(), any(), eq(1L));
        org.mockito.ArgumentCaptor<String> auditDomain = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(auditRepo).record(any(), eq("wiki.page.update"), any(), any(), auditDomain.capture());
        assertThat(auditDomain.getValue())
                .as("the update audit is stamped with the brand the page actually lived in")
                .isEqualTo("brandy");
    }

    @Test
    void update_resolvedRowVanishes_is404_neverTheSelfContradicting409() {
        // The resolved row vanished before the UPDATE ran (a concurrent delete). updateById
        // matches zero rows; the secondary re-read is BY THE SAME ID (never slug+domain), so
        // an id that is genuinely gone is a 404, not a re-derivation that could contradict
        // itself the way the pre-fix slug+domain re-read did.
        when(pages.findBySlug(eq("gone"), isNull()))
                .thenReturn(Mono.just(pageRow("brandy", "gone", "id-y-gone")));
        when(pages.updateById(eq("id-y-gone"), any(), any(), any(), any(), eq(1L))).thenReturn(Mono.empty());
        when(pages.findById(eq("id-y-gone"), isNull())).thenReturn(Mono.empty());

        as(BRANDX_ISS, "superadmin").put().uri("/bff/wiki/admin/pages/gone")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"title\":\"T\",\"contentMarkdown\":\"b\",\"expectedVersion\":1}")
                .exchange().expectStatus().isNotFound();

        verify(pages).findById("id-y-gone", null);
        verify(auditRepo, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void update_genuineStaleVersion_is409_reportsCurrentVersionFromTheSameResolvedId() {
        // A real concurrent edit: updateById's version guard matches zero rows because the
        // row moved on (not because of a coerced domain). The secondary read is BY ID, so
        // it reports the SAME row's current version — never a different brand's row.
        when(pages.findBySlug(eq("home"), eq("brandy")))
                .thenReturn(Mono.just(pageRow("brandy", "home", "id-y-home")));
        when(pages.updateById(eq("id-y-home"), any(), any(), any(), any(), eq(1L))).thenReturn(Mono.empty());
        when(pages.findById(eq("id-y-home"), isNull()))
                .thenReturn(Mono.just(pageRow("brandy", "home", "id-y-home", 3L)));

        as(BRANDY_ISS, "admin").put().uri("/bff/wiki/admin/pages/home")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"title\":\"T\",\"contentMarkdown\":\"b\",\"expectedVersion\":1}")
                .exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.error")
                .isEqualTo("stale edit: page 'home' is at version 3, not 1");

        verify(auditRepo, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void publish_brandXAdmin_resolvesInBrandX_publishesThatRowsId_brandYRowUnreachable() {
        when(pages.findBySlug(eq("home"), eq("brandx"))).thenReturn(Mono.just(pageRow("brandx", "home", "id-x-home")));
        when(pages.findBySlug(eq("home"), eq("brandy"))).thenReturn(Mono.just(pageRow("brandy", "home", "id-y-home")));
        when(pages.publishById("id-x-home")).thenReturn(Mono.just(pageRow("brandx", "home", "id-x-home")));
        when(auditRepo.record(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(auditRow("brandx", "wiki.page.publish", "home")));

        as(BRANDX_ISS, "admin").post().uri("/bff/wiki/admin/pages/home/publish")
                .exchange().expectStatus().isOk();

        verify(pages).findBySlug("home", "brandx");
        verify(pages, never()).findBySlug("home", "brandy");
        verify(pages).publishById("id-x-home");
        verify(pages, never()).publishById("id-y-home");
    }

    @Test
    void publish_superadmin_pageInNonDefaultBrand_publishesResolvedRowById_auditsInPageBrand() {
        // Issue #33's other probe: the OLD publish coerced a SUPERADMIN's null scope to
        // "default" — UPDATE ... WHERE slug=? AND domain='default' — which never matched
        // brandy's row, so POST /publish answered 404 for a page the SAME caller could
        // GET moments earlier. The fix publishes by the resolved id, so this now succeeds.
        when(pages.findBySlug(eq("runbook"), isNull()))
                .thenReturn(Mono.just(pageRow("brandy", "runbook", "id-y-runbook")));
        when(pages.publishById("id-y-runbook")).thenReturn(Mono.just(pageRow("brandy", "runbook", "id-y-runbook")));
        when(auditRepo.record(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(auditRow("brandy", "wiki.page.publish", "runbook")));

        as(BRANDX_ISS, "superadmin").post().uri("/bff/wiki/admin/pages/runbook/publish")
                .exchange().expectStatus().isOk();

        verify(pages).findBySlug("runbook", null);
        verify(pages).publishById("id-y-runbook");
        org.mockito.ArgumentCaptor<String> auditDomain = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(auditRepo).record(any(), eq("wiki.page.publish"), any(), any(), auditDomain.capture());
        assertThat(auditDomain.getValue())
                .as("the publish audit is stamped with the brand the page actually lived in")
                .isEqualTo("brandy");
    }

    @Test
    void publish_resolvedRowVanishes_is404_neverAudited() {
        when(pages.findBySlug(eq("gone"), isNull()))
                .thenReturn(Mono.just(pageRow("brandy", "gone", "id-y-gone")));
        when(pages.publishById("id-y-gone")).thenReturn(Mono.empty());

        as(BRANDX_ISS, "superadmin").post().uri("/bff/wiki/admin/pages/gone/publish")
                .exchange().expectStatus().isNotFound();

        verify(pages).publishById("id-y-gone");
        verify(auditRepo, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void delete_brandXAdmin_resolvesInBrandX_deletesThatRowsId_brandYRowUnreachable() {
        // delete() first findBySlug(slug, brandx) — a brand-Y page must be invisible here, so
        // a brand-X admin can never delete another brand's page. We model the brandx lookup
        // returning the brand-X row (so delete proceeds) and prove BOTH that the scoped lookup
        // is brandx AND that the delete is pinned to the id that lookup returned.
        when(pages.findBySlug(eq("home"), eq("brandx"))).thenReturn(Mono.just(pageRow("brandx", "home", "id-x-home")));
        when(pages.findBySlug(eq("home"), eq("brandy"))).thenReturn(Mono.just(pageRow("brandy", "home", "id-y-home")));
        when(pages.deleteById("id-x-home")).thenReturn(Mono.just(1L));
        when(auditRepo.record(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(auditRow("brandx", "wiki.page.delete", "home")));

        as(BRANDX_ISS, "admin").delete().uri("/bff/wiki/admin/pages/home")
                .exchange().expectStatus().isNoContent();

        // MUTATION-RESISTANT: the resolve is brand-X scoped and the brand-Y lookup is never
        // consulted; the delete is by the RESOLVED row's id, so brand-Y's same-slug row (a
        // different id) is structurally unreachable — there is no domain left to leak.
        verify(pages).findBySlug("home", "brandx");
        verify(pages, never()).findBySlug("home", "brandy");
        verify(pages).deleteById("id-x-home");
        verify(pages, never()).deleteById("id-y-home");
        assertThat(capturedDeleteAuditDomain())
                .as("the delete audit lands in the brand the page lived in")
                .isEqualTo("brandx");
    }

    // ── SUPERADMIN delete (issue #15): resolve cross-brand, delete THAT row, audit in ITS brand ──

    @Test
    void delete_superadmin_pageInNonDefaultBrand_deletesResolvedRowById_auditsInPageBrand() {
        // Issue #15: a SUPERADMIN's scope is null (cross-brand resolve) and the old delete
        // coerced that null to "default" — DELETE ... WHERE slug=? AND domain='default' —
        // which never matched brand-Y's row (204, page left behind, attachments already gone)
        // or, worse, removed default's same-slug page. The fix deletes by the resolved id.
        when(pages.findBySlug(eq("runbook"), isNull()))
                .thenReturn(Mono.just(pageRow("brandy", "runbook", "id-y-runbook")));
        when(pages.deleteById("id-y-runbook")).thenReturn(Mono.just(1L));
        when(auditRepo.record(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(auditRow("brandy", "wiki.page.delete", "runbook")));

        as(BRANDX_ISS, "superadmin").delete().uri("/bff/wiki/admin/pages/runbook")
                .exchange().expectStatus().isNoContent();

        // MUTATION-RESISTANT: the resolve used the null bypass (derived by the REAL service
        // from the super-role), the delete is pinned to the id that resolve returned, and
        // the audit domain is the PAGE's brand — not null/"default", not the issuer's brandx.
        verify(pages).findBySlug("runbook", null);
        verify(pages).deleteById("id-y-runbook");
        assertThat(capturedDeleteAuditDomain())
                .as("the delete audit is stamped with the brand the page actually lived in")
                .isEqualTo("brandy");
    }

    @Test
    void delete_zeroRowsMatched_is404_neverAudited() {
        // The resolved row vanished before the DELETE ran (a concurrent delete). The old code
        // discarded the count and answered 204 regardless; now "matched nothing" is a 404 and
        // nothing is written to the audit trail for a delete that did not happen.
        when(pages.findBySlug(eq("gone"), isNull()))
                .thenReturn(Mono.just(pageRow("brandy", "gone", "id-y-gone")));
        when(pages.deleteById("id-y-gone")).thenReturn(Mono.just(0L));

        as(BRANDX_ISS, "superadmin").delete().uri("/bff/wiki/admin/pages/gone")
                .exchange().expectStatus().isNotFound();

        verify(pages).deleteById("id-y-gone");
        verify(auditRepo, never()).record(any(), any(), any(), any(), any());
    }
}
