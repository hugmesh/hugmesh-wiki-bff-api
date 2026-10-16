package net.hugmesh.wikibff.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.authentication.ReactiveAuthenticationManagerResolver;
import org.springframework.security.authorization.AuthorityReactiveAuthorizationManager;
import org.springframework.security.authorization.ReactiveAuthorizationManager;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.core.GrantedAuthorityDefaults;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import net.hugmesh.commons.config.TenancyResourceServerConfig;
import net.hugmesh.commons.security.CasdoorAuthorities;
import net.hugmesh.commons.security.DomainRegistry;
import net.hugmesh.commons.security.Roles;
import net.hugmesh.wikibff.security.PrincipalDomain;
import net.hugmesh.wikibff.security.WikiStepUpAccessDeniedHandler;
import net.hugmesh.wikibff.security.WikiStepUpGate;

/**
 * Casdoor-aware OAuth2 resource-server security for the wiki BFF (reactive
 * WebFlux). {@code @EnableReactiveMethodSecurity} activates the {@code @PreAuthorize}
 * gates on the controllers. The JWT→authority mapping is delegated to the shared
 * {@link CasdoorAuthorities} converter — COPIED (logic) from the
 * adversarially-verified-safe hugmesh-gateway so the BFF and the gateway emit
 * BYTE-IDENTICAL authorities, including the reserved-prefix injection guard on
 * {@code permissions} and exactly one issuer-derived {@code DOMAIN_<x>} tenant
 * authority.
 *
 * <p><b>3-tier architecture context.</b> This BFF owns TIER 1 (the writable
 * {@code wiki_page} Postgres content store). kiwix-serve (TIER 3 READ) is a SEPARATE
 * backend reached on its own gateway path; Kiwix has NO native auth, so the gateway
 * IS its authorization. This filter chain is DEFENCE IN DEPTH on top of the gateway
 * route-access:
 * <ul>
 *   <li>{@code /bff/wiki/superadmin/**} -> {@code hasRole('SUPERADMIN')} — the
 *       MANAGE-ALL surface (settings + the ZIM-rebuild trigger, PR4). Declared FIRST so
 *       the most-specific prefix wins.</li>
 *   <li>{@code /bff/wiki/admin/**} -> {@code hasAnyRole('ADMIN','SUPERADMIN')} — the
 *       write/publish console seam. Admins reach it via the converter's ROLE_ADMIN;
 *       SUPERADMIN reaches it via the role hierarchy edge.</li>
 *   <li>{@code /bff/wiki/**} -> {@code .authenticated()} — the read floor. The
 *       ROLE_WIKI route-access floor (admin|superadmin|mod+ROLE_WIKI) is enforced at the
 *       gateway; the converter adds ROLE_USER to every principal so {@code .authenticated()}
 *       proves authentication. The exact WIKI_READ / WIKI_WRITE / WIKI_MANAGE
 *       composites (including the {@code MOD AND WIKI} AND-of-roles read gate, which
 *       {@code hasAnyRole} cannot express) live at the METHOD level via
 *       {@code @PreAuthorize}.</li>
 * </ul>
 *
 * <p><b>D8 MFA step-up (ships dark).</b> The two PRIVILEGED path groups —
 * {@code /bff/wiki/superadmin/**} (WIKI_MANAGE: the audit trail + the access-state
 * views) and {@code /bff/wiki/admin/**} (WIKI_WRITE: page create/update/publish/delete,
 * attachment upload/delete, access grant/timeout/ban/restore) — are wrapped in
 * {@link #stepUp}. While {@code app.mfa.enabled} is {@code false} (today's default,
 * org-wide) that wrapper is a NO-OP and the emitted policy is byte-identical to the
 * legacy one; the moment the platform flips the flag those two groups ALSO require the
 * {@link Roles#MFA_SATISFIED} marker the converter derives from a FRESH
 * {@code mfa_satisfied_at} claim. Wiring it HERE rather than at flip time means
 * enforcement arrives WITH the flag, not with a follow-up change to this repo
 * (hugmesh-forum-bff-api {@code SecurityConfig.stepUp} is the landed precedent). The
 * ordinary {@code /bff/wiki/**} read floor is deliberately NOT step-up gated — reading
 * the wiki is not a privileged action.
 *
 * <p><b>Domain-scope (multi-brand).</b> The wiki pages + attachments + audit trail are
 * per-domain. A scoped ADMIN of brand X sees ONLY brand X's pages; the service/repo
 * layers add a {@code domain = :domain} predicate derived SERVER-SIDE from the
 * validated issuer ({@link PrincipalDomain}), with SUPERADMIN unscoped. Author-tracking
 * (author_subject == JWT-subject) is recorded independently.
 *
 * <p><b>Multi-issuer is OPT-IN + backward-compatible.</b> When
 * {@code app.tenancy.issuers} carries per-brand issuer=domain pairs
 * ({@link TenancyResourceServerConfig} contributes a resolver AND the registry is
 * {@link DomainRegistry#isMultiIssuer()}), each trusted per-brand Casdoor validates
 * its own tokens (unknown {@code iss} → 401). Otherwise the resource server stays on
 * the existing single {@code o.jwt(...)} issuer-uri path — byte-for-byte unchanged.
 */
@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
// SecurityConfig depends on DomainRegistry (the converter stamps the issuer-derived
// DOMAIN_<x>); PrincipalDomain is the controllers'/service's single domain-resolution
// bean. Importing both here means every @WebFluxTest slice that imports SecurityConfig
// gets them too — the slice context loads without each test re-declaring them.
@Import({DomainRegistry.class, PrincipalDomain.class})
public class SecurityConfig {

    /** Issuer→domain source of truth. Injected so the converter can stamp the
     *  cryptographically-validated {@code iss} as a {@code DOMAIN_<x>} authority.
     *  Single-issuer clusters get a registry that maps the lone issuer (or no issuer
     *  at all) to {@code DOMAIN_default}, so behaviour is unchanged. */
    private final DomainRegistry domainRegistry;

    /** OPTIONAL per-brand multi-issuer resolver. Present (non-null provider value)
     *  ONLY when {@code app.tenancy.issuers} is configured, via
     *  {@link TenancyResourceServerConfig}. When absent the resource server stays on
     *  the single {@code o.jwt(...)} issuer-uri wiring — byte-for-byte the current
     *  single-issuer path. */
    private final ObjectProvider<ReactiveAuthenticationManagerResolver<ServerWebExchange>>
            tenantAuthManagerResolver;

    /** D8 MFA marker derivation — {@code app.mfa.enabled} (default {@code false},
     *  ships dark). When false the converter emits the byte-identical legacy authority
     *  set, {@code MFA_SATISFIED} is never produced, and {@link #stepUp} is a no-op.
     *  Read by BOTH the converter ({@link #casdoorAuthoritiesConverter}) and the path
     *  gate ({@link #stepUp}) so the same one flag derives AND enforces the marker. */
    private final boolean mfaEnabled;

    /** D8 MFA freshness window in millis — {@code app.mfa.freshness-window} (default
     *  600000 = 10m). Shared with the multi-issuer path so the marker derives
     *  identically on both decoder paths. */
    private final long mfaFreshnessWindowMillis;

    @Autowired
    public SecurityConfig(DomainRegistry domainRegistry,
                          ObjectProvider<ReactiveAuthenticationManagerResolver<ServerWebExchange>>
                                  tenantAuthManagerResolver,
                          @Value("${app.mfa.enabled:false}") boolean mfaEnabled,
                          @Value("${app.mfa.freshness-window:600000}") long mfaFreshnessWindowMillis) {
        this.domainRegistry = domainRegistry;
        this.tenantAuthManagerResolver = tenantAuthManagerResolver;
        this.mfaEnabled = mfaEnabled;
        this.mfaFreshnessWindowMillis = mfaFreshnessWindowMillis;
    }

    /**
     * Legacy convenience (MFA derivation OFF, default 10m window) — keeps the pre-D8
     * slice tests compiling and proves the dark default emits the byte-identical
     * legacy authority set. Production uses the {@code @Autowired} constructor above.
     *
     * @param domainRegistry            issuer-to-domain map; used by the JWT authorities
     *                                  converter to stamp the {@code DOMAIN_<x>} authority
     * @param tenantAuthManagerResolver provider of the per-brand
     *                                  {@link ReactiveAuthenticationManagerResolver};
     *                                  present only when {@code app.tenancy.issuers} is
     *                                  configured, absent in single-issuer clusters
     */
    public SecurityConfig(DomainRegistry domainRegistry,
                          ObjectProvider<ReactiveAuthenticationManagerResolver<ServerWebExchange>>
                                  tenantAuthManagerResolver) {
        this(domainRegistry, tenantAuthManagerResolver, false, 600_000L);
    }

    /**
     * SpringDoc OpenAPI spec + Swagger UI surface. {@code /v3/api-docs} is the raw
     * OpenAPI JSON the platform ng-openapi-gen typed clients (the paired
     * hugmesh-wiki-ui) + the docs pipeline fetch. Gated per profile (see the two
     * filter-chain beans): public under dev/test so {@code gen:fetch} can curl the
     * spec without a token, ROLE_ADMIN / SUPERADMIN everywhere else (prod) so the API
     * contract is never publicly reachable — matching the forum-bff / user-bff /
     * admin-bff template.
     */
    private static final String[] SPEC_PATHS = {
            "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/webjars/swagger-ui/**"
    };

    /**
     * Reactive HTTP response-header hardening for this BFF (OWASP secure-headers /
     * NIST 800-228 / ASVS V14.4). Applied to EVERY {@link SecurityWebFilterChain}.
     *
     * <p>This is a <b>pure-JSON resource server that always sits behind the
     * hugmesh-gateway front door</b> — it serves no HTML document and never
     * terminates public TLS. Per the header policy, the browser-policy set (HSTS,
     * Permissions-Policy, COOP/CORP, the full UI-shell CSP) is emitted ONCE at the
     * gateway so all BFFs inherit it and the per-brand configs can't drift. This
     * BFF therefore re-asserts only the <b>API-defensive minimum</b>:
     * <ul>
     *   <li><b>Content-Security-Policy: default-src 'none'; frame-ancestors 'none'</b>
     *       — a no-document API CSP (defence-in-depth, not the UI-shell value).</li>
     *   <li>X-Content-Type-Options (nosniff), X-Frame-Options (DENY),
     *       Cache-Control (no-store) and Referrer-Policy (no-referrer) are Spring
     *       Security's reactive SECURE DEFAULTS — left implicit and NOT disabled.</li>
     * </ul>
     * Deliberately OMITTED here: HSTS (meaningless / misleading over the plaintext
     * intra-mesh hop — lives only at the TLS edge) and Permissions-Policy (a
     * no-document JSON API has no browser-feature surface to gate). This is
     * ADDITIVE: it changes no authz rule, no issuer validation and no CSRF posture.
     */
    private static void hardenHeaders(ServerHttpSecurity.HeaderSpec h) {
        h.contentSecurityPolicy(csp -> csp.policyDirectives(
                "default-src 'none'; frame-ancestors 'none'"));
        // nosniff + X-Frame-Options DENY + Cache-Control + Referrer-Policy:no-referrer
        // remain Spring Security reactive defaults — intentionally NOT overridden.
    }

    /**
     * Shared route rules common to BOTH the dev/test and prod filter chains. The
     * ONLY difference between the two profiles is how {@link #SPEC_PATHS} is gated,
     * so every other route lives here to keep the two chains in lockstep and make
     * it impossible to loosen a non-spec route by editing only one chain.
     *
     * @param ex the {@link ServerHttpSecurity.AuthorizeExchangeSpec} to configure;
     *           mutated in-place by adding path-matcher rules for health probes,
     *           the superadmin surface, the admin surface, and the wiki read floor
     */
    private void commonRoutes(ServerHttpSecurity.AuthorizeExchangeSpec ex) {
        // Probes are public so k8s/Docker healthchecks need no token.
        ex.pathMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll();
        // The MANAGE-ALL surface — SUPERADMIN only (the audit trail + the access-state
        // views today; settings + the ZIM-rebuild trigger, PR4). Declared BEFORE the
        // admin + floor prefixes so the most-specific path matches first. Wrapped in the
        // D8 step-up composition: BYTE-IDENTICAL to .hasRole("SUPERADMIN") while
        // app.mfa.enabled is off (that is exactly what .hasRole delegates to), plus the
        // MFA_SATISFIED marker once the platform turns MFA on.
        ex.pathMatchers("/bff/wiki/superadmin/**").access(
                stepUp(AuthorityReactiveAuthorizationManager.hasRole("SUPERADMIN"),
                        WikiStepUpGate.Surface.SUPERADMIN));
        // The write/publish console seam — ADMIN (or the cross-domain SUPERADMIN
        // super-role) end-to-end. Admins reach it via the converter's ROLE_ADMIN with
        // no ROLE_WIKI needed; SUPERADMIN reaches it via the role hierarchy edge. The
        // SECOND D8 step-up surface: BYTE-IDENTICAL to .hasAnyRole("ADMIN","SUPERADMIN")
        // while app.mfa.enabled is off (what .hasAnyRole delegates to), plus the
        // MFA_SATISFIED marker once the platform turns MFA on.
        ex.pathMatchers("/bff/wiki/admin/**").access(
                stepUp(AuthorityReactiveAuthorizationManager.hasAnyRole("ADMIN", "SUPERADMIN"),
                        WikiStepUpGate.Surface.ADMIN));
        // The whole wiki read surface — the ROLE_WIKI floor is enforced at the gateway,
        // so .authenticated() proves authentication here. The exact WIKI_READ composite
        // (admin|superadmin|(MOD AND WIKI)) is re-checked at the method level
        // (@PreAuthorize) in the controller — NOT here, because hasAnyRole cannot
        // express the MOD-AND-WIKI AND.
        ex.pathMatchers("/bff/wiki/**").authenticated();
    }

    /**
     * D8 STEP-UP composition for the two PRIVILEGED wiki path groups. When
     * {@code app.mfa.enabled} is OFF (the dark default) the {@code base} rule is handed
     * straight back, so the emitted policy is byte-identical to the legacy one — this
     * MUST stay a no-op while MFA is dark, because the converter NEVER produces the
     * marker when the flag is off and an unconditional check would 403 every admin
     * and superadmin today. When ON, the path additionally demands the non-role
     * {@link Roles#MFA_SATISFIED} marker.
     *
     * <p>The marker is derived ONLY by {@link CasdoorAuthorities} from the BFF-written,
     * freshness-validated {@code mfa_satisfied_at} claim, and {@code MFA_SATISFIED} is
     * one of the converter's RESERVED prefixes — so a forged
     * {@code permissions:["MFA_SATISFIED"]} is dropped before it ever reaches this gate.
     * This is a genuine second-factor check, not a role re-check.
     *
     * <p>Fail-CLOSED by construction: an absent marker is simply "not granted", so a
     * client-credentials / never-stepped-up / STALE-stamp principal is denied the moment
     * the platform flips the flag — no follow-up change needed in this repo. {@code base}
     * is evaluated FIRST and short-circuits on deny, so the step-up leg can only ever
     * NARROW an already-granted decision, never widen one (an anonymous caller is still
     * 401 at the base rule, never a step-up 403). COPIED from the landed
     * hugmesh-forum-bff-api precedent so the two BFFs enforce the flag identically.
     *
     * <p><b>It also RECORDS which conjunct denied</b> (hugmesh-wiki-bff-api#35). When
     * {@code base} has granted and the marker leg refuses, {@code surface} is stamped on the
     * exchange ({@link WikiStepUpGate#recordDenial}) so {@link WikiStepUpAccessDeniedHandler}
     * can render 403 plus {@code X-Step-Up-Required: X-Wiki-StepUp} instead of a bare 403
     * that is indistinguishable from "you are not an admin". What is denied does not move: the
     * recording happens strictly AFTER the decision, on the deny branch only. A role denial
     * never reaches the recording line.
     *
     * @param base the role/authentication rule this path already enforced
     * @param surface which privileged path group this wrapper guards, for the refusal wording
     * @return {@code base} unchanged while MFA is dark; otherwise {@code base} AND the
     *         {@link Roles#MFA_SATISFIED} marker
     */
    private ReactiveAuthorizationManager<AuthorizationContext> stepUp(
            ReactiveAuthorizationManager<AuthorizationContext> base,
            WikiStepUpGate.Surface surface) {
        if (!mfaEnabled) {
            return base;
        }
        ReactiveAuthorizationManager<AuthorizationContext> mfaMarker =
                AuthorityReactiveAuthorizationManager.hasAuthority(Roles.MFA_SATISFIED);
        return (authentication, context) -> base.authorize(authentication, context)
                .flatMap(result -> result.isGranted()
                        ? mfaMarker.authorize(authentication, context)
                                .doOnNext(stepUpResult -> {
                                    if (!stepUpResult.isGranted()) {
                                        WikiStepUpGate.recordDenial(context.getExchange(), surface);
                                    }
                                })
                        : Mono.just(result));
    }

    /**
     * The chain-wide access-denied renderer, installed on BOTH profile chains so the two
     * cannot drift. A PASS-THROUGH for every denial except the one {@link #stepUp} recorded as
     * a step-up refusal — see {@link WikiStepUpAccessDeniedHandler}. Stateless; taken as a
     * METHOD PARAMETER by the chain beans so a slice test can build either chain from a bare
     * {@code ServerHttpSecurity.http()} and still get the production wiring.
     *
     * @return the shared handler instance
     */
    @Bean
    WikiStepUpAccessDeniedHandler wikiStepUpAccessDeniedHandler() {
        return new WikiStepUpAccessDeniedHandler();
    }

    /**
     * Dev / test profile: the OpenAPI spec + Swagger UI are PUBLIC so
     * {@code gen:fetch} (ng-openapi-gen) and the docs pipeline can curl
     * {@code /v3/api-docs} locally without a Casdoor token. Activate with
     * {@code SPRING_PROFILES_ACTIVE=dev} (or the {@code test} profile the slice
     * tests use). Every non-spec route is identical to prod.
     *
     * @param http the {@link ServerHttpSecurity} to configure
     * @param stepUpAccessDenied the step-up-aware access-denied handler
     * @return the built {@link SecurityWebFilterChain} for the dev/test profile
     */
    @Bean
    @Profile({"dev", "test"})
    SecurityWebFilterChain devFilterChain(ServerHttpSecurity http,
                                          WikiStepUpAccessDeniedHandler stepUpAccessDenied) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .headers(SecurityConfig::hardenHeaders)
                .exceptionHandling(e -> e.accessDeniedHandler(stepUpAccessDenied))
                .authorizeExchange(ex -> {
                    commonRoutes(ex);
                    ex.pathMatchers(SPEC_PATHS).permitAll();
                    ex.anyExchange().authenticated();
                })
                .oauth2ResourceServer(this::resourceServer)
                .build();
    }

    /**
     * Production profile (default — active whenever neither {@code dev} nor
     * {@code test} is): the OpenAPI spec + Swagger UI require ROLE_ADMIN (or the
     * cross-domain SUPERADMIN super-role) so the API contract is never publicly
     * reachable. Mirrors the forum-bff / user-bff / admin-bff profile split. Every
     * non-spec route is identical to dev/test.
     *
     * @param http the {@link ServerHttpSecurity} to configure
     * @param stepUpAccessDenied the step-up-aware access-denied handler
     * @return the built {@link SecurityWebFilterChain} for the production profile
     */
    @Bean
    @Profile("!dev & !test")
    SecurityWebFilterChain filterChain(ServerHttpSecurity http,
                                       WikiStepUpAccessDeniedHandler stepUpAccessDenied) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .headers(SecurityConfig::hardenHeaders)
                .exceptionHandling(e -> e.accessDeniedHandler(stepUpAccessDenied))
                .authorizeExchange(ex -> {
                    commonRoutes(ex);
                    ex.pathMatchers(SPEC_PATHS).hasAnyRole("ADMIN", "SUPERADMIN");
                    ex.anyExchange().authenticated();
                })
                .oauth2ResourceServer(this::resourceServer)
                .build();
    }

    /**
     * Wire the OAuth2 resource server. Multi-issuer is OPT-IN: swap to the per-issuer
     * authentication-manager resolver ONLY when BOTH a resolver was contributed by
     * {@link TenancyResourceServerConfig} AND the registry is genuinely multi-issuer
     * (more than the single back-compat entry). Gating on {@code isMultiIssuer()} —
     * not merely {@code resolver != null} — keeps a single-issuer cluster on the
     * EXISTING issuer-uri {@code o.jwt(...)} decoder path even if the tenancy
     * conditional ever activates with just the lone back-compat issuer. In true
     * multi-brand mode each trusted per-brand Casdoor validates its own tokens
     * (unknown iss -> 401, fail-closed).
     *
     * @param o the {@link ServerHttpSecurity.OAuth2ResourceServerSpec} to configure;
     *          either wired to the multi-issuer resolver or to the single-issuer JWT
     *          decoder depending on registry state
     */
    private void resourceServer(ServerHttpSecurity.OAuth2ResourceServerSpec o) {
        ReactiveAuthenticationManagerResolver<ServerWebExchange> resolver =
                tenantAuthManagerResolver.getIfAvailable();
        if (resolver != null && domainRegistry.isMultiIssuer()) {
            o.authenticationManagerResolver(resolver);
        } else {
            o.jwt(j -> j.jwtAuthenticationConverter(jwtConverter()));
        }
    }

    /**
     * Method-security role hierarchy — SUPERADMIN-only edges. Deliberately MINIMAL:
     * SUPERADMIN is a strict superset of admin/mod/user, but {@code ROLE_ADMIN} and
     * {@code ROLE_MOD} are documented SIBLINGS (no {@code ADMIN > MOD} edge) and the
     * AND-intersection composite ({@code WIKI_READ}'s {@code MOD AND WIKI}) is
     * intentionally ABSENT — so a hierarchy can NEVER let a plain ADMIN satisfy a
     * MOD-AND-WIKI gate or vice versa. This bean only smooths {@code @PreAuthorize}
     * ergonomics + lets SUPERADMIN reach the admin-gated write/manage + spec surfaces;
     * the actual cross-surface authorization (and the domain-scope) is enforced by the
     * service layer.
     *
     * @return a {@link RoleHierarchy} encoding {@code SUPERADMIN > ADMIN},
     *         {@code SUPERADMIN > MOD}, and {@code SUPERADMIN > USER}
     */
    @Bean
    RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy("""
                ROLE_SUPERADMIN > ROLE_ADMIN
                ROLE_SUPERADMIN > ROLE_MOD
                ROLE_SUPERADMIN > ROLE_USER""");
    }

    /**
     * Wire the {@link #roleHierarchy()} into REACTIVE method security so the
     * {@code @PreAuthorize("hasRole('ADMIN')")} gates honour {@code ROLE_SUPERADMIN >
     * ROLE_ADMIN} — i.e. the cross-domain SUPERADMIN reaches the ADMIN-only write
     * methods without an explicit {@code ROLE_ADMIN}. (A bare {@code RoleHierarchy}
     * bean is NOT auto-applied to {@code @PreAuthorize} in the reactive
     * method-security path, so the expression handler is set explicitly.) The
     * hierarchy is still deliberately MINIMAL — it never crosses the MOD-AND-WIKI
     * AND-composite (that gate stays an explicit AND in the WIKI_READ expression).
     *
     * @param roleHierarchy the {@link RoleHierarchy} bean produced by
     *                      {@link #roleHierarchy()}
     * @return a {@link MethodSecurityExpressionHandler} with the role hierarchy and
     *         the default {@code ROLE_} prefix applied
     */
    @Bean
    MethodSecurityExpressionHandler wikiMethodSecurityExpressionHandler(RoleHierarchy roleHierarchy) {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setRoleHierarchy(roleHierarchy);
        // Keep the default ROLE_ prefix explicit so hasRole('ADMIN') maps to ROLE_ADMIN.
        handler.setDefaultRolePrefix(new GrantedAuthorityDefaults("ROLE_").getRolePrefix());
        return handler;
    }

    /**
     * Builds a {@link ReactiveJwtAuthenticationConverter} bound to the
     * {@link CasdoorAuthorities} converter so the single-issuer decoder path
     * emits the same authority set as the multi-issuer path in
     * {@link TenancyResourceServerConfig}.
     *
     * @return a configured {@link ReactiveJwtAuthenticationConverter} that delegates
     *         JWT-to-authority conversion to {@link #casdoorAuthoritiesConverter()}
     */
    private ReactiveJwtAuthenticationConverter jwtConverter() {
        ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(casdoorAuthoritiesConverter());
        return converter;
    }

    /**
     * Casdoor JWT → authorities conversion. Delegates to the shared
     * {@link CasdoorAuthorities} so the single-issuer path here and the optional
     * multi-issuer path ({@link TenancyResourceServerConfig}) stay byte-identical —
     * three claim sources ({@code roles} → {@code ROLE_<UPPER>}, {@code permissions}
     * verbatim MINUS reserved-prefix injection, legacy {@code isAdmin} →
     * {@code ROLE_ADMIN}), a {@code ROLE_USER} floor, plus exactly one issuer-derived
     * {@code DOMAIN_<x>} authority (NON-role; {@code DOMAIN_default} for the
     * single-issuer fallback so this cluster is unchanged).
     *
     * @return a {@link Converter} from {@link Jwt} to a {@link Flux} of
     *         {@link GrantedAuthority} instances produced by
     *         {@link CasdoorAuthorities#converter(DomainRegistry)}
     */
    Converter<Jwt, Flux<GrantedAuthority>> casdoorAuthoritiesConverter() {
        return CasdoorAuthorities.converter(domainRegistry,
                new CasdoorAuthorities.MfaConfig(mfaEnabled, mfaFreshnessWindowMillis));
    }
}
