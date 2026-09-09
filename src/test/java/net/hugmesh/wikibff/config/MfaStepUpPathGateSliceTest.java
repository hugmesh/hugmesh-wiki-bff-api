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
import net.hugmesh.commons.security.Roles;
import net.hugmesh.wikibff.testsupport.TestIssuer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The D8 STEP-UP path gate — {@code SecurityConfig.stepUp} wrapping
 * {@code /bff/wiki/superadmin/**} (WIKI_MANAGE) and {@code /bff/wiki/admin/**}
 * (WIKI_WRITE). Same slice plumbing as {@link SecurityConfigPathGateSliceTest} (real
 * RS256 tokens from in-process {@link TestIssuer}s, the production filter chain, zero
 * network); the ONE difference is that this class also drives the chain with
 * {@code app.mfa.enabled=true} so BOTH branches of the flag are asserted against the
 * SAME policy code. PORTED from the landed hugmesh-forum-bff-api
 * {@code MfaStepUpPathGateSliceTest}; only the path/role table changes.
 *
 * <p><b>The two load-bearing properties.</b>
 * <ol>
 *   <li><b>Dark = no-op.</b> With the flag OFF the marker is NEVER derived, so an
 *       unconditional step-up check would 403 every admin and superadmin on the
 *       platform TODAY. The disabled-branch tests pin that today's callers are
 *       untouched — including the least-privilege cascade (ADMIN is still 403 on the
 *       superadmin surface).</li>
 *   <li><b>Flip = enforced.</b> With the flag ON the privileged paths demand the
 *       {@link Roles#MFA_SATISFIED} marker, which the converter derives ONLY from a
 *       FRESH {@code mfa_satisfied_at} claim — so enforcement arrives WITH the platform
 *       flag rather than needing a follow-up change here.</li>
 * </ol>
 *
 * <p>The marker is a RESERVED converter prefix, so the forged-{@code permissions} case is
 * asserted too: a caller cannot mint their own second factor.
 */
class MfaStepUpPathGateSliceTest extends SecuritySliceSupport {

    /** 10 minutes — the {@code app.mfa.freshness-window} default both slices are built with. */
    private static final long FRESHNESS_WINDOW_MILLIS = 600_000L;

    private static final String SUPERADMIN_PATH = "/bff/wiki/superadmin/audit";
    private static final String ADMIN_PATH = "/bff/wiki/admin/pages";
    private static final String READ_FLOOR_PATH = "/bff/wiki/pages";

    /** Maps the three surfaces (any HTTP method) so an ALLOWED request resolves to 200
     *  instead of a 404 that could mask a missing security decision. The bodies are
     *  never reached on 401/403. */
    @RestController
    static class WikiStub {
        @RequestMapping({SUPERADMIN_PATH, ADMIN_PATH, READ_FLOOR_PATH})
        Mono<String> ok() { return Mono.just("ok"); }
    }

    private TestIssuer issuer;
    private TestIssuer issuer2;     // a second trusted issuer so isMultiIssuer() holds
    private WebTestClient mfaOff;   // app.mfa.enabled=false — today's platform default
    private WebTestClient mfaOn;    // app.mfa.enabled=true  — the post-flip platform

    @BeforeEach
    void setUp() {
        issuer = new TestIssuer();
        issuer2 = new TestIssuer();
        // Two trusted issuers so DomainRegistry.isMultiIssuer() is true and SecurityConfig
        // takes the resolver path (the single-issuer o.jwt(...) path would need a Spring
        // property context to build a decoder). Identical to the coarse path-gate slice.
        DomainRegistry registry = new DomainRegistry(issuer.issuerUri(),
                issuer.issuerUri() + "=brandx," + issuer2.issuerUri() + "=brandy");
        // The MFA flag must be set on the resolver too — that is what derives the marker
        // — and on SecurityConfig, which enforces it. One platform flag, mirrored twice.
        mfaOff = sliceClient(registry, provider(resolver(registry, false)), new WikiStub());
        mfaOn = mfaSliceClient(registry, provider(resolver(registry, true)), new WikiStub());
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

    /** A token carrying the Casdoor {@code roles} claim and, optionally, extra claims. */
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

    /** A token whose {@code mfa_satisfied_at} stamp is {@code ageMillis} old (string, as
     *  Casdoor emits it). A NEGATIVE age is a stamp from the future. */
    private String steppedUpToken(long ageMillis, String... roles) {
        return token(List.of(roles), Map.of(Roles.MFA_SATISFIED_AT_CLAIM,
                String.valueOf(System.currentTimeMillis() - ageMillis)));
    }

    private static WebTestClient.ResponseSpec get(WebTestClient client, String path, String bearer) {
        return client.get().uri(path).headers(h -> h.setBearerAuth(bearer)).exchange();
    }

    // --- flag OFF: the wrapper is a NO-OP (today's behaviour, unchanged) ------

    @Test
    void mfaDisabled_superadminPath_withoutAnyStamp_stillAllowed() {
        // THE regression this repo cannot afford: with MFA dark the converter never emits
        // the marker, so a gate that checked it unconditionally would lock out every
        // superadmin on the platform today.
        get(mfaOff, SUPERADMIN_PATH, plainToken("superadmin")).expectStatus().isOk();
    }

    @Test
    void mfaDisabled_adminPath_withoutAnyStamp_stillAllowed() {
        get(mfaOff, ADMIN_PATH, plainToken("admin")).expectStatus().isOk();
    }

    @Test
    void mfaDisabled_adminPath_superadmin_stillAllowed() {
        get(mfaOff, ADMIN_PATH, plainToken("superadmin")).expectStatus().isOk();
    }

    @Test
    void mfaDisabled_superadminPath_admin_stillForbidden_403() {
        // The least-privilege cascade survives the wrapper byte-for-byte: a plain ADMIN
        // does NOT satisfy the SUPERADMIN-only surface (no ADMIN -> SUPERADMIN edge).
        get(mfaOff, SUPERADMIN_PATH, plainToken("admin")).expectStatus().isForbidden();
    }

    @Test
    void mfaDisabled_readFloor_unchanged() {
        get(mfaOff, READ_FLOOR_PATH, plainToken("user")).expectStatus().isOk();
    }

    // --- flag ON: the superadmin path demands a FRESH second factor ----------

    @Test
    void mfaEnabled_superadminPath_withoutStamp_forbidden_403() {
        get(mfaOn, SUPERADMIN_PATH, plainToken("superadmin")).expectStatus().isForbidden();
    }

    @Test
    void mfaEnabled_superadminPath_freshStamp_allowed() {
        get(mfaOn, SUPERADMIN_PATH, steppedUpToken(1_000L, "superadmin")).expectStatus().isOk();
    }

    @Test
    void mfaEnabled_superadminPath_staleStamp_forbidden_403() {
        // Just outside the 10-minute window -> Roles.isMfaSatisfied is false -> no marker.
        get(mfaOn, SUPERADMIN_PATH,
                steppedUpToken(FRESHNESS_WINDOW_MILLIS + 60_000L, "superadmin"))
                .expectStatus().isForbidden();
    }

    @Test
    void mfaEnabled_superadminPath_stepUpDoesNotReplaceTheRole() {
        // Step-up NARROWS, it never widens: a fully stepped-up plain ADMIN is still 403
        // on the SUPERADMIN-only surface.
        get(mfaOn, SUPERADMIN_PATH, steppedUpToken(1_000L, "admin")).expectStatus().isForbidden();
    }

    @Test
    void mfaEnabled_superadminPath_forgedPermissionMarker_forbidden_403() {
        // MFA_SATISFIED is a RESERVED converter prefix — a self-asserted permissions entry
        // is dropped before it reaches the gate, so a caller cannot mint their own factor.
        String forged = token(List.of("superadmin"),
                Map.of("permissions", List.of(Roles.MFA_SATISFIED)));
        get(mfaOn, SUPERADMIN_PATH, forged).expectStatus().isForbidden();
    }

    @Test
    void mfaEnabled_superadminPath_unauthenticated_stillUnauthorized_401() {
        // The base rule short-circuits, so an absent token is still 401 (not a step-up 403).
        mfaOn.get().uri(SUPERADMIN_PATH).exchange().expectStatus().isUnauthorized();
    }

    // --- flag ON: the admin path demands a FRESH second factor ---------------

    @Test
    void mfaEnabled_adminPath_withoutStamp_forbidden_403() {
        get(mfaOn, ADMIN_PATH, plainToken("admin")).expectStatus().isForbidden();
    }

    @Test
    void mfaEnabled_adminPath_freshStamp_allowed() {
        get(mfaOn, ADMIN_PATH, steppedUpToken(1_000L, "admin")).expectStatus().isOk();
    }

    @Test
    void mfaEnabled_adminPath_staleStamp_forbidden_403() {
        get(mfaOn, ADMIN_PATH, steppedUpToken(FRESHNESS_WINDOW_MILLIS + 60_000L, "admin"))
                .expectStatus().isForbidden();
    }

    @Test
    void mfaEnabled_adminPath_futureStamp_forbidden_403() {
        // A stamp from the FUTURE is rejected (clock-skew / forged-future defence) — it
        // would otherwise never go stale.
        get(mfaOn, ADMIN_PATH, steppedUpToken(-60_000L, "admin")).expectStatus().isForbidden();
    }

    @Test
    void mfaEnabled_adminPath_superadmin_freshStamp_allowed() {
        get(mfaOn, ADMIN_PATH, steppedUpToken(1_000L, "superadmin")).expectStatus().isOk();
    }

    @Test
    void mfaEnabled_adminPath_stepUpDoesNotReplaceTheRole() {
        // Step-up NARROWS, it never widens: a fully stepped-up NON-admin is still 403.
        get(mfaOn, ADMIN_PATH, steppedUpToken(1_000L, "wiki", "user")).expectStatus().isForbidden();
    }

    @Test
    void mfaEnabled_adminPath_forgedPermissionMarker_forbidden_403() {
        String forged = token(List.of("admin"),
                Map.of("permissions", List.of(Roles.MFA_SATISFIED)));
        get(mfaOn, ADMIN_PATH, forged).expectStatus().isForbidden();
    }

    @Test
    void mfaEnabled_adminPath_unauthenticated_stillUnauthorized_401() {
        mfaOn.get().uri(ADMIN_PATH).exchange().expectStatus().isUnauthorized();
    }

    // --- flag ON: the ordinary wiki read floor is NOT step-up gated ----------

    @Test
    void mfaEnabled_readFloor_isNotStepUpGated() {
        // Reading the wiki is not a privileged action — only the superadmin and admin
        // groups are step-up surfaces, so a plain user with NO stamp still passes.
        get(mfaOn, READ_FLOOR_PATH, plainToken("user")).expectStatus().isOk();
    }

    @Test
    void mfaEnabled_readFloor_unauthenticated_401() {
        mfaOn.get().uri(READ_FLOOR_PATH).exchange().expectStatus().isUnauthorized();
    }
}
