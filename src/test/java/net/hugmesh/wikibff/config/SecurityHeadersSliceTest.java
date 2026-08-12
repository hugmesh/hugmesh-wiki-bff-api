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
 * SECURITY-HEADERS slice — drives the PRODUCTION {@link SecurityConfig#filterChain}
 * end-to-end through a real {@link WebFilterChainProxy} and asserts the hardened
 * reactive HTTP response headers (OWASP secure-headers / NIST 800-228 / ASVS V14.4)
 * are emitted on a response, while the deny-by-default 401 floor is unchanged.
 *
 * <p>This BFF is a pure-JSON resource server behind the hugmesh-gateway, so per
 * the header policy it re-asserts only the API-defensive minimum (a no-document CSP)
 * and inherits HSTS / Permissions-Policy / COOP/CORP / the UI-shell CSP from the
 * gateway front door. The test therefore asserts:
 * <ul>
 *   <li>the BFF API CSP {@code default-src 'none'; frame-ancestors 'none'} is present;</li>
 *   <li>Spring's reactive SECURE DEFAULTS are kept (X-Content-Type-Options: nosniff,
 *       X-Frame-Options: DENY, Cache-Control: no-store, Referrer-Policy: no-referrer)
 *       — proving the {@code .headers(...)} block did not disable them;</li>
 *   <li>HSTS is NOT emitted by the BFF (it lives only at the TLS front door);</li>
 *   <li>headers are present on BOTH a permitted (200) AND a fail-closed (401) response;</li>
 *   <li>an anonymous request to a protected wiki path still 401s — the additive header
 *       block changed no authz decision.</li>
 * </ul>
 *
 * <p>The chain is built through the multi-issuer resolver (an in-process JWKS/OIDC
 * server on {@code 127.0.0.1}, no network) — the same no-network decoder seam
 * {@code WikiProdSpecGateSliceTest} uses — because the single-issuer {@code o.jwt(...)}
 * path needs a configured jwtDecoder bean that a bare slice has none of. Header
 * emission and the anonymous 401 are decoder-agnostic.
 */
class SecurityHeadersSliceTest {

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

    // ── the hardened headers are present on a PERMITTED (200) response ─────────

    @Test
    void permittedResponse_carriesBffApiCsp() {
        client.get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Content-Security-Policy",
                        "default-src 'none'; frame-ancestors 'none'");
    }

    @Test
    void permittedResponse_keepsSpringSecureDefaults() {
        client.get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                // nosniff + X-Frame-Options DENY are Spring reactive defaults — kept.
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().valueEquals("X-Frame-Options", "DENY")
                // Cache-Control no-store + Referrer-Policy no-referrer defaults — kept.
                .expectHeader().valueMatches("Cache-Control", ".*no-store.*")
                .expectHeader().valueEquals("Referrer-Policy", "no-referrer");
    }

    @Test
    void bffDoesNotEmitHsts() {
        // HSTS belongs ONLY at the TLS front door — a plaintext intra-mesh BFF must NOT emit it.
        client.get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist("Strict-Transport-Security");
    }

    // ── the hardened headers are ALSO present on a fail-closed (401) response ──

    @Test
    void unauthenticatedResponse_stillCarriesHardenedHeaders() {
        client.get().uri("/bff/wiki/pages").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("Content-Security-Policy",
                        "default-src 'none'; frame-ancestors 'none'")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().valueEquals("X-Frame-Options", "DENY");
    }

    // ── the additive header block changed no authz decision ────────────────────

    @Test
    void anonymous_protectedWikiPath_stillUnauthorized() {
        client.get().uri("/bff/wiki/pages").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void anonymous_unmappedPath_stillUnauthorized() {
        // anyExchange().authenticated() deny-by-default floor is intact.
        client.get().uri("/totally/unmapped").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void anonymous_actuatorProbe_stillPermitted() {
        // The header block did not over-broaden or narrow the public probe surface.
        client.get().uri("/actuator/info").exchange().expectStatus().isOk();
    }

    /** Maps the probed paths so an ALLOWED request resolves to 200 (a 404 could mask a
     *  missing security decision). Protected wiki paths are NOT mapped here — they must
     *  401 at the filter BEFORE routing, which is exactly what the 401 tests assert. */
    @org.springframework.web.bind.annotation.RestController
    static class ProbeController {
        @org.springframework.web.bind.annotation.RequestMapping({
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
     * In-process trusted-issuer fixture (same shape as {@code WikiProdSpecGateSliceTest}'s
     * {@code Issuer}). Serves OIDC discovery + JWKS on {@code 127.0.0.1} and mints
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

        @SuppressWarnings("unused")
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
