package net.hugmesh.wikibff.config;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.ReactiveAuthenticationManagerResolver;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authorization.ExceptionTranslationWebFilter;
import org.springframework.security.web.server.authorization.HttpStatusServerAccessDeniedHandler;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;
import net.hugmesh.commons.security.DomainRegistry;
import net.hugmesh.wikibff.security.WikiStepUpAccessDeniedHandler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * STRUCTURAL pins for the step-up access-denied wiring (hugmesh-wiki-bff-api#35) — two claims
 * {@link WikiStepUpAccessDeniedHandler} makes that a behavioural test alone could miss.
 *
 * <p><b>1. BOTH chains.</b> {@link SecurityConfig} builds {@code devFilterChain}
 * ({@code @Profile({"dev","test"})}) and {@code filterChain} ({@code @Profile("!dev & !test")})
 * and installs the handler on each. This asserts, one assertion per chain with no profile
 * machinery, that each built chain dispatches its 403s through the handler.
 * {@code StepUpSignalSliceTest} closes the same gap behaviourally.
 *
 * <p><b>2. "Every other refusal stays byte-identical".</b> Installing an explicit
 * {@code accessDeniedHandler} makes {@code ServerHttpSecurity} discard its default handlers
 * wholesale. For a chain that configures {@code oauth2ResourceServer} those defaults resolve to
 * {@code BearerTokenServerAccessDeniedHandler}, not to {@link HttpStatusServerAccessDeniedHandler}.
 * The reference handler is extracted from a stock chain at RUNTIME and the whole response
 * (status, every header, body) compared, so a Spring upgrade that resolves its defaults
 * differently fails here instead of changing the wire.
 */
class StepUpAccessDeniedWiringTest {

    /**
     * Two trusted issuers so {@code DomainRegistry.isMultiIssuer()} holds and
     * {@code resourceServer} takes the per-issuer resolver branch. That is deliberate
     * PLUMBING, not the subject — but the single-issuer {@code o.jwt(...)} branch resolves a
     * {@code ReactiveJwtDecoder} from the ApplicationContext, and these chains are built from
     * a bare {@code ServerHttpSecurity.http()} with no context at all.
     */
    private static final String ISSUERS_CSV =
            "http://127.0.0.1:1=brandx,http://127.0.0.1:2=brandy";

    /** Never invoked: no request is driven through a chain here, only handlers. */
    private static final ReactiveAuthenticationManagerResolver<ServerWebExchange> RESOLVER =
            exchange -> Mono.empty();

    private static SecurityConfig config(boolean mfaEnabled) {
        return new SecurityConfig(new DomainRegistry("http://127.0.0.1:1", ISSUERS_CSV),
                SecuritySliceSupport.provider(RESOLVER), mfaEnabled, 600000L);
    }

    /** The {@code accessDeniedHandler} the built chain will actually dispatch a 403 through. */
    private static ServerAccessDeniedHandler accessDeniedHandlerOf(SecurityWebFilterChain chain) {
        List<WebFilter> filters = chain.getWebFilters().collectList().block();
        assertThat(filters).as("built chain filters").isNotNull().isNotEmpty();
        ExceptionTranslationWebFilter translation = filters.stream()
                .filter(ExceptionTranslationWebFilter.class::isInstance)
                .map(ExceptionTranslationWebFilter.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no ExceptionTranslationWebFilter in the chain — a 403 cannot reach any "
                                + "accessDeniedHandler at all"));
        return (ServerAccessDeniedHandler)
                ReflectionTestUtils.getField(translation, "accessDeniedHandler");
    }

    /**
     * The handler Spring Security itself installs for a chain shaped like ours MINUS the
     * {@code exceptionHandling} call — i.e. exactly what {@code origin/main} dispatched every
     * denial through. Resolved at runtime rather than named, so an upstream change in how the
     * defaults are chosen is picked up here instead of being assumed away.
     */
    private static ServerAccessDeniedHandler springsOwnHandlerForThisChainShape() {
        return accessDeniedHandlerOf(ServerHttpSecurity.http()
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(ex -> ex.anyExchange().authenticated())
                .oauth2ResourceServer(o -> o.authenticationManagerResolver(RESOLVER))
                .build());
    }

    @Test
    void bothProfileChains_dispatchTheirDenialsThroughTheStepUpHandler() {
        SecurityConfig config = config(false);
        WikiStepUpAccessDeniedHandler handler = config.wikiStepUpAccessDeniedHandler();

        assertThat(accessDeniedHandlerOf(
                config.devFilterChain(ServerHttpSecurity.http(), handler)))
                .as("devFilterChain (@Profile dev,test) accessDeniedHandler")
                .isSameAs(handler);
        assertThat(accessDeniedHandlerOf(
                config.filterChain(ServerHttpSecurity.http(), handler)))
                .as("filterChain (@Profile !dev & !test) accessDeniedHandler")
                .isSameAs(handler);
    }

    /**
     * THE EQUIVALENCE. Drive the same {@link AccessDeniedException} through Spring's own
     * handler and through ours (no step-up attribute on the exchange, so the pass-through
     * branch) and compare the whole response: status, every header, and the body. This is the
     * assertion that would have caught the dropped {@code WWW-Authenticate: Bearer} and the
     * invented {@code "Access Denied"} body.
     */
    @Test
    void nonStepUpDenial_isByteIdenticalToWhatSpringWouldHaveSent() {
        assertResponsesMatch(springsOwnHandlerForThisChainShape(),
                config(false).wikiStepUpAccessDeniedHandler(), "/bff/wiki/admin/pages");
    }

    /**
     * The same equivalence with the MFA flag ARMED, on a path that is NOT step-up wrapped —
     * the other half of the pass-through: arming the flag must not move the wire for anything
     * outside the two privileged path groups.
     */
    @Test
    void armedFlag_stillPassesThroughUnchanged_onANonPrivilegedPath() {
        assertResponsesMatch(springsOwnHandlerForThisChainShape(),
                config(true).wikiStepUpAccessDeniedHandler(), "/bff/wiki/pages");
    }

    /**
     * The measurement behind the class javadoc, asserted rather than only described: the bare
     * status handler is NOT an equivalent delegate on this chain shape. If this ever starts
     * passing, the "which delegate" reasoning above has changed and the handler's choice must
     * be re-derived rather than inherited.
     */
    @Test
    void theBareStatusHandler_wouldNOTHaveBeenAnEquivalentDelegate() {
        ServerAccessDeniedHandler reference = springsOwnHandlerForThisChainShape();
        ServerAccessDeniedHandler bare =
                new HttpStatusServerAccessDeniedHandler(org.springframework.http.HttpStatus.FORBIDDEN);

        MockServerHttpResponse fromReference =
                refuse(reference, "/bff/wiki/admin/pages", authenticated());
        MockServerHttpResponse fromBare = refuse(bare, "/bff/wiki/admin/pages", authenticated());

        assertThat(fromReference.getStatusCode())
                .as("both are still a 403")
                .isEqualTo(fromBare.getStatusCode());
        assertThat(fromReference.getHeaders().getFirst("WWW-Authenticate"))
                .as("Spring's own default challenges with the RFC 6750 insufficient_scope "
                        + "shape on a real (authenticated) 403")
                .startsWith("Bearer error=\"insufficient_scope\"");
        assertThat(headersOf(fromBare).getFirst("WWW-Authenticate"))
                .as("the bare status handler sends no challenge at all — this is why the "
                        + "delegate must be the bearer one")
                .isNull();
        assertThat(bodyOf(fromBare))
                .as("and it invents a body where Spring's own sends none")
                .isEqualTo("Access Denied");
    }

    /** Run both handlers over an identical request and compare the responses in full. */
    private static void assertResponsesMatch(ServerAccessDeniedHandler reference,
                                             ServerAccessDeniedHandler actual,
                                             String path) {
        assertResponsesMatch(reference, actual, path, null);
        assertResponsesMatch(reference, actual, path, authenticated());
    }

    private static void assertResponsesMatch(ServerAccessDeniedHandler reference,
                                             ServerAccessDeniedHandler actual,
                                             String path,
                                             Authentication authentication) {
        MockServerHttpResponse expected = refuse(reference, path, authentication);
        MockServerHttpResponse got = refuse(actual, path, authentication);

        HttpStatusCode expectedStatus = expected.getStatusCode();
        assertThat(expectedStatus).as("reference handler must actually have refused").isNotNull();
        assertThat(got.getStatusCode())
                .as("status of a non-step-up denial on %s", path).isEqualTo(expectedStatus);
        assertThat(headersOf(got)).as("headers of a non-step-up denial on %s", path)
                .isEqualTo(headersOf(expected));
        assertThat(bodyOf(got)).as("body of a non-step-up denial on %s", path)
                .isEqualTo(bodyOf(expected));
    }

    /**
     * Drive one refusal. {@code authentication} is the EXCHANGE PRINCIPAL and it changes the
     * answer: {@code BearerTokenServerAccessDeniedHandler} sends a bare
     * {@code WWW-Authenticate: Bearer} when the exchange has no OAuth2 principal, and the full
     * RFC 6750 {@code insufficient_scope} challenge when it has one. Production 403s are the
     * SECOND kind — a denial only reaches an access-denied handler when the caller is
     * authenticated, and this service authenticates with bearer JWTs — so a delegate measured
     * only against the first shape could look equivalent while silently rewriting every real
     * refusal. Both are compared.
     */
    private static MockServerHttpResponse refuse(ServerAccessDeniedHandler handler, String path,
                                                 Authentication authentication) {
        MockServerWebExchange base =
                MockServerWebExchange.from(MockServerHttpRequest.get(path));
        ServerWebExchange exchange = authentication == null
                ? base
                : base.mutate().principal(Mono.just(authentication)).build();
        handler.handle(exchange, new AccessDeniedException("Access Denied")).block();
        return base.getResponse();
    }

    /**
     * An authenticated but under-privileged bearer principal — the shape every 403 that
     * reaches an access-denied handler in this service actually has. A
     * {@link JwtAuthenticationToken} specifically, because
     * {@code BearerTokenServerAccessDeniedHandler} keys its RFC 6750 challenge off
     * {@code AbstractOAuth2TokenAuthenticationToken}: a generic test token would take the
     * bare-{@code Bearer} branch and the measurement would not be of production at all.
     */
    private static Authentication authenticated() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").claim("sub", "u").build();
        return new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList("ROLE_USER"));
    }

    private static HttpHeaders headersOf(MockServerHttpResponse response) {
        return response.getHeaders();
    }

    private static String bodyOf(MockServerHttpResponse response) {
        String body = response.getBodyAsString().defaultIfEmpty("").block();
        return body == null ? "" : body;
    }
}
