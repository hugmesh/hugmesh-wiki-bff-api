package net.hugmesh.wikibff.wiki;

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
import net.hugmesh.wikibff.access.WikiAccessGuard;
import net.hugmesh.wikibff.config.SecurityConfig;
import net.hugmesh.commons.web.ApiException;
import net.hugmesh.wikibff.web.ApiExceptionHandler;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
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
    void deletePage_serviceNotFound_is404_notFoldedInto204() {
        // Issue #15: the service signals "matched nothing" as a 404; the controller must
        // surface it rather than answer 204 for every completion.
        when(wiki.delete(eq("home"), any()))
                .thenReturn(Mono.error(ApiException.notFound("wiki page not found: home")));
        asSuperadmin().delete().uri("/bff/wiki/admin/pages/home").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.error").isEqualTo("wiki page not found: home");
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

    // ── the audit response is a TYPED schema, not an open map (issue wiki-ui#2) ──

    @Test
    void audit_bodyDeserializesIntoTheTypedAuditList_everyFieldCarried() {
        // The endpoint used to return Map<String,Object>, which springdoc rendered as an
        // open `additionalProperties: {}` object — so every generated client saw
        // Record<string, unknown>. Binding the body to WikiDto.AuditList proves the
        // response really IS that named shape: if the controller reverted to the raw map,
        // Jackson would still bind (the JSON is identical) — so we also assert every
        // field round-trips, which a reshaped/renamed DTO would break.
        Instant when = Instant.parse("2026-08-20T10:15:30Z");
        when(wiki.auditTrail(any(), anyInt())).thenReturn(Flux.just(
                new WikiAuditLog("a-1", "brandx", "actor-1", "wiki.page.create", "home", "v1", when),
                new WikiAuditLog("a-2", "brandy", "actor-2", "wiki.page.update", "faq", null, when)));

        WikiDto.AuditList body = asSuperadmin().get().uri("/bff/wiki/superadmin/audit")
                .exchange()
                .expectStatus().isOk()
                .expectBody(WikiDto.AuditList.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body.actions()).hasSize(2);
        WikiDto.AuditEntry first = body.actions().get(0);
        assertThat(first).isEqualTo(
                new WikiDto.AuditEntry("a-1", "brandx", "actor-1", "wiki.page.create", "home", "v1", when));
        // detail is nullable in wiki_audit_log — the DTO must carry the null through,
        // not drop the row or substitute a placeholder.
        assertThat(body.actions().get(1).detail()).isNull();
        assertThat(body.actions().get(1).createdAt()).isEqualTo(when);
    }

    @Test
    void audit_wireShapeIsUnchangedByTheTypedDto_sameKeysSameNames() {
        // Typing the response must NOT be a breaking change: the key is still "actions"
        // and each row still carries the WikiAuditLog field names the existing UI reads.
        when(wiki.auditTrail(any(), anyInt())).thenReturn(Flux.just(
                new WikiAuditLog("a-1", "brandx", "actor-1", "wiki.page.create", "home", "v1",
                        Instant.parse("2026-08-20T10:15:30Z"))));

        asSuperadmin().get().uri("/bff/wiki/superadmin/audit")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.actions.length()").isEqualTo(1)
                .jsonPath("$.actions[0].id").isEqualTo("a-1")
                .jsonPath("$.actions[0].domain").isEqualTo("brandx")
                .jsonPath("$.actions[0].actorSubject").isEqualTo("actor-1")
                .jsonPath("$.actions[0].action").isEqualTo("wiki.page.create")
                .jsonPath("$.actions[0].target").isEqualTo("home")
                .jsonPath("$.actions[0].detail").isEqualTo("v1")
                .jsonPath("$.actions[0].createdAt").exists();
    }

    @Test
    void audit_emptyTrailIsAnEmptyActionsArray_notAMissingKey() {
        // List.copyOf(empty) must still serialize the key — a client that reads
        // `res.actions ?? []` would mask a missing key, so assert the array is present.
        when(wiki.auditTrail(any(), anyInt())).thenReturn(Flux.empty());

        asSuperadmin().get().uri("/bff/wiki/superadmin/audit")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.actions").isArray()
                .jsonPath("$.actions.length()").isEqualTo(0);
    }
}
