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
import static org.mockito.ArgumentMatchers.eq;
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
                                WikiAttachmentRepository attachments, PrincipalDomain principalDomain) {
            return new WikiService(pages, audit, new MarkdownRenderer(), principalDomain, attachments);
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
    @MockitoBean WikiAttachmentRepository attachments;
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
        return new WikiPage("id-" + slug, domain, slug, "Title", "# md", "<h1>md</h1>",
                "author-1", 1L, Instant.now(), Instant.now(), false, null);
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
    void update_brandYAdmin_repoUpdateScopedToBrandY_authorFromJwtSub() {
        when(pages.updateBySlug(eq("home"), any(), any(), any(), any(), eq(1L), any()))
                .thenReturn(Mono.just(pageRow("brandy", "home")));
        when(auditRepo.record(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(auditRow("brandy", "wiki.page.update", "home")));

        as(BRANDY_ISS, "admin").put().uri("/bff/wiki/admin/pages/home")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"title\":\"T\",\"contentMarkdown\":\"b\",\"expectedVersion\":1}")
                .exchange().expectStatus().isOk();

        org.mockito.ArgumentCaptor<String> author = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> domain = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(pages).updateBySlug(eq("home"), any(), any(), any(), author.capture(), eq(1L), domain.capture());
        assertThat(domain.getValue()).as("update scoped to brandy (the OTHER brand, issuer-derived)").isEqualTo("brandy");
        assertThat(author.getValue()).as("re-stamped author==subject is the validated JWT sub").isEqualTo("actor-1");
    }

    @Test
    void publish_brandXAdmin_repoPublishScopedToBrandX() {
        when(pages.publishBySlug(eq("home"), any())).thenReturn(Mono.just(pageRow("brandx", "home")));
        when(auditRepo.record(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(auditRow("brandx", "wiki.page.publish", "home")));

        as(BRANDX_ISS, "admin").post().uri("/bff/wiki/admin/pages/home/publish")
                .exchange().expectStatus().isOk();

        verify(pages).publishBySlug("home", "brandx");
    }

    @Test
    void delete_brandXAdmin_repoDeleteScopedToBrandX_brandYRowInvisible() {
        // delete() first findBySlug(slug, brandx) — a brand-Y page must be invisible here, so
        // a brand-X admin can never delete another brand's page. We model the brandx lookup
        // returning the page (so delete proceeds) and prove the scoped lookup is brandx.
        when(pages.findBySlug(eq("home"), eq("brandx"))).thenReturn(Mono.just(pageRow("brandx", "home")));
        when(pages.findBySlug(eq("home"), eq("brandy"))).thenReturn(Mono.just(pageRow("brandy", "home")));
        when(attachments.deleteByPageId(any())).thenReturn(Mono.just(0L));
        when(pages.deleteBySlugAndDomain(eq("home"), any())).thenReturn(Mono.just(1L));
        when(auditRepo.record(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(auditRow("brandx", "wiki.page.delete", "home")));

        as(BRANDX_ISS, "admin").delete().uri("/bff/wiki/admin/pages/home")
                .exchange().expectStatus().isNoContent();

        // MUTATION-RESISTANT: every delete step is brand-X scoped; the brand-Y lookup is
        // never consulted (so a brand-Y page can never be deleted by a brand-X admin). A
        // leaked scope would hit findBySlug(home, brandy) and FAIL the never-verify.
        verify(pages).findBySlug("home", "brandx");
        verify(pages, never()).findBySlug("home", "brandy");
        verify(pages).deleteBySlugAndDomain("home", "brandx");
    }
}
