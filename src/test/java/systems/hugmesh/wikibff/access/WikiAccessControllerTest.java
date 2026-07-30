package systems.hugmesh.wikibff.access;

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
import systems.hugmesh.wikibff.config.SecurityConfig;
import systems.hugmesh.wikibff.web.ApiExceptionHandler;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * The grant/timeout/ban overlay surface gating + contract (reactive @WebFluxTest):
 *
 * <ul>
 *   <li>grant/timeout/ban/restore (under /bff/wiki/admin/access/**) — ALLOWED for ADMIN +
 *       SUPERADMIN (WIKI_WRITE); FORBIDDEN for a MOD+WIKI reader (the admin prefix is ADMIN
 *       end-to-end) and a plain USER.</li>
 *   <li>list-all/history (under /bff/wiki/superadmin/access) — ALLOWED for SUPERADMIN only
 *       (WIKI_MANAGE); FORBIDDEN for a plain ADMIN.</li>
 *   <li>contract: grant returns the active read row; timeout validates ttlSeconds &gt; 0;
 *       restore maps an unknown accessType to 400.</li>
 * </ul>
 */
@WebFluxTest(controllers = WikiAccessController.class)
@Import({SecurityConfig.class, ApiExceptionHandler.class})
class WikiAccessControllerTest {

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

    @MockitoBean WikiAccessService access;
    @MockitoBean ReactiveJwtDecoder jwtDecoder;

    private WebTestClient asUser() {
        return web.mutateWith(mockJwt().authorities(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private WebTestClient asModWiki() {
        return web.mutateWith(mockJwt().authorities(
                new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_MOD"),
                new SimpleGrantedAuthority("ROLE_WIKI")));
    }

    private WebTestClient asAdmin() {
        return web.mutateWith(mockJwt().authorities(
                new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private WebTestClient asSuperadmin() {
        return web.mutateWith(mockJwt().authorities(
                new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_SUPERADMIN")));
    }

    private static WikiAccess active(String type, Instant exp) {
        return new WikiAccess("id-1", "default", "bob", type, WikiAccess.STATE_ACTIVE,
                "admin-x", "reason", Instant.now(), exp, null);
    }

    // ── grant/timeout/ban/restore (WIKI_WRITE) ─────────────────────────
    @Test
    void grant_allowedForAdmin_returnsActiveReadRow() {
        when(access.grant(eq("bob"), any(), any(), any())).thenReturn(Mono.just(active(WikiAccess.TYPE_READ, null)));
        asAdmin().post().uri("/bff/wiki/admin/access/grant").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"userSubject\":\"bob\",\"reason\":\"welcome\"}")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.accessType").isEqualTo("read")
                .jsonPath("$.state").isEqualTo("active");
    }

    @Test
    void ban_allowedForSuperadmin() {
        when(access.ban(eq("bob"), any(), any(), any())).thenReturn(Mono.just(active(WikiAccess.TYPE_BANNED, null)));
        asSuperadmin().post().uri("/bff/wiki/admin/access/ban").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"userSubject\":\"bob\"}")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.accessType").isEqualTo("banned");
    }

    @Test
    void timeout_allowedForAdmin() {
        when(access.timeout(eq("bob"), anyLong(), any(), any(), any()))
                .thenReturn(Mono.just(active(WikiAccess.TYPE_BANNED, Instant.now().plusSeconds(3600))));
        asAdmin().post().uri("/bff/wiki/admin/access/timeout").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"userSubject\":\"bob\",\"ttlSeconds\":3600}")
                .exchange().expectStatus().isOk();
    }

    @Test
    void timeout_400WhenTtlNotPositive() {
        asAdmin().post().uri("/bff/wiki/admin/access/timeout").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"userSubject\":\"bob\",\"ttlSeconds\":0}")
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void grant_400WhenUserSubjectMissing() {
        asAdmin().post().uri("/bff/wiki/admin/access/grant").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"reason\":\"x\"}")
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void restore_allowedForAdmin() {
        when(access.revoke(eq("bob"), eq(WikiAccess.TYPE_BANNED), any())).thenReturn(Mono.empty());
        asAdmin().delete().uri("/bff/wiki/admin/access/banned/bob")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.state").isEqualTo("revoked");
    }

    @Test
    void restore_400WhenAccessTypeUnknown() {
        asAdmin().delete().uri("/bff/wiki/admin/access/bogus/bob")
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void grant_forbiddenForModWiki_writersAreAdminsOnly() {
        asModWiki().post().uri("/bff/wiki/admin/access/grant").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"userSubject\":\"bob\"}")
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void grant_forbiddenForPlainUser() {
        asUser().post().uri("/bff/wiki/admin/access/grant").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"userSubject\":\"bob\"}")
                .exchange().expectStatus().isForbidden();
    }

    // ── manage-all (WIKI_MANAGE) ───────────────────────────────────────
    @Test
    void listActive_allowedForSuperadmin() {
        when(access.listActive(any())).thenReturn(Flux.just(active(WikiAccess.TYPE_READ, null)));
        asSuperadmin().get().uri("/bff/wiki/superadmin/access")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.access[0].userSubject").isEqualTo("bob");
    }

    @Test
    void listActive_forbiddenForAdmin_manageIsSuperadminOnly() {
        asAdmin().get().uri("/bff/wiki/superadmin/access")
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void history_allowedForSuperadmin() {
        when(access.historyFor(eq("bob"), any())).thenReturn(Flux.just(
                new WikiAccess("id-2", "default", "bob", WikiAccess.TYPE_BANNED, WikiAccess.STATE_REVOKED,
                        "admin-x", "r", Instant.now(), null, Instant.now())));
        asSuperadmin().get().uri("/bff/wiki/superadmin/access/bob/history")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.access[0].state").isEqualTo("revoked");
    }
}
