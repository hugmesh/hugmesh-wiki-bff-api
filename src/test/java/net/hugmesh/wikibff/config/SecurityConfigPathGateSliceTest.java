package net.hugmesh.wikibff.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.ReactiveAuthenticationManagerResolver;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import net.hugmesh.commons.config.TenancyResolvers;
import net.hugmesh.commons.config.TenancyResourceServerConfig;
import net.hugmesh.commons.security.DomainRegistry;
import net.hugmesh.wikibff.testsupport.TestIssuer;

import java.util.List;

/**
 * Coarse PATH-GATE slice for the wiki BFF — the {@link SecurityConfig}
 * {@code authorizeExchange} policy of the PROD chain ({@code filterChain},
 * {@code @Profile("!dev & !test")}), end-to-end through the real filter chain.
 * REPLICATED from the keystone {@code SecurityConfigPathGateSliceTest} in
 * hugmesh-gateway; only this repo's path/role table changes (the slice plumbing
 * is identical).
 *
 * <p>This repo's surface (most-specific prefix first):
 * <ul>
 *   <li><b>{@code /bff/wiki/superadmin/**}</b> — {@code hasRole('SUPERADMIN')};</li>
 *   <li><b>{@code /bff/wiki/admin/**}</b> — {@code hasAnyRole('ADMIN','SUPERADMIN')};</li>
 *   <li><b>{@code /bff/wiki/**}</b> — {@code .authenticated()} read floor;</li>
 *   <li><b>SPEC_PATHS</b> ({@code /v3/api-docs/**}, {@code /swagger-ui/**}) —
 *       {@code hasAnyRole('ADMIN','SUPERADMIN')} in PROD (this chain), so the OpenAPI
 *       contract is never publicly reachable; USER -> 403, anonymous -> 401;</li>
 *   <li><b>{@code /actuator/health}, {@code /actuator/info}</b> — {@code permitAll}
 *       (k8s/Docker probes need no token).</li>
 * </ul>
 *
 * <p>Asserts the ALLOW/DENY matrix:
 * <ul>
 *   <li><b>USER-SITUATIONAL DENY</b> — USER/MOD get 403 on admin + superadmin + spec
 *       surfaces; USER/MOD/ADMIN get 403 on the superadmin surface (least-privilege
 *       cascade, no ADMIN&gt;MOD edge);</li>
 *   <li><b>USER-SITUATIONAL ALLOW</b> — ADMIN reaches the admin + spec surfaces;
 *       SUPERADMIN reaches everything (role hierarchy edge);</li>
 *   <li><b>ANONYMOUS ALLOW</b> — actuator health/info probes;</li>
 *   <li><b>ANONYMOUS DENY</b> — the {@code .authenticated()} read floor + the prod
 *       spec gate 401 anonymous.</li>
 * </ul>
 *
 * <p>The path policy is auth-decoder-agnostic, so this slice drives tokens through the
 * multi-issuer resolver path (the only no-network decoder seam — backed by an
 * in-process {@link TestIssuer}, two trusted issuers so {@code isMultiIssuer()} holds);
 * the {@code authorizeExchange} rules under test are identical on the single-issuer path.
 */
class SecurityConfigPathGateSliceTest extends SecuritySliceSupport {

    private TestIssuer issuer;
    private TestIssuer issuer2;   // a second trusted issuer so isMultiIssuer() holds
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        issuer = new TestIssuer();
        issuer2 = new TestIssuer();
        String issuersCsv = issuer.issuerUri() + "=brandx," + issuer2.issuerUri() + "=brandy";
        DomainRegistry registry = new DomainRegistry(issuer.issuerUri(), issuersCsv);
        TenancyResourceServerConfig tenancy = new TenancyResourceServerConfig(registry, "", "", false, 600_000L);
        ReactiveAuthenticationManagerResolver<ServerWebExchange> resolver =
                TenancyResolvers.tenantResolver(tenancy);
        client = sliceClient(registry, provider(resolver), new ProbeController());
    }

    @AfterEach
    void tearDown() {
        issuer.close();
        issuer2.close();
    }

    private WebTestClient.RequestHeadersSpec<?> as(List<String> roles, String path) {
        String token = issuer.mint("p-" + String.join("-", roles), roles);
        return client.get().uri(path).header("Authorization", "Bearer " + token);
    }

    // ── SUPERADMIN surface — SUPERADMIN only ──────────────────────────────────

    @Test
    void user_forbiddenOn_superadmin() {
        as(List.of("user"), "/bff/wiki/superadmin/settings").exchange().expectStatus().isForbidden();
    }

    @Test
    void mod_forbiddenOn_superadmin() {
        as(List.of("mod"), "/bff/wiki/superadmin/settings").exchange().expectStatus().isForbidden();
    }

    @Test
    void admin_forbiddenOn_superadmin() {
        // The least-privilege cascade: a plain ADMIN does NOT satisfy the SUPERADMIN-only
        // MANAGE-ALL surface (no ADMIN -> SUPERADMIN edge). 403, never 200.
        as(List.of("admin"), "/bff/wiki/superadmin/settings").exchange().expectStatus().isForbidden();
    }

    @Test
    void superadmin_allowedOn_superadmin() {
        as(List.of("superadmin"), "/bff/wiki/superadmin/settings").exchange().expectStatus().isOk();
    }

    // ── ADMIN surface — ADMIN or SUPERADMIN ───────────────────────────────────

    @Test
    void user_forbiddenOn_admin() {
        as(List.of("user"), "/bff/wiki/admin/publish").exchange().expectStatus().isForbidden();
    }

    @Test
    void mod_forbiddenOn_admin() {
        // A MOD is NOT an admin (no ADMIN > MOD edge); the write/publish console is
        // ADMIN|SUPERADMIN only.
        as(List.of("mod"), "/bff/wiki/admin/publish").exchange().expectStatus().isForbidden();
    }

    @Test
    void admin_allowedOn_admin() {
        as(List.of("admin"), "/bff/wiki/admin/publish").exchange().expectStatus().isOk();
    }

    @Test
    void superadmin_allowedOn_admin() {
        // SUPERADMIN reaches the ADMIN surface via the role-hierarchy edge.
        as(List.of("superadmin"), "/bff/wiki/admin/publish").exchange().expectStatus().isOk();
    }

    // ── read floor — any authenticated principal ──────────────────────────────

    @Test
    void user_allowedOn_readFloor() {
        // /bff/wiki/** is .authenticated() — a plain authenticated USER reaches it. The
        // exact WIKI_READ composite (incl. MOD-AND-WIKI) is re-checked at the method
        // level (@PreAuthorize), not here.
        as(List.of("user"), "/bff/wiki/page/welcome").exchange().expectStatus().isOk();
    }

    // ── PROD spec gate — ROLE_ADMIN|SUPERADMIN, the contract is never public ───

    @Test
    void user_forbiddenOn_apiDocs() {
        // In PROD the OpenAPI spec is NOT public — a plain USER is 403'd.
        as(List.of("user"), "/v3/api-docs").exchange().expectStatus().isForbidden();
    }

    @Test
    void mod_forbiddenOn_swaggerUi() {
        as(List.of("mod"), "/swagger-ui/index.html").exchange().expectStatus().isForbidden();
    }

    @Test
    void admin_allowedOn_apiDocs() {
        // ADMIN reaches the spec surface (it is a role allow, not a blanket deny).
        as(List.of("admin"), "/v3/api-docs").exchange().expectStatus().isOk();
    }

    @Test
    void superadmin_allowedOn_apiDocs() {
        as(List.of("superadmin"), "/v3/api-docs").exchange().expectStatus().isOk();
    }

    @Test
    void anonymous_deniedOn_apiDocs() {
        // PROD spec gate: anonymous gets 401 (no auth) — the contract is never publicly
        // reachable in production. This is the gap the prod profile closes vs dev/test.
        client.get().uri("/v3/api-docs").exchange().expectStatus().isUnauthorized();
    }

    // ── ANONYMOUS ALLOW — actuator probes ─────────────────────────────────────

    @Test
    void anonymous_allowedOn_actuatorProbes() {
        // Probes are permitAll so k8s/Docker healthchecks need no token.
        for (String path : List.of("/actuator/health", "/actuator/info")) {
            client.get().uri(path).exchange()
                    .expectStatus().value(s -> assertNot401Nor403(path, s));
        }
    }

    // ── ANONYMOUS DENY — the authenticated() floor ────────────────────────────

    @Test
    void anonymous_deniedOn_readFloor() {
        // /bff/wiki/** is .authenticated() — an anonymous caller 401s at the floor.
        client.get().uri("/bff/wiki/page/welcome").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void anonymous_deniedOn_arbitraryGatedPath() {
        // anyExchange().authenticated() — an ungoverned, non-public path 401s anonymous.
        client.get().uri("/api/reports/q3").exchange().expectStatus().isUnauthorized();
    }

    /** A permitAll request must not be 401/403. The probe controller maps every path,
     *  so a permitAll path resolves to 200; we only guard against the security 401/403.
     *  The WebTestClient status-value consumer hands us the RAW status code (int). */
    private static void assertNot401Nor403(String path, int status) {
        if (status == 401 || status == 403) {
            throw new AssertionError("expected anonymous ALLOW on " + path + " but got " + status);
        }
    }

    /** Maps every path the slice exercises so an ALLOWED request resolves to 200
     *  (instead of a 404 that could mask a missing security decision). */
    @RestController
    static class ProbeController {
        @RequestMapping({
                "/bff/wiki/superadmin/settings",
                "/bff/wiki/admin/publish",
                "/bff/wiki/page/welcome",
                "/v3/api-docs", "/swagger-ui/index.html",
                "/actuator/health", "/actuator/info",
                "/api/reports/q3",
        })
        Mono<String> ok() {
            return Mono.just("ok");
        }
    }
}
