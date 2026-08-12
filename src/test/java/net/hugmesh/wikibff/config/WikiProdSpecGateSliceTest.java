package net.hugmesh.wikibff.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.ReactiveAuthenticationManagerResolver;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ServerWebExchange;
import net.hugmesh.commons.config.TenancyResolvers;
import net.hugmesh.commons.config.TenancyResourceServerConfig;
import net.hugmesh.commons.security.DomainRegistry;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WAVE-2 PROD SPEC-GATE slice — drives the PRODUCTION {@link SecurityConfig#filterChain}
 * ({@code @Profile("!dev & !test")}) end-to-end against the {@code SPEC_PATHS} surface,
 * with REAL RSA-signed tokens validated through the multi-issuer resolver (an in-process
 * JWKS/OIDC server on {@code 127.0.0.1} — no network, no live Casdoor).
 *
 * <p>The mission's prod spec-gating ask: the OpenAPI spec + Swagger UI behave DIFFERENTLY
 * prod vs dev. The dev/test chain {@code devFilterChain} {@code permitAll}s the spec so
 * {@code gen:fetch} can curl it without a token; the PROD chain gates it to
 * {@code ROLE_ADMIN|SUPERADMIN} so the contract is NEVER publicly reachable. This slice
 * asserts the PROD gate explicitly:
 * <ul>
 *   <li><b>anonymous ⇒ 401</b> on {@code /v3/api-docs} + {@code /swagger-ui/**} — the
 *       headline difference from dev (where they are public);</li>
 *   <li><b>USER / MOD ⇒ 403</b> — authenticated but unprivileged callers cannot read the
 *       contract;</li>
 *   <li><b>ADMIN / SUPERADMIN ⇒ reach it</b> (role allow, via the role-hierarchy edge for
 *       SUPERADMIN);</li>
 *   <li><b>actuator health/info ⇒ permitAll</b> (anonymous probes still pass — the spec
 *       gate did not over-broaden the public surface).</li>
 * </ul>
 *
 * <p>This is self-contained (its own in-file {@code TestIssuer}-style fixture + prod-chain
 * builder) so it neither collides with nor depends on the in-flight multi-issuer
 * security-slice PR (#7); it mirrors that PR's style. The {@code SPEC_PATHS} policy is
 * auth-decoder-agnostic, so driving tokens through the multi-issuer path (the only
 * no-network decoder seam) exercises the SAME {@code authorizeExchange} rules the
 * single-issuer path would.
 */
class WikiProdSpecGateSliceTest {

    private Issuer brandX;
    private Issuer brandY;   // second trusted issuer so isMultiIssuer() holds
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        brandX = new Issuer();
        brandY = new Issuer();
        String issuersCsv = brandX.issuerUri() + "=brandx," + brandY.issuerUri() + "=brandy";
        DomainRegistry registry = new DomainRegistry(brandX.issuerUri(), issuersCsv);
        TenancyResourceServerConfig tenancy = new TenancyResourceServerConfig(registry, "", "", false, 600_000L);
        ReactiveAuthenticationManagerResolver<ServerWebExchange> resolver =
                TenancyResolvers.tenantResolver(tenancy);

        // Build the PROD chain (the spec gate is ADMIN|SUPERADMIN here) and install it as a
        // web filter so the resource-server auth + authorizeExchange run as in production.
        SecurityConfig config = new SecurityConfig(registry, provider(resolver));
        SecurityWebFilterChain chain = config.filterChain(ServerHttpSecurity.http());
        client = WebTestClient.bindToController(new ProbeController())
                .webFilter(new WebFilterChainProxy(chain))
                .build();
    }

    @AfterEach
    void tearDown() {
        brandX.close();
        brandY.close();
    }

    private WebTestClient.RequestHeadersSpec<?> as(String role, String path) {
        String token = brandX.mint("p-" + role, List.of(role));
        return client.get().uri(path).header("Authorization", "Bearer " + token);
    }

    // ── the headline prod gate: anonymous can NOT reach the spec ──────────────

    @Test
    void anonymous_unauthorizedOn_apiDocs() {
        client.get().uri("/v3/api-docs").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void anonymous_unauthorizedOn_swaggerUi() {
        client.get().uri("/swagger-ui/index.html").exchange().expectStatus().isUnauthorized();
    }

    // ── authenticated-but-unprivileged is 403 (contract is not for everyone) ──

    @Test
    void user_forbiddenOn_apiDocs() {
        as("user", "/v3/api-docs").exchange().expectStatus().isForbidden();
    }

    @Test
    void mod_forbiddenOn_apiDocs() {
        as("mod", "/v3/api-docs").exchange().expectStatus().isForbidden();
    }

    @Test
    void user_forbiddenOn_swaggerUi() {
        as("user", "/swagger-ui/index.html").exchange().expectStatus().isForbidden();
    }

    // ── ADMIN / SUPERADMIN reach the spec ─────────────────────────────────────

    @Test
    void admin_allowedOn_apiDocs() {
        as("admin", "/v3/api-docs").exchange().expectStatus().isOk();
    }

    @Test
    void superadmin_allowedOn_apiDocs() {
        // via the ROLE_SUPERADMIN > ROLE_ADMIN hierarchy edge
        as("superadmin", "/v3/api-docs").exchange().expectStatus().isOk();
    }

    @Test
    void admin_allowedOn_swaggerUi() {
        as("admin", "/swagger-ui/index.html").exchange().expectStatus().isOk();
    }

    // ── the gate did not over-broaden: actuator probes stay public ────────────

    @Test
    void anonymous_allowedOn_actuatorProbes() {
        for (String path : List.of("/actuator/health", "/actuator/info")) {
            client.get().uri(path).exchange()
                    .expectStatus().value(s -> {
                        if (s == 401 || s == 403) {
                            throw new AssertionError("expected anonymous ALLOW on " + path + " but got " + s);
                        }
                    });
        }
    }

    /** Maps the spec + actuator paths so an ALLOWED request resolves to 200 (not a 404
     *  that could mask a missing security decision). */
    @org.springframework.web.bind.annotation.RestController
    static class ProbeController {
        @org.springframework.web.bind.annotation.RequestMapping({
                "/v3/api-docs", "/swagger-ui/index.html",
                "/actuator/health", "/actuator/info",
        })
        reactor.core.publisher.Mono<String> ok() {
            return reactor.core.publisher.Mono.just("ok");
        }
    }

    /** ObjectProvider yielding the given value from getIfAvailable() (what SecurityConfig calls). */
    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
        };
    }

    /**
     * In-process trusted-issuer fixture (same shape as the multi-issuer-slice
     * {@code TestIssuer}, inlined + renamed here to avoid colliding with the in-flight
     * PR #7 helper). Serves OIDC discovery + JWKS on {@code 127.0.0.1} and mints
     * RSA-signed Casdoor-shaped tokens whose {@code iss} is this server.
     */
    static final class Issuer implements AutoCloseable {
        private final RSAKey rsaKey;
        private final HttpServer server;
        private final String issuerUri;

        Issuer() {
            try {
                this.rsaKey = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
                RSAKey publicKey = rsaKey.toPublicJWK();
                this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                this.issuerUri = "http://127.0.0.1:" + server.getAddress().getPort();
                server.createContext("/.well-known/openid-configuration", ex -> respond(ex,
                        "{\"issuer\":\"" + issuerUri + "\",\"jwks_uri\":\"" + issuerUri
                                + "/.well-known/jwks.json\"}"));
                server.createContext("/.well-known/jwks.json", ex ->
                        respond(ex, new JWKSet(publicKey).toString()));
                server.setExecutor(null);
                server.start();
            } catch (JOSEException | IOException e) {
                throw new IllegalStateException("failed to start test Issuer", e);
            }
        }

        String issuerUri() { return issuerUri; }

        String mint(String subject, List<String> roleNames) {
            try {
                List<Map<String, String>> roles = roleNames.stream().map(r -> Map.of("name", r)).toList();
                Instant now = Instant.now();
                SignedJWT jwt = new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(),
                        new JWTClaimsSet.Builder().issuer(issuerUri).subject(subject)
                                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300)))
                                .claim("roles", roles).build());
                jwt.sign(new RSASSASigner(rsaKey));
                return jwt.serialize();
            } catch (JOSEException e) {
                throw new IllegalStateException("failed to mint test JWT", e);
            }
        }

        private static void respond(HttpExchange ex, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        }

        @Override public void close() { server.stop(0); }
    }
}
