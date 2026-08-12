package net.hugmesh.wikibff.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import net.hugmesh.commons.security.DomainRegistry;
import net.hugmesh.commons.security.Roles;

/**
 * Resolves the EFFECTIVE domain-scope for a request principal — the single place a
 * controller/service turns a validated {@link Jwt} into the {@code domain} string the
 * repo layer scopes on. COPIED (logic) from the hugmesh-forum-bff-api
 * {@code PrincipalDomain} (package adjusted).
 *
 * <p>Two outcomes:
 * <ul>
 *   <li>SUPERADMIN (the cross-domain infra super-role, read from the validated
 *       {@code roles} claim) ⇒ {@code null} — the {@link DomainScope} bypass: the
 *       service applies NO domain predicate, so SUPERADMIN sees/mutates every brand's
 *       wiki pages (e.g. the whole-platform wiki across all brands);</li>
 *   <li>any other principal ⇒ the concrete {@code DOMAIN_<x>} derived from the
 *       token's cryptographically-validated ISSUER (per-brand Casdoor → distinct
 *       {@code iss}) through the SAME {@link DomainRegistry} the converter used. A
 *       single-issuer cluster resolves {@code "default"} for everyone, so behaviour
 *       is unchanged.</li>
 * </ul>
 *
 * <p>The domain is NEVER read from a request field — it comes only from the issuer,
 * so a caller cannot forge a cross-tenant scope. This sits ABOVE author-tracking
 * (author_subject == JWT subject), which the wiki service records independently; a
 * non-SUPERADMIN principal of brand X reaches ONLY brand X's pages.
 */
@Component
public class PrincipalDomain {

    private final DomainRegistry registry;

    /**
     * Constructs a {@code PrincipalDomain} backed by the given domain registry.
     *
     * @param registry the registry used to map JWT issuers to domain strings
     */
    public PrincipalDomain(DomainRegistry registry) {
        this.registry = registry;
    }

    /**
     * The domain to scope this principal's queries to: {@code null} for SUPERADMIN
     * (cross-domain bypass), else the issuer-derived {@code DOMAIN_<x>}.
     *
     * <p>FAIL-CLOSED for a non-SUPERADMIN whose issuer the registry can't resolve
     * (unknown issuer in multi-brand mode → {@code null} from the registry): we
     * substitute a sentinel that can match NO row ({@code __deny__}) rather than the
     * {@code null} bypass, so an unresolved issuer denies rather than escalates. In
     * single-issuer mode the registry always resolves {@code "default"}, so this
     * sentinel never fires there.
     *
     * @param jwt the validated JWT for the current request principal
     * @return {@code null} for a SUPERADMIN (cross-domain bypass), {@code "__deny__"}
     *         when the issuer cannot be resolved by the registry for a non-SUPERADMIN
     *         principal, or the concrete domain string (e.g. {@code "default"} or a
     *         brand-specific name) for all other authenticated principals
     */
    public String forScope(Jwt jwt) {
        if (Roles.isSuperadmin(jwt)) {
            return null;                       // SUPERADMIN cross-domain bypass
        }
        String domain = Roles.domainOf(jwt, registry);
        // A non-SUPERADMIN with an unresolved issuer must FAIL CLOSED, never inherit
        // the null (=bypass) sentinel. A non-matchable domain denies all rows.
        return domain == null ? "__deny__" : domain;
    }

    /**
     * The caller's domain-scope for callers that want the raw {@link #forScope(Jwt)}
     * outcome (including the {@code null} SUPERADMIN bypass) when the principal is a
     * {@link Jwt}, falling back to {@code fallback} (typically
     * {@link DomainRegistry#DEFAULT_DOMAIN}) for a non-JWT principal (e.g. a test stub).
     * Shared by the wiki services so the JWT-vs-non-JWT branching isn't duplicated at
     * each call site.
     */
    public String resolveScope(Authentication auth, String fallback) {
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return forScope(jwt);
        }
        return fallback;
    }

    /**
     * The acting principal's Casdoor subject (the {@code sub} claim) when the principal
     * is a {@link Jwt} (the runtime shape; also the robust path since
     * {@code Authentication.getName()} depends on the token type), else
     * {@code auth.getName()}, else {@code fallback} when there is no principal/name
     * (defensive; e.g. a seed job). NEVER read from a request field. Shared by the wiki
     * services so this fallback chain isn't duplicated at each call site.
     */
    public static String resolveSubject(Authentication auth, String fallback) {
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt && jwt.getSubject() != null) {
            return jwt.getSubject();
        }
        return auth == null || auth.getName() == null ? fallback : auth.getName();
    }

    /**
     * Resolve a SUPERADMIN-supplied TARGET brand for a cross-domain write (e.g. banning a
     * victim who lives in another brand): a SUPERADMIN has a {@code null} actor-scope, so the
     * brand a ban/timeout must land in cannot be derived from the actor's issuer — it has to
     * be the VICTIM's brand. This validates the requested brand against the SAME
     * {@link DomainRegistry} the converter trusts (mirrors the admin-bff "FIX-NODEFAULT"
     * register guard, {@link DomainRegistry#isKnownDomain}), so a SUPERADMIN can only target a
     * real, reachable per-brand domain — never {@code default}, blank, or an unknown string.
     *
     * @param requested the target brand from the request body (a SUPERADMIN-only field)
     * @return the validated known brand string, or {@code null} when {@code requested} is
     *         absent or not a known non-default brand (the caller must fail closed)
     */
    public String resolveKnownDomain(String requested) {
        return registry.isKnownDomain(requested) ? requested : null;
    }

    /**
     * True iff a genuine per-brand multi-issuer registry is configured ({@code app.tenancy
     * .issuers} parsed into 2+ brands) — the multi-brand posture in which a SUPERADMIN's
     * cross-brand ban MUST carry a target brand (single-issuer clusters are all {@code
     * default}, so the legacy behaviour is unchanged there).
     *
     * @return {@code true} iff the registry holds two or more issuer-to-brand entries
     */
    public boolean isMultiIssuer() {
        return registry.isMultiIssuer();
    }
}
