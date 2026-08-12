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
 * ADMIN of brand X can only grant/ban/restore WITHIN brand X; a SUPERADMIN (null scope)
 * writes to the platform {@code default} domain (its acts are cross-domain at read time).
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
     *       supply — so we resolve the {@code targetDomain} body field against the trusted
     *       {@link DomainRegistry} ({@link PrincipalDomain#resolveKnownDomain}). In
     *       multi-issuer mode a SUPERADMIN MUST name a real victim brand: a missing/unknown
     *       target FAILS CLOSED ({@code 400}) rather than being normalised to {@code default},
     *       which the read guard would never match against a non-default user (the silent
     *       no-effect ban this fix closes). In single-issuer mode every principal is
     *       {@code default}, so a SUPERADMIN write keeps the legacy {@code default} brand
     *       (behaviour unchanged).</li>
     * </ul>
     */
    private Mono<String> writeDomain(Authentication actor, String targetDomain, String verb) {
        String scope = scopeOf(actor);
        // Scoped ADMIN: own brand only; the request's targetDomain is inert (no cross-brand write).
        if (scope != null) {
            return Mono.just(scope);
        }
        // SUPERADMIN cross-domain write: in single-issuer mode there is no real victim brand
        // to name, so keep the legacy default (unchanged behaviour).
        if (!principalDomain.isMultiIssuer()) {
            return Mono.just(DomainRegistry.DEFAULT_DOMAIN);
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
