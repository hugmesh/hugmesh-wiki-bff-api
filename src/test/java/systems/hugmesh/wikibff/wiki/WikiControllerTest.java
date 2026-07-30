package systems.hugmesh.wikibff.wiki;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import systems.hugmesh.wikibff.access.WikiAccessGuard;
import systems.hugmesh.wikibff.config.SecurityConfig;
import systems.hugmesh.commons.web.ApiException;
import systems.hugmesh.wikibff.web.ApiExceptionHandler;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * Wiki surface gating + contract (reactive @WebFluxTest). The role-cascade story under
 * test (the WIKI_READ / WIKI_WRITE / WIKI_MANAGE composites):
 *
 * <ul>
 *   <li>READ (list/get) — the method gate is now {@code isAuthenticated()}; the REAL read
 *       decision (the WIKI_READ role composite OR an active grant, AND NOT an active ban,
 *       SUPERADMIN exempt) is enforced by {@link WikiAccessGuard}, MOCKED here so the
 *       controller-wiring contract is isolated from the guard's own (separately tested)
 *       logic: an ALLOW lets the read through; a guard 403 (the role-deny OR the ban) is a
 *       FORBIDDEN. The guard's own precedence is proven in WikiAccessGuardTest.</li>
 *   <li>WIKI_WRITE (create/update/publish, under /bff/wiki/admin/**) — ALLOWED for ADMIN
 *       + SUPERADMIN; FORBIDDEN for a MOD+WIKI reader (writers are admins only) — denied
 *       at the path level (the admin prefix is ADMIN end-to-end).</li>
 *   <li>WIKI_MANAGE (audit, under /bff/wiki/superadmin/**) — ALLOWED for SUPERADMIN only;
 *       FORBIDDEN for a plain ADMIN — denied at the path level.</li>
 * </ul>
 * @PreAuthorize is active via @EnableReactiveMethodSecurity in the imported SecurityConfig.
 */
@WebFluxTest(controllers = WikiController.class)
@Import({SecurityConfig.class, ApiExceptionHandler.class})
class WikiControllerTest {

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

    @MockitoBean WikiService wiki;
    @MockitoBean WikiAccessGuard accessGuard;
    @MockitoBean ReactiveJwtDecoder jwtDecoder;

    /** Default: the read guard ALLOWS (so the WIKI_WRITE/MANAGE path-level tests are not
     *  perturbed by it). The read-specific tests below override per-case. */
    @BeforeEach
    void allowReadByDefault() {
        when(accessGuard.assertCanRead(any())).thenReturn(Mono.empty());
    }

    private WebTestClient asUser() {
        return web.mutateWith(mockJwt().authorities(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private WebTestClient asModWiki() {
        return web.mutateWith(mockJwt().authorities(
                new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_MOD"),
                new SimpleGrantedAuthority("ROLE_WIKI")));
    }

    private WebTestClient asModNoWiki() {
        return web.mutateWith(mockJwt().authorities(
                new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_MOD")));
    }

    private WebTestClient asAdmin() {
        return web.mutateWith(mockJwt().authorities(
                new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private WebTestClient asSuperadmin() {
        return web.mutateWith(mockJwt().authorities(
                new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_SUPERADMIN")));
    }

    private static WikiDto.Page page(String slug) {
        return new WikiDto.Page("id-1", "default", slug, "Title", "# md", "<h1>md</h1>",
                "author-1", 1L, Instant.now(), Instant.now(), false, null);
    }

    /** Make the read guard DENY (the role-deny or the ban precedence) for this case. */
    private void guardDenies() {
        when(accessGuard.assertCanRead(any()))
                .thenReturn(Mono.error(ApiException.forbidden("denied")));
    }

    // ── READ (guard-enforced) ───────────────────────────────────────────
    @Test
    void list_allowedForAdmin() {
        when(wiki.list(any())).thenReturn(Flux.just(
                new WikiDto.PageSummary("id-1", "home", "Home", "a", 1L, Instant.now())));
        asAdmin().get().uri("/bff/wiki/pages").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.pages[0].slug").isEqualTo("home");
    }

    @Test
    void list_allowedForModWithWiki() {
        when(wiki.list(any())).thenReturn(Flux.empty());
        asModWiki().get().uri("/bff/wiki/pages").exchange().expectStatus().isOk();
    }

    @Test
    void list_forbiddenForModWithoutWiki() {
        // The guard's role-deny: a MOD missing ROLE_WIKI (and with no grant) is NOT a reader.
        when(wiki.list(any())).thenReturn(Flux.empty());   // never reached (guard short-circuits)
        guardDenies();
        asModNoWiki().get().uri("/bff/wiki/pages").exchange().expectStatus().isForbidden();
    }

    @Test
    void list_forbiddenForPlainUser() {
        // The guard's role-deny: a plain USER with no qualifying role and no grant.
        when(wiki.list(any())).thenReturn(Flux.empty());   // never reached (guard short-circuits)
        guardDenies();
        asUser().get().uri("/bff/wiki/pages").exchange().expectStatus().isForbidden();
    }

    @Test
    void get_allowedForModWithWiki() {
        when(wiki.get(eq("home"), any())).thenReturn(Mono.just(page("home")));
        asModWiki().get().uri("/bff/wiki/pages/home").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.slug").isEqualTo("home");
    }

    @Test
    void get_forbiddenWhenGuardDenies_banPrecedence() {
        // The ban precedence: the guard denies even a would-be reader; the read never runs.
        when(wiki.get(any(), any())).thenReturn(Mono.empty());  // never reached (guard short-circuits)
        guardDenies();
        asModWiki().get().uri("/bff/wiki/pages/home").exchange().expectStatus().isForbidden();
    }

    // ── WIKI_WRITE (create/update/publish) ──────────────────────────────
    @Test
    void create_allowedForAdmin_rendersAndReturns() {
        when(wiki.create(any(), any())).thenReturn(Mono.just(page("new-page")));
        asAdmin().post().uri("/bff/wiki/admin/pages").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"slug\":\"new-page\",\"title\":\"New\",\"contentMarkdown\":\"# New\"}")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.slug").isEqualTo("new-page");
    }

    @Test
    void create_400WhenSlugMissing() {
        asAdmin().post().uri("/bff/wiki/admin/pages").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"title\":\"New\",\"contentMarkdown\":\"# New\"}")
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void create_400WhenSlugNotUrlSafe() {
        asAdmin().post().uri("/bff/wiki/admin/pages").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"slug\":\"Not A Slug!\",\"title\":\"New\",\"contentMarkdown\":\"# New\"}")
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void create_forbiddenForModWiki_writersAreAdminsOnly() {
        // The /bff/wiki/admin/** prefix is ADMIN end-to-end; a MOD+WIKI reader is denied.
        asModWiki().post().uri("/bff/wiki/admin/pages").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"slug\":\"x\",\"title\":\"x\",\"contentMarkdown\":\"x\"}")
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void update_allowedForSuperadmin() {
        when(wiki.update(eq("home"), any(), any())).thenReturn(Mono.just(page("home")));
        asSuperadmin().put().uri("/bff/wiki/admin/pages/home").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"title\":\"T\",\"contentMarkdown\":\"b\",\"expectedVersion\":1}")
                .exchange().expectStatus().isOk();
    }

    @Test
    void publish_allowedForAdmin() {
        when(wiki.publish(eq("home"), any())).thenReturn(Mono.just(page("home")));
        asAdmin().post().uri("/bff/wiki/admin/pages/home/publish").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.state").isEqualTo("published");
    }

    @Test
    void deletePage_allowedForAdmin() {
        when(wiki.delete(eq("home"), any())).thenReturn(Mono.empty());
        asAdmin().delete().uri("/bff/wiki/admin/pages/home").exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void deletePage_allowedForSuperadmin() {
        when(wiki.delete(eq("home"), any())).thenReturn(Mono.empty());
        asSuperadmin().delete().uri("/bff/wiki/admin/pages/home").exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void deletePage_forbiddenForModWiki() {
        asModWiki().delete().uri("/bff/wiki/admin/pages/home").exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void deletePage_forbiddenForPlainUser() {
        asUser().delete().uri("/bff/wiki/admin/pages/home").exchange()
                .expectStatus().isForbidden();
    }

    // ── WIKI_MANAGE (audit) ─────────────────────────────────────────────
    @Test
    void audit_allowedForSuperadmin() {
        when(wiki.auditTrail(any(), anyInt())).thenReturn(Flux.just(
                new WikiAuditLog("a-1", "default", "admin", "wiki.page.create", "home", "v1", Instant.now())));
        asSuperadmin().get().uri("/bff/wiki/superadmin/audit").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.actions[0].action").isEqualTo("wiki.page.create");
    }

    @Test
    void audit_forbiddenForAdmin_manageIsSuperadminOnly() {
        asAdmin().get().uri("/bff/wiki/superadmin/audit").exchange().expectStatus().isForbidden();
    }
}
