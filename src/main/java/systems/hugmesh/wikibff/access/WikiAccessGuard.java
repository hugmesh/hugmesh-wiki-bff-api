package systems.hugmesh.wikibff.access;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import systems.hugmesh.commons.security.DomainRegistry;
import systems.hugmesh.wikibff.security.PrincipalDomain;
import systems.hugmesh.commons.security.Roles;
import systems.hugmesh.commons.web.ApiException;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * THE LOAD-BEARING enforcement of the grant/timeout/ban overlay: it resolves whether a
 * request principal may READ the wiki by combining the STATIC role gate with the DYNAMIC
 * {@code wiki_access} desired-state, applying the precedence rule.
 *
 * <p>{@code @PreAuthorize(WIKI_READ)} on the controller can only express the static role
 * composite (ADMIN | SUPERADMIN | (MOD AND WIKI)). It CANNOT consult the DB for a
 * per-principal grant or ban. So — exactly as admin-bff enforces {@code access_suspension}
 * at request time rather than leaving it inert — this guard is invoked programmatically by
 * the read endpoints, loads the principal's {@code wiki_access} rows for their brand, and
 * decides:
 *
 * <pre>
 *   READ ALLOWED  ==  (role qualifies: ADMIN|SUPERADMIN|(MOD AND WIKI)
 *                      OR an ACTIVE non-expired wiki_access GRANT of read for this
 *                         user+domain)
 *                 AND NOT (an ACTIVE non-expired BAN/TIMEOUT for this user+domain)
 * </pre>
 *
 * <p>PRECEDENCE — the load-bearing rule:
 * <ul>
 *   <li>an ACTIVE non-expired BAN/TIMEOUT DENIES even a principal who would otherwise
 *       qualify by ROLE or by GRANT (the ban wins);</li>
 *   <li>SUPERADMIN is UNAFFECTED by a ban (infra control plane) — checked FIRST, before
 *       any DB read;</li>
 *   <li>an EXPIRED timeout auto-restores: {@link WikiAccess#isEffectiveAt} treats a past
 *       {@code expires_at} as no-longer-active, so the row stops denying WITHOUT a write.</li>
 * </ul>
 *
 * <p>The {@code @PreAuthorize(WIKI_READ)} gate still runs FIRST (defence in depth: a plain
 * USER with neither role nor grant is rejected at the method gate before this guard runs);
 * this guard then enforces the DYNAMIC half — the grant that LETS a plain USER in, and the
 * ban that KEEPS a role-holder out. The two together are the full overlay semantics.
 */
@Component
public class WikiAccessGuard {

    private final WikiAccessRepository repo;
    private final PrincipalDomain principalDomain;

    public WikiAccessGuard(WikiAccessRepository repo, PrincipalDomain principalDomain) {
        this.repo = repo;
        this.principalDomain = principalDomain;
    }

    /**
     * Enforce wiki READ access for the request principal, applying the overlay precedence.
     * Completes (passes through the supplied/empty Mono) when read is allowed; errors with
     * a 403 {@link ApiException} when an active ban/timeout denies it OR when neither role
     * nor grant qualifies. Wrap a read endpoint as
     * {@code guard.assertCanRead(auth).then(wiki.list(auth)...)}.
     *
     * @param auth the Spring Security authentication for the current request principal
     * @return an empty {@link Mono} that completes normally when access is permitted
     * @throws ApiException (403 FORBIDDEN) if an active ban/timeout is in effect for the
     *                      principal, or if neither a qualifying role nor an active grant exists
     */
    public Mono<Void> assertCanRead(Authentication auth) {
        // SUPERADMIN is the infra control plane — exempt from the overlay (never banned
        // out), resolved with NO DB read.
        if (isSuperadmin(auth)) {
            return Mono.empty();
        }
        boolean roleQualifies = roleQualifiesForRead(auth);
        String domain = scopeOf(auth);
        String subject = subject(auth);
        Instant now = Instant.now();

        // Load the principal's ACTIVE overrides for THEIR brand...
        return repo.activeFor(subject, domain)
                .filter(a -> a.isEffectiveAt(now))   // ...dropping any lapsed timeout (auto-restores)...
                .collectList()                       // ...then materialise the working set...
                // ...and apply the ban>grant>role precedence (de-nested into a named helper).
                .flatMap(rows -> decideRead(rows, roleQualifies));
    }

    /** True iff {@code a} is an ACTIVE BAN/TIMEOUT (an {@code access_type='banned'} row) —
     *  routed through {@link AccessType} so the closed read|banned vocabulary is the lens,
     *  not a bare string-equals. */
    private static boolean isActiveBan(WikiAccess a) {
        return AccessType.BANNED == AccessType.fromWire(a.accessType());
    }

    /** True iff {@code a} is an ACTIVE GRANT (an {@code access_type='read'} row) — same
     *  {@link AccessType} lens as {@link #isActiveBan}. */
    private static boolean isActiveGrant(WikiAccess a) {
        return AccessType.READ == AccessType.fromWire(a.accessType());
    }

    /**
     * Apply the overlay precedence to the principal's ACTIVE (non-expired) override rows:
     * an active BAN denies (wins over role and grant); otherwise read is allowed if the
     * STATIC role qualifies OR an active GRANT exists; else denied. Behaviour-identical to
     * the prior inline {@code flatMap} body — extracted so the Mono chain reads as one line
     * per operator.
     */
    private Mono<Void> decideRead(List<WikiAccess> rows, boolean roleQualifies) {
        boolean banned = rows.stream().anyMatch(WikiAccessGuard::isActiveBan);
        boolean granted = rows.stream().anyMatch(WikiAccessGuard::isActiveGrant);
        // PRECEDENCE: an active ban/timeout denies even a role-holder or grantee.
        if (banned) {
            return Mono.error(ApiException.forbidden(
                    "wiki read is banned for this principal in this brand"));
        }
        // Otherwise allowed if the role qualifies OR an active grant exists.
        if (roleQualifies || granted) {
            return Mono.empty();
        }
        return Mono.error(ApiException.forbidden(
                "wiki read requires a qualifying role or an active grant"));
    }

    /**
     * True iff the principal's STATIC roles satisfy WIKI_READ
     * (ADMIN | SUPERADMIN | (MOD AND WIKI)) — the same composite the {@code @PreAuthorize}
     * expresses, evaluated here so the guard can OR-in the dynamic grant.
     *
     * @param auth the Spring Security authentication for the current request principal
     * @return {@code true} iff the principal holds ROLE_ADMIN, ROLE_SUPERADMIN, or both
     *         ROLE_MOD and ROLE_WIKI
     */
    private boolean roleQualifiesForRead(Authentication auth) {
        if (auth == null) return false;
        Collection<? extends GrantedAuthority> a = auth.getAuthorities();
        return hasRole(a, "ROLE_ADMIN")
                || hasRole(a, "ROLE_SUPERADMIN")
                || (hasRole(a, "ROLE_MOD") && hasRole(a, "ROLE_WIKI"));
    }

    /**
     * Linear scan of the authority collection for an exact authority string match.
     *
     * @param auths the granted authorities from the current authentication
     * @param role  the exact authority string to search for (e.g. {@code "ROLE_ADMIN"})
     * @return {@code true} iff {@code auths} contains an authority whose string equals {@code role}
     */
    private static boolean hasRole(Collection<? extends GrantedAuthority> auths, String role) {
        if (auths == null) return false;
        return auths.stream().anyMatch(g -> role.equals(g.getAuthority()));
    }

    /**
     * SUPERADMIN read from either the JWT roles claim (runtime shape) or the converted
     * authorities (test/authorities-only shape).
     *
     * @param auth the Spring Security authentication for the current request principal
     * @return {@code true} iff the principal is a SUPERADMIN by JWT roles claim or by
     *         granted authority
     */
    private boolean isSuperadmin(Authentication auth) {
        if (auth == null) return false;
        if (auth.getPrincipal() instanceof Jwt jwt && Roles.isSuperadmin(jwt)) {
            return true;
        }
        return Roles.isSuperadmin(auth.getAuthorities());
    }

    /**
     * The caller's brand — derived SERVER-SIDE from the validated JWT issuer
     * ({@link PrincipalDomain}); the {@code default} domain for a non-JWT principal
     * (test/single-tenant). NEVER a request field. SUPERADMIN never reaches this (it is
     * short-circuited above), so the {@code null} bypass is never used by the guard.
     *
     * @param auth the Spring Security authentication for the current request principal
     * @return the brand/tenant string derived from the JWT issuer, or
     *         {@link DomainRegistry#DEFAULT_DOMAIN} for non-JWT principals
     */
    private String scopeOf(Authentication auth) {
        String d = principalDomain.resolveScope(auth, DomainRegistry.DEFAULT_DOMAIN);
        return d == null ? DomainRegistry.DEFAULT_DOMAIN : d;
    }

    /**
     * The acting principal's Casdoor subject — the key the overlay targets.
     *
     * @param auth the Spring Security authentication for the current request principal
     * @return the JWT {@code sub} claim when the principal is a {@link Jwt}; otherwise
     *         {@link Authentication#getName()}, or an empty string if both are absent
     */
    private static String subject(Authentication auth) {
        return PrincipalDomain.resolveSubject(auth, "");
    }
}
