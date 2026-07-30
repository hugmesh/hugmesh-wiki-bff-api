package systems.hugmesh.wikibff.access;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import systems.hugmesh.wikibff.config.SecurityConfig;
import systems.hugmesh.wikibff.security.PrincipalDomain;
import systems.hugmesh.wikibff.web.ApiExceptionHandler;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * WAVE-2 HTTP-LAYER CROSS-DOMAIN + OWNER==SUBJECT for the grant/timeout/ban OVERLAY
 * surface ({@link WikiAccessController}).
 *
 * <p><b>Why this is MUTATION-RESISTANT (the v1 fix).</b> The first cut MOCKED
 * {@link WikiAccessService} and then RE-DERIVED the expected scope/subject in-test via a
 * test-local {@code PrincipalDomain.forScope(capturedJwt)} + {@code jwt.getSubject()}.
 * That was CIRCULAR — the production {@link WikiAccessService} scope/actor derivation
 * never ran, so the assertions checked the test's own re-implementation. (v1 also
 * carried a TAUTOLOGICAL "forged {@code authorSubject} body is inert" assertion: the
 * {@code GrantOrBan} DTO has no such field and {@code @JsonIgnoreProperties} silently
 * drops it, so nothing was actually being defended.)
 *
 * <p>This rewrite exercises the REAL derivation end-to-end: the REAL
 * {@link WikiAccessService} wired with the REAL {@link PrincipalDomain} + a MULTI-BRAND
 * registry (driven via slice {@code properties}); ONLY {@link WikiAccessRepository} is
 * mocked. We capture the {@code domain} AND the {@code grantedBy} (actor subject)
 * STRINGS the repo actually receives ({@code repo.insertActive(user, type, exp, reason,
 * grantedBy, domain)} / {@code repo.listActive(domain)} / {@code repo.historyFor(user,
 * domain)}) — the values PRODUCED by the REAL service. So if the brand-scope or the
 * actor-subject derivation were broken, the captured value would change and the test
 * would FAIL.
 *
 * <p>Stories under test:
 * <ul>
 *   <li><b>SUPERADMIN manage surface ⇒ {@code null} bypass</b> — list-all/history reach
 *       the repo with a {@code null} domain (cross-domain), derived from the super-role,
 *       never the {@code ?domain} param;</li>
 *   <li><b>ADMIN own-domain on the write seam</b> — a brand-X ADMIN's grant reaches the
 *       repo scoped to {@code brandx}; a brand-Y ADMIN's ban reaches it scoped to
 *       {@code brandy} (the OTHER brand);</li>
 *   <li><b>owner==subject (actor identity)</b> — the {@code grantedBy} the repo receives
 *       is the validated JWT {@code sub} (the REAL {@code WikiAccessService.subject});
 *       a forged {@code authorSubject} in the body never reaches the repo as the actor;</li>
 *   <li><b>ADMIN/MOD 403 on /superadmin/** + USER 403 + anonymous 401</b> on the write
 *       seam (least privilege; KEPT from v1 — already solid).</li>
 * </ul>
 */
@WebFluxTest(controllers = WikiAccessController.class, properties = {
        // Drive the SecurityConfig-imported DomainRegistry into MULTI-BRAND mode via the
        // SAME @Value properties production reads (no bean override needed).
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://id.brandx.example",
        "app.tenancy.issuers=https://id.brandx.example=brandx,https://id.brandy.example=brandy"
})
@Import({SecurityConfig.class, ApiExceptionHandler.class,
        WikiAccessCrossDomainControllerTest.RealServiceConfig.class})
class WikiAccessCrossDomainControllerTest {

    private static final String BRANDX_ISS = "https://id.brandx.example";
    private static final String BRANDY_ISS = "https://id.brandy.example";

    /**
     * The REAL {@link WikiAccessService} over the MOCKED {@link WikiAccessRepository},
     * consuming the REAL {@link PrincipalDomain} that {@link SecurityConfig} imports
     * (driven multi-brand by the slice {@code properties}). The service's scope + actor
     * derivation is genuinely exercised (NOT re-implemented in-test).
     */
    @TestConfiguration
    static class RealServiceConfig {
        @Bean
        WikiAccessService wikiAccessService(WikiAccessRepository repo, PrincipalDomain principalDomain) {
            return new WikiAccessService(repo, principalDomain);
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

    // The REAL service is wired by RealServiceConfig; ONLY the repository is mocked, so the
    // captured `domain`/`grantedBy` args are the values the REAL WikiAccessService produced.
    @MockitoBean WikiAccessRepository repo;
    @MockitoBean ReactiveJwtDecoder jwtDecoder;

    private static Consumer<Jwt.Builder> jwt(String issuer, String subject, List<String> roles) {
        return b -> b.claim("iss", issuer).issuer(issuer).subject(subject)
                .claim("roles", roles)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600));
    }

    /** mockJwt carrying a real issuer + subject (server-side identity) AND role authorities. */
    private WebTestClient as(String issuer, String subject, String role) {
        return web.mutateWith(mockJwt()
                .jwt(jwt(issuer, subject, List.of(role)))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("ROLE_" + role.toUpperCase())));
    }

    private static WikiAccess active(String type, String domain, String grantedBy) {
        return new WikiAccess("id-1", domain, "bob", type, WikiAccess.STATE_ACTIVE,
                grantedBy, "reason", Instant.now(), null, null);
    }

    // ── SUPERADMIN manage surface — cross-domain (null) bypass, issuer-keyed ──

    @Test
    void listActive_superadmin_repoQueriedWithNull_notParam() {
        when(repo.listActive(any())).thenReturn(Flux.just(
                active(WikiAccess.TYPE_READ, "brandx", "root"),
                active(WikiAccess.TYPE_BANNED, "brandy", "root")));

        as(BRANDX_ISS, "root", "superadmin").get().uri("/bff/wiki/superadmin/access?domain=brandy")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.access.length()").isEqualTo(2);

        // MUTATION-RESISTANT: the REAL service queried the repo with null (cross-domain
        // bypass), NOT "brandy" (param) or "brandx" (issuer). A broken bypass FAILS here.
        org.mockito.ArgumentCaptor<String> domain = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(repo).listActive(domain.capture());
        assertThat(domain.getValue())
                .as("SUPERADMIN list-all queries the repo with null (cross-domain); the ?domain param is inert")
                .isNull();
    }

    @Test
    void history_superadmin_repoQueriedWithNull_issuerKeyed() {
        when(repo.historyFor(eq("bob"), any())).thenReturn(Flux.just(
                new WikiAccess("id-2", "brandy", "bob", WikiAccess.TYPE_BANNED, WikiAccess.STATE_REVOKED,
                        "root", "r", Instant.now(), null, Instant.now())));

        // Even a brand-Y-issued SUPERADMIN reads cross-domain history (null scope).
        as(BRANDY_ISS, "root", "superadmin").get().uri("/bff/wiki/superadmin/access/bob/history")
                .exchange().expectStatus().isOk();

        org.mockito.ArgumentCaptor<String> domain = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(repo).historyFor(eq("bob"), domain.capture());
        assertThat(domain.getValue())
                .as("a brand-Y-issued SUPERADMIN's history read STILL queries the repo with null")
                .isNull();
    }

    // ── ADMIN is path-gated OUT of the SUPERADMIN manage surface (403) — KEPT ──

    @Test
    void listActive_admin_forbidden_manageIsSuperadminOnly() {
        as(BRANDX_ISS, "admin-x", "admin").get().uri("/bff/wiki/superadmin/access")
                .exchange().expectStatus().isForbidden();
        verify(repo, never()).listActive(any());
    }

    @Test
    void history_admin_forbidden_manageIsSuperadminOnly() {
        as(BRANDX_ISS, "admin-x", "admin").get().uri("/bff/wiki/superadmin/access/bob/history")
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void history_mod_forbidden() {
        as(BRANDX_ISS, "mod-x", "mod").get().uri("/bff/wiki/superadmin/access/bob/history")
                .exchange().expectStatus().isForbidden();
    }

    // ── write seam — scope + actor are issuer-derived (captured at the repo) ──

    @Test
    void grant_brandXAdmin_repoInsertScopedToBrandX_actorFromJwtSub() {
        // No existing active row -> the service inserts a NEW one with the derived scope +
        // actor. findActive is the scoped pre-check; insertActive is where scope+actor land.
        when(repo.findActive(eq("bob"), eq(WikiAccess.TYPE_READ), any())).thenReturn(Mono.empty());
        when(repo.insertActive(eq("bob"), eq(WikiAccess.TYPE_READ), any(), any(), any(), any()))
                .thenReturn(Mono.just(active(WikiAccess.TYPE_READ, "brandx", "admin-x")));

        as(BRANDX_ISS, "admin-x", "admin").post().uri("/bff/wiki/admin/access/grant")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"userSubject\":\"bob\",\"reason\":\"welcome\"}")
                .exchange().expectStatus().isOk();

        // MUTATION-RESISTANT cross-domain + owner==subject: capture the ACTUAL domain +
        // grantedBy the repo's insertActive received from the REAL service.
        org.mockito.ArgumentCaptor<String> grantedBy = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> domain = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(repo).insertActive(eq("bob"), eq(WikiAccess.TYPE_READ), any(), any(),
                grantedBy.capture(), domain.capture());
        assertThat(domain.getValue())
                .as("a brand-X ADMIN's grant is inserted scoped to brandx (issuer-derived), never the body/param")
                .isEqualTo("brandx");
        assertThat(grantedBy.getValue())
                .as("owner==subject: the inserted grantedBy is the validated JWT sub (admin-x)")
                .isEqualTo("admin-x");
        // The scoped pre-check is ALSO brand-X — a brand-X admin can never resolve a brand-Y row.
        verify(repo).findActive("bob", WikiAccess.TYPE_READ, "brandx");
    }

    @Test
    void ban_brandYAdmin_repoInsertScopedToBrandY_actorIsJwtSub_notForgedBody() {
        when(repo.findActive(eq("bob"), eq(WikiAccess.TYPE_BANNED), any())).thenReturn(Mono.empty());
        when(repo.insertActive(eq("bob"), eq(WikiAccess.TYPE_BANNED), any(), any(), any(), any()))
                .thenReturn(Mono.just(active(WikiAccess.TYPE_BANNED, "brandy", "admin-y")));

        // The body tries to forge a different actor via an extra "authorSubject" field —
        // it is structurally not bindable on GrantOrBan (userSubject+reason) and is dropped
        // by @JsonIgnoreProperties. The DEFENCE actually under test is at the GUARD/derivation:
        // the actor the REAL service passes to the repo is the validated JWT sub, regardless.
        as(BRANDY_ISS, "admin-y", "admin").post().uri("/bff/wiki/admin/access/ban")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"userSubject\":\"bob\",\"authorSubject\":\"someone-else\",\"reason\":\"spam\"}")
                .exchange().expectStatus().isOk();

        org.mockito.ArgumentCaptor<String> grantedBy = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> domain = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(repo).insertActive(eq("bob"), eq(WikiAccess.TYPE_BANNED), any(), any(),
                grantedBy.capture(), domain.capture());
        assertThat(domain.getValue()).as("brand-Y ADMIN's ban is inserted scoped to brandy").isEqualTo("brandy");
        assertThat(grantedBy.getValue())
                .as("owner==subject: the inserted grantedBy is the validated JWT sub (admin-y), "
                        + "NOT the body's forged authorSubject (someone-else)")
                .isEqualTo("admin-y")
                .isNotEqualTo("someone-else");
    }

    // ── REAL cross-domain isolation on the write pre-check ────────────────────

    @Test
    void grant_brandXAdmin_preChecksOnlyBrandX_neverBrandY() {
        // A brand-Y active row exists, but a brand-X admin's grant must resolve ONLY in
        // brand-X (so it can never see/reuse/clobber a brand-Y override). The REAL service
        // queries findActive scoped to brandx; the brandy row is invisible. We model the
        // repo's per-brand behaviour: the brandx pre-check is empty -> a new brandx insert.
        when(repo.findActive(eq("bob"), eq(WikiAccess.TYPE_READ), eq("brandx"))).thenReturn(Mono.empty());
        when(repo.findActive(eq("bob"), eq(WikiAccess.TYPE_READ), eq("brandy")))
                .thenReturn(Mono.just(active(WikiAccess.TYPE_READ, "brandy", "admin-y")));
        when(repo.insertActive(eq("bob"), eq(WikiAccess.TYPE_READ), any(), any(), any(), eq("brandx")))
                .thenReturn(Mono.just(active(WikiAccess.TYPE_READ, "brandx", "admin-x")));

        as(BRANDX_ISS, "admin-x", "admin").post().uri("/bff/wiki/admin/access/grant")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"userSubject\":\"bob\",\"reason\":\"welcome\"}")
                .exchange().expectStatus().isOk();

        // MUTATION-RESISTANT: the pre-check + the insert both happened in brandx ONLY. The
        // brandy row was never resolved (its findActive stub was never hit). If the scope
        // leaked to brandy, the service would have found the existing brandy row and NOT
        // inserted into brandx — the verifies below would FAIL.
        verify(repo).findActive("bob", WikiAccess.TYPE_READ, "brandx");
        verify(repo, never()).findActive("bob", WikiAccess.TYPE_READ, "brandy");
        verify(repo).insertActive(eq("bob"), eq(WikiAccess.TYPE_READ), any(), any(), eq("admin-x"), eq("brandx"));
    }

    @Test
    void grant_forbiddenForPlainUser_writersAreAdminsOnly() {
        as(BRANDX_ISS, "u", "user").post().uri("/bff/wiki/admin/access/grant")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"userSubject\":\"bob\"}")
                .exchange().expectStatus().isForbidden();
        verify(repo, never()).insertActive(any(), any(), any(), any(), any(), any());
    }

    @Test
    void grant_anonymous_unauthorized() {
        web.post().uri("/bff/wiki/admin/access/grant")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"userSubject\":\"bob\"}")
                .exchange().expectStatus().isUnauthorized();
    }
}
