package net.hugmesh.wikibff.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.ReactiveAuthenticationManagerResolver;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ServerWebExchange;
import net.hugmesh.commons.security.DomainRegistry;

/**
 * Shared scaffolding for the wiki-BFF SECURITY-SLICE tests — builds a
 * {@link WebTestClient} that drives a real controller THROUGH the production
 * {@link SecurityConfig#filterChain} (the actual {@code authorizeExchange} path
 * policy + the OAuth2 resource-server wiring), with NO full Spring Boot context.
 * COPIED from the adversarially-verified-safe hugmesh-gateway
 * {@code SecuritySliceSupport} (package + constructor adapted to this repo).
 *
 * <p>This is the load-bearing half of the copyable multi-issuer pattern (see
 * {@code docs/testing/MULTI-ISSUER-SLICE.md} in hugmesh-gateway): a resource-server
 * repo replicates the slice by (1) constructing its own {@code SecurityConfig.filterChain}
 * the same way here, (2) supplying issuer-specific tokens via
 * {@link net.hugmesh.wikibff.testsupport.TestIssuer}, and (3) asserting
 * authority emission / 401 fail-closed against {@code WebTestClient}.
 *
 * <p><b>This repo's wiring.</b> {@code SecurityConfig(DomainRegistry, ObjectProvider)}
 * is the constructor; {@code filterChain(ServerHttpSecurity)} is the PROD chain
 * ({@code @Profile("!dev & !test")}) where the OpenAPI spec paths are gated to
 * {@code ROLE_ADMIN|SUPERADMIN} — exactly the surface the path-gate slice exercises.
 * (The dev/test chain {@code devFilterChain} permits the spec paths; the slice builds
 * the prod chain on purpose so the production spec gate is under test.)
 */
abstract class SecuritySliceSupport {

    /**
     * Build a {@link WebTestClient} bound to {@code controllers}, routed THROUGH the
     * real {@link SecurityConfig#filterChain} built from a fresh
     * {@link ServerHttpSecurity}. The chain is installed as a {@link WebFilterChainProxy}
     * web filter so the resource-server authentication + the {@code authorizeExchange}
     * policy run exactly as in production.
     *
     * @param registry        the issuer-&gt;domain registry (single- or multi-issuer)
     * @param tenantResolver  the OPTIONAL per-issuer resolver provider — pass
     *                        {@link #provider(Object)} with the real resolver to take
     *                        the multi-issuer swap, or {@link #absent()} to stay on
     *                        the single-issuer {@code o.jwt(...)} path
     * @param controllers     the @RestController instances under test
     */
    static WebTestClient sliceClient(
            DomainRegistry registry,
            ObjectProvider<ReactiveAuthenticationManagerResolver<ServerWebExchange>> tenantResolver,
            Object... controllers) {

        SecurityConfig config = new SecurityConfig(registry, tenantResolver);
        SecurityWebFilterChain chain = config.filterChain(ServerHttpSecurity.http());
        WebFilterChainProxy securityFilter = new WebFilterChainProxy(chain);

        return WebTestClient.bindToController(controllers)
                .webFilter(securityFilter)
                .build();
    }

    /**
     * Like {@link #sliceClient} but with the D8 MFA flag ON — {@code SecurityConfig} is
     * built with {@code app.mfa.enabled=true} and a 10-minute freshness window, so the
     * privileged-path {@code stepUp} wrappers on {@code /bff/wiki/superadmin/**} and
     * {@code /bff/wiki/admin/**} are LIVE. Same PROD chain as {@link #sliceClient}
     * (the {@code commonRoutes} the step-up wrappers live in is shared by both profiles).
     *
     * <p>The caller must ALSO enable MFA on whatever produces the authorities (the
     * {@code TenancyResourceServerConfig} the multi-issuer slice drives), or no token
     * can ever carry the {@code MFA_SATISFIED} marker the gate demands — the flag is one
     * platform-wide switch and the slice has to mirror that.
     *
     * @param registry        the issuer-&gt;domain registry (single- or multi-issuer)
     * @param tenantResolver  the per-issuer resolver provider, built with MFA ON
     * @param controllers     the @RestController instances under test
     */
    static WebTestClient mfaSliceClient(
            DomainRegistry registry,
            ObjectProvider<ReactiveAuthenticationManagerResolver<ServerWebExchange>> tenantResolver,
            Object... controllers) {

        SecurityConfig config = new SecurityConfig(registry, tenantResolver, true, 600_000L);
        SecurityWebFilterChain chain = config.filterChain(ServerHttpSecurity.http());
        WebFilterChainProxy securityFilter = new WebFilterChainProxy(chain);

        return WebTestClient.bindToController(controllers)
                .webFilter(securityFilter)
                .build();
    }

    /** An {@link ObjectProvider} that yields the given value from
     *  {@code getIfAvailable()} (what {@code SecurityConfig} calls). */
    @SuppressWarnings("unchecked")
    static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
        };
    }

    /** An empty {@link ObjectProvider} ({@code getIfAvailable()} returns {@code null})
     *  — the single-issuer path, where no tenant resolver was contributed. */
    static <T> ObjectProvider<T> absent() {
        return new ObjectProvider<>() {
            @Override public T getObject() { throw new IllegalStateException("no value"); }
            @Override public T getObject(Object... args) { throw new IllegalStateException("no value"); }
            @Override public T getIfAvailable() { return null; }
            @Override public T getIfUnique() { return null; }
        };
    }
}
