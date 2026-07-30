package systems.hugmesh.wikibff.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.ReactiveAuthenticationManagerResolver;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ServerWebExchange;
import systems.hugmesh.wikibff.access.WikiAccess;
import systems.hugmesh.wikibff.access.WikiAccessController;
import systems.hugmesh.wikibff.access.WikiAccessRepository;
import systems.hugmesh.wikibff.access.WikiAccessService;
import systems.hugmesh.commons.config.TenancyResolvers;
import systems.hugmesh.commons.config.TenancyResourceServerConfig;
import systems.hugmesh.commons.security.DomainRegistry;
import systems.hugmesh.wikibff.security.PrincipalDomain;
import systems.hugmesh.wikibff.testsupport.TestIssuer;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * RAW-API-DENY (HTTP layer) negative security suite for the {@code wiki_access} grant /
 * timeout / ban OVERLAY surface ({@link WikiAccessController}) — the GOLD-EXEMPLAR way:
 * driven through the REAL production {@link SecurityConfig#filterChain} (multi-issuer
 * ENABLED) with tokens minted by REAL in-process RS256 JWKS issuers
 * ({@link TestIssuer}), NOT synthetic {@code mockJwt()}. So the entire pipeline — issuer
 * discovery, RSA signature validation, the Casdoor authorities converter, the path gate
 * AND the method {@code @PreAuthorize} — runs exactly as in production, and a restricted
 * caller is rejected by the SAME machinery that ships.
 *
 * <p><b>Authority for the expected status</b> = this repo's {@link SecurityConfig}
 * {@code commonRoutes} path table ({@code /bff/wiki/admin/**} → ADMIN|SUPERADMIN;
 * {@code /bff/wiki/superadmin/**} → SUPERADMIN) PLUS the method {@code @PreAuthorize}
 * ({@code WIKI_WRITE} = ADMIN|SUPERADMIN on grant/timeout/ban; {@code WIKI_MANAGE} =
 * SUPERADMIN on the list/history surface).
 *
 * <p><b>Mutation-resistant (★).</b> Each negative wires the REAL {@link WikiAccessService}
 * over a MOCKED {@link WikiAccessRepository}; after asserting the deny status, it verifies
 * the repository write/read method was NEVER invoked — i.e. the action did NOT take
 * effect. A fail-open that returned 200 (or that reached the service while returning an
 * error) would trip the {@code verify(..., never())} and FAIL.
 *
 * <p>Negative cases (per the test standard — CROSS-USER DENY / least privilege):
 * <ul>
 *   <li>ROLE_USER cannot grant / timeout / ban / revoke wiki access (admin-gated → 403),
 *       and the repo never inserts/CASes;</li>
 *   <li>ROLE_USER + ROLE_MOD (no WIKI, no ADMIN) also 403 on the write seam;</li>
 *   <li>{@code /superadmin/access} (+ history) DENIED to ADMIN and to MOD (403), repo
 *       never queried;</li>
 *   <li>anonymous → 401 on every gated method;</li>
 *   <li>forged / untrusted-issuer token → 401 (fail-closed) even when it claims
 *       superadmin, and a tampered signature → 401.</li>
 * </ul>
 */
class WikiAccessRawApiDenySliceTest extends SecuritySliceSupport {

    private TestIssuer brandX;
    private TestIssuer rogue;   // a well-formed, correctly-signed issuer that is NOT trusted
    private WikiAccessRepository repo;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        brandX = new TestIssuer();
        rogue = new TestIssuer();

        // Multi-issuer registry: brand-X trusted (+ a second listed issuer so the registry
        // is genuinely multi-issuer and the per-issuer resolver swap is taken); rogue is NOT
        // listed -> its tokens 401 fail-closed.
        String issuersCsv = brandX.issuerUri() + "=brandx,https://id.brandy.example=brandy";
        DomainRegistry registry = new DomainRegistry(brandX.issuerUri(), issuersCsv);

        TenancyResourceServerConfig tenancy = new TenancyResourceServerConfig(registry, "", "", false, 600_000L);
        ReactiveAuthenticationManagerResolver<ServerWebExchange> resolver =
                TenancyResolvers.tenantResolver(tenancy);

        // REAL service over a MOCKED repo + the REAL PrincipalDomain on the multi-brand
        // registry — so a denied request can be proven to NEVER reach the repo.
        repo = mock(WikiAccessRepository.class);
        WikiAccessService service = new WikiAccessService(repo, new PrincipalDomain(registry));
        WikiAccessController controller = new WikiAccessController(service);

        client = sliceClient(registry, provider(resolver), controller);
    }

    @AfterEach
    void tearDown() {
        brandX.close();
        rogue.close();
    }

    /** A bearer header for a brand-X token carrying the given Casdoor roles. */
    private String bearerX(String subject, String... roles) {
        return "Bearer " + brandX.mint(subject, List.of(roles));
    }

    // ── ROLE_USER cannot grant / timeout / ban / revoke (WIKI_WRITE → 403) ─────

    @Test
    void user_cannotGrant_403_andRepoNeverInserts() {
        client.post().uri("/bff/wiki/admin/access/grant")
                .header("Authorization", bearerX("alice", "user"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"userSubject\":\"bob\",\"reason\":\"x\"}")
                .exchange().expectStatus().isForbidden();
        verify(repo, never()).insertActive(any(), any(), any(), any(), any(), any());
        verify(repo, never()).findActive(any(), any(), any());
    }

    @Test
    void user_cannotTimeout_403_andRepoNeverInserts() {
        client.post().uri("/bff/wiki/admin/access/timeout")
                .header("Authorization", bearerX("alice", "user"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"userSubject\":\"bob\",\"ttlSeconds\":600,\"reason\":\"x\"}")
                .exchange().expectStatus().isForbidden();
        verify(repo, never()).insertActive(any(), any(), any(), any(), any(), any());
    }

    @Test
    void user_cannotBan_403_andRepoNeverInserts() {
        client.post().uri("/bff/wiki/admin/access/ban")
                .header("Authorization", bearerX("alice", "user"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"userSubject\":\"bob\",\"reason\":\"spam\"}")
                .exchange().expectStatus().isForbidden();
        verify(repo, never()).insertActive(any(), any(), any(), any(), any(), any());
    }

    @Test
    void user_cannotRevoke_403_andRepoNeverCases() {
        client.delete().uri("/bff/wiki/admin/access/banned/bob")
                .header("Authorization", bearerX("alice", "user"))
                .exchange().expectStatus().isForbidden();
        verify(repo, never()).findActive(any(), any(), any());
        verify(repo, never()).revoke(any());
    }

    @Test
    void modWithoutWikiOrAdmin_cannotBan_403() {
        // A plain MOD (no ADMIN, no WIKI) is below the WIKI_WRITE (ADMIN|SUPERADMIN) gate.
        client.post().uri("/bff/wiki/admin/access/ban")
                .header("Authorization", bearerX("m", "mod"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"userSubject\":\"bob\",\"reason\":\"x\"}")
                .exchange().expectStatus().isForbidden();
        verify(repo, never()).insertActive(any(), any(), any(), any(), any(), any());
    }

    // ── /superadmin/access DENIED to ADMIN + MOD (WIKI_MANAGE → 403) ───────────

    @Test
    void admin_cannotListActive_superadminOnly_403_andRepoNeverQueried() {
        client.get().uri("/bff/wiki/superadmin/access")
                .header("Authorization", bearerX("admin-x", "admin"))
                .exchange().expectStatus().isForbidden();
        verify(repo, never()).listActive(any());
    }

    @Test
    void admin_cannotReadHistory_superadminOnly_403_andRepoNeverQueried() {
        client.get().uri("/bff/wiki/superadmin/access/bob/history")
                .header("Authorization", bearerX("admin-x", "admin"))
                .exchange().expectStatus().isForbidden();
        verify(repo, never()).historyFor(any(), any());
    }

    @Test
    void mod_cannotListActive_superadminOnly_403() {
        client.get().uri("/bff/wiki/superadmin/access")
                .header("Authorization", bearerX("mod-x", "mod"))
                .exchange().expectStatus().isForbidden();
        verify(repo, never()).listActive(any());
    }

    // ── anonymous → 401 on every gated method, repo untouched ──────────────────

    @Test
    void anonymous_grant_401() {
        client.post().uri("/bff/wiki/admin/access/grant")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"userSubject\":\"bob\"}")
                .exchange().expectStatus().isUnauthorized();
        verifyNoInteractions(repo);
    }

    @Test
    void anonymous_listActive_401() {
        client.get().uri("/bff/wiki/superadmin/access")
                .exchange().expectStatus().isUnauthorized();
        verifyNoInteractions(repo);
    }

    // ── forged / untrusted issuer → 401 fail-closed (even claiming superadmin) ─

    @Test
    void untrustedIssuerToken_claimingSuperadmin_grant_is401_failClosed() {
        // The rogue issuer is a perfectly-signed JWT, simply NOT in app.tenancy.issuers.
        // Even claiming superadmin it 401s before any authority is emitted — never reaches
        // the WIKI_WRITE method, never the repo.
        String token = rogue.mint("attacker", List.of("admin", "superadmin"));
        client.post().uri("/bff/wiki/admin/access/grant")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"userSubject\":\"bob\"}")
                .exchange().expectStatus().isUnauthorized();
        verifyNoInteractions(repo);
    }

    @Test
    void untrustedIssuerToken_claimingSuperadmin_listActive_is401_failClosed() {
        String token = rogue.mint("attacker", List.of("superadmin"));
        client.get().uri("/bff/wiki/superadmin/access")
                .header("Authorization", "Bearer " + token)
                .exchange().expectStatus().isUnauthorized();
        verifyNoInteractions(repo);
    }

    @Test
    void tamperedSignature_grant_is401() {
        // A brand-X token whose signature is corrupted fails RSA validation -> 401.
        String token = brandX.mint("admin-x", List.of("admin"));
        String tampered = token.substring(0, token.length() - 4) + "AAAA";
        client.post().uri("/bff/wiki/admin/access/grant")
                .header("Authorization", "Bearer " + tampered)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"userSubject\":\"bob\"}")
                .exchange().expectStatus().isUnauthorized();
        verifyNoInteractions(repo);
    }

    @Test
    void garbageBearer_grant_is401() {
        client.post().uri("/bff/wiki/admin/access/grant")
                .header("Authorization", "Bearer not-a-jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"userSubject\":\"bob\"}")
                .exchange().expectStatus().isUnauthorized();
        verifyNoInteractions(repo);
    }

    /** Sanity floor: a brand-X ADMIN's grant IS permitted (the negative tests are denying
     *  the right thing, not blanket-403). The repo's findActive is consulted (pre-check)
     *  and an insert lands — proving the 403s above are role-driven, not a broken chain. */
    @Test
    void admin_grant_isPermitted_reachesRepo() {
        org.mockito.Mockito.when(repo.findActive(any(), any(), any()))
                .thenReturn(reactor.core.publisher.Mono.empty());
        org.mockito.Mockito.when(repo.insertActive(any(), any(), any(), any(), any(), any()))
                .thenReturn(reactor.core.publisher.Mono.just(new WikiAccess(
                        "id-1", "brandx", "bob", WikiAccess.TYPE_READ, WikiAccess.STATE_ACTIVE,
                        "admin-x", "welcome", java.time.Instant.now(), null, null)));

        client.post().uri("/bff/wiki/admin/access/grant")
                .header("Authorization", bearerX("admin-x", "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"userSubject\":\"bob\",\"reason\":\"welcome\"}")
                .exchange().expectStatus().isOk();
        verify(repo).insertActive(any(), any(), any(), any(), any(), any());
    }
}
