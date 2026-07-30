package systems.hugmesh.wikibff.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.ReactiveAuthenticationManagerResolver;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import systems.hugmesh.commons.security.DomainRegistry;
import systems.hugmesh.commons.testsupport.PostgresTestSupport;
import systems.hugmesh.wikibff.testsupport.TestIssuer;
import systems.hugmesh.wikibff.testsupport.WrongKidForger;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multi-issuer 401 fail-closed coverage proven through the FULL Spring Boot context
 * (not a hand-wired slice). This is the complementary half of
 * {@link MultiIssuerSecuritySliceTest}: that test directly instantiates
 * {@code SecurityConfig} + {@code TenancyResourceServerConfig} and wires the resolver
 * by hand; this one boots the REAL application context and asserts the resolver is
 * actually contributed and swapped in END-TO-END by Spring:
 *
 * <ul>
 *   <li>{@link TenancyResourceServerConfig}'s
 *       {@code @ConditionalOnExpression("'${app.tenancy.issuers:}'.contains('=')")}
 *       actually FIRES when {@code app.tenancy.issuers} carries issuer=domain pairs
 *       (set via {@link DynamicPropertySource} below), contributing the
 *       {@code tenantAuthManagerResolver} bean;</li>
 *   <li>{@link SecurityConfig}'s {@code ObjectProvider} injection picks that bean up
 *       and — because the {@link DomainRegistry} the context builds from the SAME
 *       property is genuinely {@link DomainRegistry#isMultiIssuer()} — takes the
 *       {@code o.authenticationManagerResolver(resolver)} swap rather than the
 *       single-issuer {@code o.jwt(...)} path.</li>
 * </ul>
 *
 * <p>A regression that broke the conditional, the provider wiring, or the
 * {@code isMultiIssuer()} gate would silently fall back to the single configured
 * issuer-uri decoder — and a hand-wired slice could NOT catch that, because it never
 * exercises Spring's own bean assembly. The D6 (cookie/opaque-session) and D8 (MFA
 * challenge stamping) changes ride exactly this boot-wired fail-closed boundary, so it
 * is asserted against the real context here.
 *
 * <p>Two {@link TestIssuer}s back the two trusted brands (in-process JWKS + OIDC
 * discovery on {@code 127.0.0.1}); a third, unlisted {@code TestIssuer} is the
 * FOREIGN/rogue issuer. NO network, NO live Casdoor — fully offline + deterministic.
 * The per-issuer decoders are built LAZILY on first request inside
 * {@code TenancyResourceServerConfig#tenantAuthManagerResolver}, so booting the
 * context never contacts an issuer; only the actual request to a trusted brand does,
 * and that hits the loopback fixture.
 *
 * <p>The three required keystone assertions, proven through the booted filter chain:
 * <ol>
 *   <li><b>(a)</b> a token from a VALID configured issuer is ACCEPTED (200) and carries
 *       its issuer-derived {@code DOMAIN_<x>} authority;</li>
 *   <li><b>(b)</b> a token from a FOREIGN/unconfigured issuer is REJECTED with 401 —
 *       fail-closed, never silently accepted nor treated as anonymous-allowed;</li>
 *   <li><b>(c)</b> a tampered-signature token AND a wrong-kid token (a token whose
 *       {@code iss} is a trusted brand but signed by a DIFFERENT key than that brand
 *       publishes) both 401.</li>
 * </ol>
 *
 * <p>Categories per the comprehensive test standard: (a) is USER-SITUATIONAL; (b) and
 * the wrong-key/wrong-kid cases in (c) are CROSS-USER DENY (the tenant-isolation /
 * forgery boundary) asserted at the integration tier.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
// Deliberately DEFAULT (prod) profile — not the `test` profile — so the
// @Profile("!dev & !test") production filterChain (the real resource-server wiring) is
// exercised. The DB is a REAL Postgres container wired by {@link PostgresTestSupport}:
// its @DynamicPropertySource overrides the prod application.yml's spring.r2dbc.url /
// spring.liquibase.url at the container (highest-precedence dynamic properties), so the
// production R2DBC + Liquibase auto-config runs against the container exactly as it
// would in prod — no in-mem H2 dual-handle (which stopped sharing a store under Boot 4).
@Import(MultiIssuer401FailClosedBootTest.ProbeConfig.class)
class MultiIssuer401FailClosedBootTest extends PostgresTestSupport {

    // Shared across the whole class (issuer URIs must be known BEFORE the context boots,
    // for @DynamicPropertySource) — closed in @AfterAll.
    private static final TestIssuer BRAND_X = new TestIssuer();
    private static final TestIssuer BRAND_Y = new TestIssuer();
    private static final TestIssuer ROGUE = new TestIssuer();   // NOT in app.tenancy.issuers

    /**
     * Drive the REAL multi-issuer config: two trusted brand issuers (so the registry is
     * genuinely multi-issuer and the '=' conditional fires) wired into the property the
     * production beans read. The back-compat single issuer-uri is also pointed at
     * brand-X so the single-issuer fallback, were it (wrongly) taken, would still
     * resolve a real issuer — making the multi-issuer swap the ONLY thing that lets
     * brand-Y in and the ONLY thing that keeps the rogue out.
     */
    @DynamicPropertySource
    static void tenancyIssuers(DynamicPropertyRegistry registry) {
        registry.add("app.tenancy.issuers",
                () -> BRAND_X.issuerUri() + "=brandx," + BRAND_Y.issuerUri() + "=brandy");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", BRAND_X::issuerUri);
    }

    @AfterAll
    static void stopIssuers() {
        BRAND_X.close();
        BRAND_Y.close();
        ROGUE.close();
    }

    @Autowired WebTestClient client;
    @Autowired ApplicationContext ctx;
    @Autowired DomainRegistry domainRegistry;

    // ── boot-wiring guards: the conditional fired + the swap was taken ────────────

    @Test
    void boot_contributesTenantResolver_andRegistryIsMultiIssuer() {
        // The @ConditionalOnExpression('=' guard) fired and contributed the bean...
        assertThat(ctx.getBeanNamesForType(ReactiveAuthenticationManagerResolver.class))
                .as("TenancyResourceServerConfig must contribute the per-issuer resolver bean "
                        + "when app.tenancy.issuers carries issuer=domain pairs")
                .isNotEmpty();
        // ...AND the registry the context built from the same property is genuinely
        // multi-issuer, which is the gate SecurityConfig#resourceServer requires before
        // it swaps onto the resolver. Both must hold for the multi-issuer path to be live.
        assertThat(domainRegistry.isMultiIssuer())
                .as("two configured trusted issuers => multi-issuer mode (the swap is taken)")
                .isTrue();
        assertThat(domainRegistry.isTrusted(ROGUE.issuerUri()))
                .as("the rogue issuer is NOT configured, so it is untrusted")
                .isFalse();
    }

    // ── (a) VALID configured issuer accepted + DOMAIN_<x> emitted ─────────────────

    @Test
    void userSituational_brandXToken_accepted_andEmitsDomainX() {
        String token = BRAND_X.mint("user-x", List.of("admin"));
        client.get().uri("/bff/wiki/page")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body)
                        .as("a token from the configured brand-X issuer is ACCEPTED through "
                                + "the booted multi-issuer resolver and stamped DOMAIN_brandx")
                        .contains("DOMAIN_brandx")
                        .contains("ROLE_ADMIN")
                        .contains("ROLE_USER")
                        .doesNotContain("DOMAIN_brandy"));
    }

    @Test
    void multiUser_brandYToken_accepted_andEmitsDomainY_provingTheSwapNotSingleIssuerFallback() {
        // Brand-Y is NOT the back-compat single issuer-uri (that is brand-X). Brand-Y can
        // ONLY authenticate if the multi-issuer resolver was actually swapped in; if the
        // context had silently fallen back to the single-issuer decoder, a brand-Y token
        // would 401. So a 200 here PROVES the boot took the multi-issuer path.
        String token = BRAND_Y.mint("user-y", List.of("mod"));
        client.get().uri("/bff/wiki/page")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body)
                        .contains("DOMAIN_brandy")
                        .contains("ROLE_MOD")
                        .doesNotContain("DOMAIN_brandx"));
    }

    // ── (b) FOREIGN/unconfigured issuer -> 401 fail-closed ────────────────────────

    @Test
    void crossUser_foreignIssuerToken_is401_failClosed_neverAnonymousAllowed() {
        // A perfectly well-formed, correctly self-signed JWT from an issuer NOT in
        // app.tenancy.issuers. The per-issuer resolver returns Mono.empty() for it ->
        // 401. There is NO default manager and NO anonymous-allow fall-through on the
        // /bff/wiki/** .authenticated() floor.
        String token = ROGUE.mint("attacker", List.of("admin", "superadmin"));
        client.get().uri("/bff/wiki/page")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void crossUser_foreignIssuer_neverReachesController_noDomainEmitted() {
        // Defence beyond the status code: confirm the rogue token never authenticates as
        // ANY domain (no DOMAIN_* body leaks through) — it is rejected before the
        // controller, not silently downgraded to an anonymous-but-allowed principal.
        String token = ROGUE.mint("attacker", List.of("superadmin"));
        client.get().uri("/bff/wiki/page")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody(String.class)
                .value(body -> assertThat(body == null ? "" : body)
                        .as("a foreign-issuer token must never surface a DOMAIN_* authority")
                        .doesNotContain("DOMAIN_"));
    }

    @Test
    void crossUser_garbageBearer_is401() {
        client.get().uri("/bff/wiki/page")
                .header("Authorization", "Bearer not-a-jwt")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ── (c) tampered signature AND wrong-kid -> 401 ───────────────────────────────

    @Test
    void crossUser_tamperedSignature_is401() {
        // A brand-X token whose trailing signature bytes are corrupted fails brand-X's
        // RSA verification -> 401 (never silently accepted).
        String token = BRAND_X.mint("user-x", List.of("admin"));
        String tampered = token.substring(0, token.length() - 4) + "AAAA";
        client.get().uri("/bff/wiki/page")
                .header("Authorization", "Bearer " + tampered)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void crossUser_wrongKidToken_is401_signedByForeignKeyButClaimingTrustedIssuer() {
        // The hardest forgery to catch: a token whose `iss` IS the trusted brand-X
        // issuer (so it resolves to brand-X's manager) but which was SIGNED by a
        // different RSA key, carrying that key's kid in the JWS header. brand-X's
        // manager fetches brand-X's JWKS and finds NO key matching the kid / the
        // signature does not verify -> 401. A fail-open bug that skipped signature
        // verification, or trusted the iss claim without verifying the key, would let
        // this through. The forged token even claims `superadmin`, so a pass-through
        // would be a full cross-key privilege forgery.
        String forged = WrongKidForger.mintForeignSignedAs(
                BRAND_X.issuerUri(), "forger", List.of("superadmin"));
        client.get().uri("/bff/wiki/page")
                .header("Authorization", "Bearer " + forged)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /**
     * Probe controller mapped at the {@code /bff/wiki/**} {@code .authenticated()} read
     * floor. Booted into the full context (NOT a hand-wired slice) so a request travels
     * the real production filter chain + the real (swapped-in) multi-issuer resolver
     * before reaching here. Echoes the authenticated authorities so (a) can assert the
     * issuer-derived {@code DOMAIN_<x>} was emitted, and (b) can assert a rejected token
     * never surfaces a domain.
     */
    @TestConfiguration
    static class ProbeConfig {
        @Bean
        ProbeController multiIssuerBootProbeController() {
            return new ProbeController();
        }
    }

    @RestController
    static class ProbeController {
        @GetMapping("/bff/wiki/page")
        Mono<String> whoami() {
            return ReactiveSecurityContextHolder.getContext()
                    .map(c -> c.getAuthentication().getAuthorities().stream()
                            .map(Object::toString)
                            .collect(Collectors.joining(",")))
                    .defaultIfEmpty("ANONYMOUS");
        }
    }
}
