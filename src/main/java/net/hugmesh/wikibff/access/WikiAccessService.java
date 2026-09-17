package net.hugmesh.wikibff.access;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import net.hugmesh.commons.security.DomainRegistry;
import net.hugmesh.commons.web.ApiException;
import net.hugmesh.wikibff.security.PrincipalDomain;

import java.time.Duration;
import java.time.Instant;

/**
 * The desired-state write surface for the {@code wiki_access} grant/timeout/ban overlay —
 * the API the {@link WikiAccessController} endpoints call. CLONED in semantics from the
 * admin-bff {@code SuspensionService}: it is DELIBERATELY the only write surface for the
 * overlay; it writes/CASes the table and NEVER touches Casdoor or the gateway directly.
 * The request-time enforcement ({@link WikiAccessGuard}) is the sole reader of the table
 * on the read path, so a write here changes who may read on the next request.
 *
 * <p>All writes are DOMAIN-scoped: the acting principal's brand is derived SERVER-SIDE
 * from the validated issuer ({@link PrincipalDomain}) and stamped on the row, so a scoped
 * ADMIN of brand X can only grant/ban/restore WITHIN brand X. A SUPERADMIN has a null scope
 * and so cannot derive the victim's brand from its own token; {@link #writeDomain} resolves
 * it instead — the named {@code targetDomain} when two or more brands are configured, the
 * single configured brand when there is exactly one, and the platform {@code default} domain
 * only when no real brand is configured at all.
 *
 * <ul>
 *   <li>{@link #grant} — give a USER an ACTIVE permanent {@code read} override
 *       (expires_at NULL).</li>
 *   <li>{@link #timeout} — a {@code banned} override with a FUTURE expires_at: it DENIES
 *       read until the TTL, then auto-restores (the enforcement read treats an expired
 *       row as inactive — no write needed on expiry).</li>
 *   <li>{@link #ban} — an ACTIVE permanent {@code banned} override (expires_at NULL).</li>
 *   <li>{@link #revoke} — CAS the active override of a type to {@code revoked} (the row
 *       is retained = audit). Restoring read = revoke the {@code banned} row; revoking a
 *       grant = revoke the {@code read} row.</li>
 * </ul>
 * Each write is IDEMPOTENT on an existing active row of the same type (returned unchanged
 * — the partial-unique index is the race backstop).
 */
@Service
public class WikiAccessService {

    private static final Logger log = LoggerFactory.getLogger(WikiAccessService.class);

    private final WikiAccessRepository repo;
    private final PrincipalDomain principalDomain;

    /**
     * Constructs the service with its required collaborators.
     *
     * @param repo            the R2DBC repository for {@code wiki_access} rows
     * @param principalDomain resolves the actor's brand from the validated JWT issuer
     */
    public WikiAccessService(WikiAccessRepository repo, PrincipalDomain principalDomain) {
        this.repo = repo;
        this.principalDomain = principalDomain;
    }

    /**
     * Grants {@code userSubject} a PERMANENT wiki read override in the actor's brand (or,
     * for a SUPERADMIN, the {@code targetDomain} victim brand). Delegates to
     * {@link #upsertActive} with {@code accessType=read} and {@code expiresAt=null};
     * idempotent if an active {@code read} row already exists.
     *
     * @param userSubject  the Casdoor subject of the user to grant read access
     * @param reason       human-readable reason recorded on the override row (may be null)
     * @param targetDomain the VICTIM's brand for a SUPERADMIN cross-brand grant; ignored
     *                     for a scoped ADMIN (issuer-derived)
     * @param actor        the authenticated admin/superadmin performing the grant
     * @return a {@link Mono} emitting the active {@link WikiAccess} row (new or existing)
     * @throws org.springframework.dao.DataAccessException if the DB write fails
     */
    public Mono<WikiAccess> grant(String userSubject, String reason, String targetDomain,
                                  Authentication actor) {
        return upsertActive(userSubject, WikiAccess.TYPE_READ, null, reason, targetDomain, actor, "grant");
    }

    /**
     * TIMEOUTs {@code userSubject}: inserts a {@code banned} override with a FUTURE
     * {@code expires_at} computed as {@code now + ttlSeconds}. The enforcement read
     * ({@link WikiAccessGuard}) stops denying once the timestamp passes — no write is
     * needed on expiry. Idempotent if an active {@code banned} row already exists.
     *
     * @param userSubject  the Casdoor subject of the user to time out
     * @param ttlSeconds   the ban duration in seconds; {@code expires_at} = now + this value
     * @param reason       human-readable reason recorded on the override row (may be null)
     * @param targetDomain the VICTIM's brand for a SUPERADMIN cross-brand timeout; ignored
     *                     for a scoped ADMIN (issuer-derived)
     * @param actor        the authenticated admin/superadmin performing the timeout
     * @return a {@link Mono} emitting the active {@link WikiAccess} row (new or existing)
     * @throws org.springframework.dao.DataAccessException if the DB write fails
     */
    public Mono<WikiAccess> timeout(String userSubject, long ttlSeconds, String reason,
                                    String targetDomain, Authentication actor) {
        Instant expiresAt = Instant.now().plus(Duration.ofSeconds(ttlSeconds));
        return upsertActive(userSubject, WikiAccess.TYPE_BANNED, expiresAt, reason, targetDomain,
                actor, "timeout");
    }

    /**
     * BANs {@code userSubject}: inserts a PERMANENT {@code banned} override
     * ({@code expires_at} NULL) in the actor's brand (or, for a SUPERADMIN, the
     * {@code targetDomain} victim brand). The ban takes effect immediately on the next
     * request evaluated by {@link WikiAccessGuard} and persists until explicitly revoked.
     * Idempotent if an active {@code banned} row already exists.
     *
     * @param userSubject  the Casdoor subject of the user to ban
     * @param reason       human-readable reason recorded on the override row (may be null)
     * @param targetDomain the VICTIM's brand for a SUPERADMIN cross-brand ban; ignored
     *                     for a scoped ADMIN (issuer-derived)
     * @param actor        the authenticated admin/superadmin performing the ban
     * @return a {@link Mono} emitting the active {@link WikiAccess} row (new or existing)
     * @throws org.springframework.dao.DataAccessException if the DB write fails
     */
    public Mono<WikiAccess> ban(String userSubject, String reason, String targetDomain,
                                Authentication actor) {
        return upsertActive(userSubject, WikiAccess.TYPE_BANNED, null, reason, targetDomain, actor, "ban");
    }

    /**
     * REVOKEs the active override of {@code accessType} for {@code userSubject} in the
     * actor's brand (CAS active->revoked; the row is retained = audit). A no-op (empty
     * Mono) when there is no active override of that type visible to the actor's brand
     * scope. Restoring read = revoke the {@code banned} row; removing a grant = revoke
     * the {@code read} row.
     *
     * <pre>
     * repo.findActive(userSubject, accessType, domain)  -- may be empty (no-op path)
     *   flatMap: repo.revoke(active.id())               -- CAS to 'revoked', logs
     *   then()                                           -- complete empty
     * </pre>
     *
     * @param userSubject the Casdoor subject whose override should be revoked
     * @param accessType  the type of override to revoke: {@code read} or {@code banned}
     * @param actor       the authenticated admin/superadmin performing the revoke
     * @return a {@link Mono}&lt;{@link Void}&gt; that completes empty on both a successful
     *         revoke and a no-op (no active row found)
     * @throws org.springframework.dao.DataAccessException if the DB CAS update fails
     */
    public Mono<Void> revoke(String userSubject, String accessType, Authentication actor) {
        String domain = scopeOf(actor);
        // A scoped admin can only resolve (and therefore revoke) a row in their own brand;
        // another brand's active override is invisible, so the revoke is a no-op for them.
        return repo.findActive(userSubject, accessType, domain)
                .flatMap(active -> repo.revoke(active.id())
                        .doOnNext(n -> log.info("[wiki-access] revoked {} for {} by {} (id={}, rows={})",
                                accessType, userSubject, subject(actor), active.id(), n)))
                .then();
    }

    /**
     * Returns all ACTIVE (non-revoked, non-expired) {@code wiki_access} overrides visible
     * to the actor. A scoped ADMIN sees only their brand; SUPERADMIN (null scope) sees all.
     *
     * @param actor the authenticated caller whose brand scope filters the result set
     * @return a {@link Flux} of active {@link WikiAccess} rows (may be empty)
     */
    public Flux<WikiAccess> listActive(Authentication actor) {
        return repo.listActive(scopeOf(actor));
    }

    /**
     * Returns the full history (active + revoked) of {@code wiki_access} rows for
     * {@code userSubject} visible to the actor — the audit read surface. Revoked rows
     * are retained permanently. Returns an empty {@link Flux} when no history exists
     * for the subject — never errors with a not-found signal.
     *
     * @param userSubject the Casdoor subject whose access history is being queried
     * @param actor       the authenticated caller whose brand scope filters the result set
     * @return a {@link Flux} of all {@link WikiAccess} rows for the subject (may be empty)
     */
    public Flux<WikiAccess> historyFor(String userSubject, Authentication actor) {
        return repo.historyFor(userSubject, scopeOf(actor));
    }

    // ── helpers ────────────────────────────────────────────────────────

    /**
     * Idempotent insert-active: an existing active row of the same type VISIBLE to the
     * WRITE scope is returned unchanged (the re-grant/re-ban while active is a no-op
     * logged at INFO); otherwise a new active row is inserted, stamped with the write
     * brand and the actor's subject. The write brand is the actor's own brand for a
     * scoped ADMIN, and the resolved {@code targetDomain} victim brand for a SUPERADMIN
     * (see {@link #writeDomain}).
     *
     * <pre>
     * writeDomain(actor, targetDomain, verb)              -- may fail closed (SUPERADMIN)
     *   flatMap: repo.findActive(userSubject, type, dom)  -- may be empty
     *     doOnNext: log no-op                             -- if row exists
     *     switchIfEmpty: repo.insertActive(...)           -- insert + log on creation
     * </pre>
     *
     * @param userSubject  the Casdoor subject of the target user
     * @param accessType   the override type: {@code read} or {@code banned}
     * @param expiresAt    the expiry instant, or {@code null} for a permanent override
     * @param reason       human-readable reason stored on the row (may be null)
     * @param targetDomain the VICTIM's brand for a SUPERADMIN cross-brand write; ignored
     *                     for a scoped ADMIN
     * @param actor        the authenticated caller; provides brand scope and granted_by subject
     * @param verb         label used in log messages ("grant", "timeout", "ban")
     * @return a {@link Mono} emitting the active {@link WikiAccess} row (existing or newly inserted)
     */
    private Mono<WikiAccess> upsertActive(String userSubject, String accessType, Instant expiresAt,
                                          String reason, String targetDomain, Authentication actor,
                                          String verb) {
        String by = subject(actor);
        // Resolve the WRITE brand first (may FAIL CLOSED for a SUPERADMIN cross-brand write
        // with no/unknown targetDomain) — done inside the reactive chain so the failure is an
        // errored Mono, not a synchronous assembly-time throw.
        return writeDomain(actor, targetDomain, verb)
                // The idempotency pre-check + the insert both use the SAME brand the row is
                // stamped with, so a SUPERADMIN re-ban of a brand-X user is a no-op against the
                // brand-X row (not against an unrelated default row the guard never reads).
                .flatMap(domain -> repo.findActive(userSubject, accessType, domain)
                        .doOnNext(existing -> log.info("[wiki-access] {} {} already active for {} (id={}) — no-op",
                                verb, accessType, userSubject, existing.id()))
                        .switchIfEmpty(Mono.defer(() ->
                                repo.insertActive(userSubject, accessType, expiresAt, reason, by, domain)
                                        .doOnNext(a -> log.info("[wiki-access] {} created {} for {} "
                                                        + "(id={}, expiresAt={}, domain={})",
                                                verb, accessType, userSubject, a.id(), expiresAt, a.domain())))));
    }

    /**
     * The brand a write must be STAMPED with — so {@link WikiAccessGuard}, which reads back
     * {@code activeFor(subject, victimBrand)}, actually matches it. Returns the brand as a
     * {@link Mono} so a SUPERADMIN's missing/unknown target FAILS CLOSED as an errored Mono
     * (a {@code 400}) rather than a synchronous assembly-time throw.
     *
     * <ul>
     *   <li>A scoped ADMIN ({@link #scopeOf} returns a concrete brand): always their own
     *       issuer-derived brand; the {@code targetDomain} body field is IGNORED so an ADMIN
     *       can never write into another brand.</li>
     *   <li>A SUPERADMIN ({@link #scopeOf} returns {@code null}, the cross-domain actor):
     *       the row must land in the VICTIM's brand, which the actor's null scope cannot
     *       supply. Three configurations, three answers — see the table below.</li>
     * </ul>
     *
     * <h3>The SUPERADMIN cases, exhaustively</h3>
     *
     * <pre>
     *  app.tenancy.issuers  | a USER resolves | targetDomain     | stamped   | before decision 26
     *  ─────────────────────┼─────────────────┼──────────────────┼───────────┼────────────────────
     *  unset (back-compat)  | "default"       | anything (inert) | "default" | same
     *  ONE pair "=acme"     | "acme"          | absent/blank     | "acme"    | "default" (INERT!)
     *  ONE pair "=acme"     | "acme"          | "acme"           | "acme"    | "default" (INERT!)
     *  ONE pair "=acme"     | "acme"          | "globex"         | 400       | "default" (INERT!)
     *  two or more pairs    | per-issuer      | a known brand    | that brand| same
     *  two or more pairs    | per-issuer      | absent/unknown   | 400       | same
     * </pre>
     *
     * <p><b>Back-compat is UNCHANGED, by construction.</b> With {@code app.tenancy.issuers}
     * empty the registry holds only the {@code {issuer -> "default"}} seed, so
     * {@link PrincipalDomain#soleConfiguredBrand} answers {@code null} and this method returns
     * {@link DomainRegistry#DEFAULT_DOMAIN} down the same branch it always did. Every principal
     * on such a mesh really IS {@code default}, so the stamp matches the guard's read and there
     * is nothing to fix; {@code targetDomain} stays inert there rather than becoming a new way
     * to get a {@code 400} out of a deployment that never had one.
     *
     * <p><b>One configured pair is a real brand</b> (operator decision 26 on
     * hugmesh-generator#347, issue #21). It was the one shape where the two sides disagreed:
     * every user resolved {@code acme} while a SUPERADMIN grant/timeout/ban was stamped
     * {@code default}, so {@link WikiAccessGuard} — which reads back
     * {@code activeFor(subject, "acme")} — never matched the row and the ban silently did
     * nothing. The stamp is now the configured brand, and a {@code targetDomain} naming any
     * OTHER brand is a {@code 400} instead of a write that lands somewhere the operator did
     * not ask for. A caller that omits {@code targetDomain} needs no change: there is exactly
     * one brand it could have meant.
     *
     * <p><b>Two or more pairs are untouched</b> — PR #39's fail-closed contract stands: name a
     * known victim brand or take a {@code 400}. That case was never silent, so it is not this
     * fix's business.
     *
     * <p>The error message names the CONFIGURED brand (registry-derived, sanitised to
     * {@code [a-z0-9-]} at construction) and deliberately does NOT echo the caller's own
     * string back into the response body.
     */
    private Mono<String> writeDomain(Authentication actor, String targetDomain, String verb) {
        String scope = scopeOf(actor);
        // Scoped ADMIN: own brand only; the request's targetDomain is inert (no cross-brand write).
        if (scope != null) {
            return Mono.just(scope);
        }
        // SUPERADMIN cross-domain write, 0 or 1 configured issuer pairs.
        if (!principalDomain.isMultiIssuer()) {
            String sole = principalDomain.soleConfiguredBrand();
            // No real brand configured (nothing set, or the back-compat {issuer -> "default"}
            // seed): every principal genuinely resolves "default", so stamping it is correct
            // and targetDomain stays inert. BYTE-FOR-BYTE the pre-decision-26 behaviour.
            if (sole == null) {
                return Mono.just(DomainRegistry.DEFAULT_DOMAIN);
            }
            // Exactly one configured pair: that IS this mesh's brand. Stamp it, so the row
            // lands where WikiAccessGuard looks instead of the inert 'default'.
            if (targetDomain == null || targetDomain.isBlank() || sole.equals(targetDomain)) {
                return Mono.just(sole);
            }
            // A named brand that is not the one this mesh has: FAIL LOUDLY. Silently writing
            // the sole brand anyway would honour a request nobody made; silently writing the
            // named one would strand the row in a domain no principal here can resolve.
            return Mono.error(ApiException.badRequest(
                    "a SUPERADMIN " + verb + " can only target this deployment's single "
                            + "configured brand '" + sole + "'; leave targetDomain unset to use it"));
        }
        // Multi-brand: resolve the victim brand from the body against the trusted registry and
        // FAIL CLOSED if it is absent/unknown — a default-stamped ban is silently ineffective.
        String resolved = principalDomain.resolveKnownDomain(targetDomain);
        return resolved != null
                ? Mono.just(resolved)
                : Mono.error(ApiException.badRequest(
                        "a SUPERADMIN " + verb + " must target a known brand via targetDomain"));
    }

    /**
     * Derives the actor's brand scope from the validated JWT issuer: {@code null} for
     * SUPERADMIN (cross-domain), else the issuer-derived {@code DOMAIN_<x>};
     * {@link DomainRegistry#DEFAULT_DOMAIN} for a non-JWT (test) principal. NEVER a
     * request field — always server-derived.
     *
     * @param auth the {@link Authentication} whose principal is inspected
     * @return the domain scope string, or {@code null} for cross-domain (SUPERADMIN)
     */
    private String scopeOf(Authentication auth) {
        return principalDomain.resolveScope(auth, DomainRegistry.DEFAULT_DOMAIN);
    }

    /**
     * Extracts the Casdoor subject from the acting principal, used as the
     * {@code granted_by} key on written rows.
     *
     * @param auth the {@link Authentication} of the acting principal
     * @return the subject string (JWT {@code sub} claim, name, or {@code "@system"})
     */
    private static String subject(Authentication auth) {
        return PrincipalDomain.resolveSubject(auth, "@system");
    }
}
