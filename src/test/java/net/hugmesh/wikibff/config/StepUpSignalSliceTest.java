package net.hugmesh.wikibff.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.ReactiveAuthenticationManagerResolver;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import net.hugmesh.commons.config.TenancyResolvers;
import net.hugmesh.commons.config.TenancyResourceServerConfig;
import net.hugmesh.commons.security.DomainRegistry;
import net.hugmesh.commons.security.Roles;
import net.hugmesh.wikibff.testsupport.TestIssuer;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * hugmesh-wiki-bff-api#35 — a step-up refusal must be RECOGNISABLE on the wire, and ONLY a
 * step-up refusal. Drives real RS256 tokens through the production filter chains (the same
 * plumbing as {@link MfaStepUpPathGateSliceTest}) and asserts the response the browser would
 * actually see: {@code X-Step-Up-Required: X-Wiki-StepUp} plus the legacy
 * {@code requires step-up} phrase when the role leg GRANTED and {@code MFA_SATISFIED} denied;
 * and NO signal on a role 403, a 401, a granted call, or with {@code app.mfa.enabled=false}.
 *
 * <p>Every signal assertion runs against BOTH profile chains ({@code filterChain} and
 * {@code devFilterChain}), because {@code SecurityConfig} installs the handler on each and
 * deleting it from one would otherwise leave this suite green.
 */
class StepUpSignalSliceTest extends SecuritySliceSupport {

    private static final long FRESHNESS_WINDOW_MILLIS = 600_000L;
    private static final String SIGNAL = "X-Step-Up-Required";
    /** The platform detector rule every UI applies to the header value. */
    private static final Pattern PLATFORM_DETECTOR = Pattern.compile("^X-[A-Za-z0-9-]{1,60}-StepUp$");

    private static final String SUPERADMIN_PATH = "/bff/wiki/superadmin/audit";
    private static final String ADMIN_PATH = "/bff/wiki/admin/pages";
    private static final String READ_FLOOR_PATH = "/bff/wiki/pages";

    @RestController
    static class WikiStub {
        @RequestMapping({SUPERADMIN_PATH, ADMIN_PATH, READ_FLOOR_PATH})
        Mono<String> ok() { return Mono.just("ok"); }
    }

    private TestIssuer issuer;
    private TestIssuer issuer2;
    private WebTestClient mfaOff;
    private WebTestClient mfaOnProd;
    private WebTestClient mfaOnDev;

    @BeforeEach
    void setUp() {
        issuer = new TestIssuer();
        issuer2 = new TestIssuer();
        DomainRegistry registry = new DomainRegistry(issuer.issuerUri(),
                issuer.issuerUri() + "=brandx," + issuer2.issuerUri() + "=brandy");
        mfaOff = sliceClient(registry, provider(resolver(registry, false)), new WikiStub());
        mfaOnProd = mfaSliceClient(registry, provider(resolver(registry, true)), new WikiStub());
        mfaOnDev = mfaDevSliceClient(registry, provider(resolver(registry, true)), new WikiStub());
    }

    @AfterEach
    void tearDown() {
        issuer.close();
        issuer2.close();
    }

    private static ReactiveAuthenticationManagerResolver<ServerWebExchange> resolver(
            DomainRegistry registry, boolean mfaEnabled) {
        return TenancyResolvers.tenantResolver(new TenancyResourceServerConfig(
                registry, "", "", mfaEnabled, FRESHNESS_WINDOW_MILLIS));
    }

    private String token(List<String> roles, Map<String, Object> extraClaims) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("roles", roles.stream().map(r -> Map.of("name", r)).toList());
        claims.put("name", "u");
        claims.putAll(extraClaims);
        return issuer.mintWithClaims("u", claims);
    }

    private String plainToken(String... roles) {
        return token(List.of(roles), Map.of());
    }

    private String steppedUpToken(long ageMillis, String... roles) {
        return token(List.of(roles), Map.of(Roles.MFA_SATISFIED_AT_CLAIM,
                String.valueOf(System.currentTimeMillis() - ageMillis)));
    }

    private static WebTestClient.ResponseSpec get(WebTestClient client, String path, String bearer) {
        return client.get().uri(path).headers(h -> h.setBearerAuth(bearer)).exchange();
    }

    private static void assertStepUpRefusal(WebTestClient.ResponseSpec spec, String verb) {
        EntityExchangeResult<byte[]> result = spec.expectBody().returnResult();
        HttpHeaders headers = result.getResponseHeaders();
        assertThat(result.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(headers.get(SIGNAL)).containsExactly("X-Wiki-StepUp");
        // The header value must satisfy the platform detector rule, whatever its spelling.
        assertThat(headers.getFirst(SIGNAL)).matches(PLATFORM_DETECTOR);
        assertThat(headers.getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        byte[] body = result.getResponseBody();
        assertThat(new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8))
                .isEqualTo("{\"error\":\"" + verb
                        + " requires step-up re-authentication (present X-Wiki-StepUp)\"}");
        // Re-presenting the same bearer token cannot satisfy a step-up, so no challenge.
        assertThat(headers.getFirst("WWW-Authenticate")).isNull();
    }

    private static void assertNoSignal(WebTestClient.ResponseSpec spec) {
        spec.expectHeader().doesNotExist(SIGNAL);
    }

    /** Run one assertion against both profile chains. */
    private void onBothChains(Function<WebTestClient, WebTestClient.ResponseSpec> request,
                              Consumer<WebTestClient.ResponseSpec> assertion) {
        assertion.accept(request.apply(mfaOnProd));
        assertion.accept(request.apply(mfaOnDev));
    }

    // --- the signal: role granted, MFA leg denied ---------------------------------------

    @Test
    void superadminPath_withoutStamp_carriesTheSignal() {
        String t = plainToken("superadmin");
        onBothChains(c -> get(c, SUPERADMIN_PATH, t),
                spec -> assertStepUpRefusal(spec, "wiki management"));
    }

    @Test
    void superadminPath_staleStamp_carriesTheSignal() {
        String t = steppedUpToken(FRESHNESS_WINDOW_MILLIS + 60_000L, "superadmin");
        onBothChains(c -> get(c, SUPERADMIN_PATH, t),
                spec -> assertStepUpRefusal(spec, "wiki management"));
    }

    @Test
    void adminPath_withoutStamp_carriesTheSignal() {
        String t = plainToken("admin");
        onBothChains(c -> get(c, ADMIN_PATH, t),
                spec -> assertStepUpRefusal(spec, "wiki administration"));
    }

    @Test
    void adminPath_staleStamp_carriesTheSignal() {
        String t = steppedUpToken(FRESHNESS_WINDOW_MILLIS + 60_000L, "admin");
        onBothChains(c -> get(c, ADMIN_PATH, t),
                spec -> assertStepUpRefusal(spec, "wiki administration"));
    }

    @Test
    void adminPath_futureStamp_carriesTheSignal() {
        String t = steppedUpToken(-60_000L, "admin");
        onBothChains(c -> get(c, ADMIN_PATH, t),
                spec -> assertStepUpRefusal(spec, "wiki administration"));
    }

    @Test
    void adminPath_superadminWithoutStamp_carriesTheSignal() {
        // SUPERADMIN reaches the admin group via the role hierarchy edge, so the role leg grants.
        String t = plainToken("superadmin");
        onBothChains(c -> get(c, ADMIN_PATH, t),
                spec -> assertStepUpRefusal(spec, "wiki administration"));
    }

    @Test
    void signalOnEveryMethodOfAGatedPath() {
        String t = plainToken("admin");
        assertStepUpRefusal(mfaOnProd.post().uri(ADMIN_PATH).headers(h -> h.setBearerAuth(t)).exchange(),
                "wiki administration");
        assertStepUpRefusal(mfaOnProd.delete().uri(ADMIN_PATH).headers(h -> h.setBearerAuth(t)).exchange(),
                "wiki administration");
    }

    // --- no signal: the role leg denied --------------------------------------------------

    @Test
    void superadminPath_adminWithoutStamp_isAPlainRole403_noSignal() {
        // A plain ADMIN fails the role leg, so it must not be offered a challenge that can
        // never let it through. Spring's own RFC 6750 challenge is preserved, and the body
        // stays empty.
        String t = plainToken("admin");
        onBothChains(c -> get(c, SUPERADMIN_PATH, t), spec -> {
            assertNoSignal(spec);
            spec.expectStatus().isForbidden()
                    .expectHeader().valueMatches("WWW-Authenticate", "^Bearer error=\"insufficient_scope\".*")
                    .expectBody().isEmpty();
        });
    }

    @Test
    void superadminPath_adminWithFreshStamp_isAPlainRole403_noSignal() {
        String t = steppedUpToken(1_000L, "admin");
        onBothChains(c -> get(c, SUPERADMIN_PATH, t), spec -> {
            assertNoSignal(spec);
            spec.expectStatus().isForbidden().expectBody().isEmpty();
        });
    }

    @Test
    void adminPath_plainUser_isAPlainRole403_noSignal() {
        String t = plainToken("user");
        onBothChains(c -> get(c, ADMIN_PATH, t), spec -> {
            assertNoSignal(spec);
            spec.expectStatus().isForbidden()
                    .expectHeader().valueMatches("WWW-Authenticate", "^Bearer error=\"insufficient_scope\".*")
                    .expectBody().isEmpty();
        });
    }

    @Test
    void adminPath_forgedPermissionMarkerOnAPlainUser_noSignal() {
        String t = token(List.of("user"), Map.of("permissions", List.of(Roles.MFA_SATISFIED)));
        onBothChains(c -> get(c, ADMIN_PATH, t), spec -> {
            assertNoSignal(spec);
            spec.expectStatus().isForbidden();
        });
    }

    // --- no signal: 401, granted, ungated ------------------------------------------------

    @Test
    void unauthenticated_isA401_noSignal() {
        for (String path : List.of(SUPERADMIN_PATH, ADMIN_PATH)) {
            onBothChains(c -> c.get().uri(path).exchange(), spec -> {
                assertNoSignal(spec);
                spec.expectStatus().isUnauthorized()
                        .expectHeader().valueEquals("WWW-Authenticate", "Bearer");
            });
        }
    }

    @Test
    void invalidToken_isA401_noSignal() {
        onBothChains(c -> get(c, ADMIN_PATH, "not-a-jwt"), spec -> {
            assertNoSignal(spec);
            spec.expectStatus().isUnauthorized();
        });
    }

    @Test
    void freshStamp_isAllowed_noSignal() {
        String t = steppedUpToken(1_000L, "admin");
        onBothChains(c -> get(c, ADMIN_PATH, t), spec -> {
            assertNoSignal(spec);
            spec.expectStatus().isOk();
        });
    }

    @Test
    void readFloor_withoutStamp_isAllowed_noSignal() {
        String t = plainToken("user");
        onBothChains(c -> get(c, READ_FLOOR_PATH, t), spec -> {
            assertNoSignal(spec);
            spec.expectStatus().isOk();
        });
    }

    // --- no signal: the flag is off ------------------------------------------------------

    @Test
    void flagOff_wouldBeStepUpCallers_areAllowed_noSignal() {
        assertNoSignal(get(mfaOff, SUPERADMIN_PATH, plainToken("superadmin")));
        assertNoSignal(get(mfaOff, ADMIN_PATH, plainToken("admin")));
        get(mfaOff, ADMIN_PATH, plainToken("admin")).expectStatus().isOk();
    }

    @Test
    void flagOff_roleDenial_isUnchanged_noSignal() {
        WebTestClient.ResponseSpec spec = get(mfaOff, SUPERADMIN_PATH, plainToken("admin"));
        assertNoSignal(spec);
        spec.expectStatus().isForbidden()
                .expectHeader().valueMatches("WWW-Authenticate", "^Bearer error=\"insufficient_scope\".*")
                .expectBody().isEmpty();
        get(mfaOff, ADMIN_PATH, plainToken("user")).expectStatus().isForbidden()
                .expectHeader().doesNotExist(SIGNAL);
    }

    @Test
    void flagOff_unauthenticated_isA401_noSignal() {
        assertNoSignal(mfaOff.get().uri(ADMIN_PATH).exchange());
    }
}
