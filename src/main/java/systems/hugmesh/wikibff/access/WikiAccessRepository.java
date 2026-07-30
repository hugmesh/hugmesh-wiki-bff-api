package systems.hugmesh.wikibff.access;

import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import systems.hugmesh.commons.security.DomainRegistry;
import systems.hugmesh.commons.tenancy.DomainScope;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Domain-scoped store for {@code wiki_access} — the grant/timeout/ban OVERLAY's
 * desired-state table (Liquibase {@code db/changelog/004-wiki-access.xml}). Delegates to
 * the Spring Data {@link WikiAccessDataRepository} (the reactive-repository idiom, matching
 * the hugmesh-federation-api exemplar): DB-generated ids (the {@code wiki_access.id} column
 * carries a {@code (gen_random_uuid())::text} DEFAULT — changelog 006; the INSERT omits
 * {@code id} and reads it back via {@code RETURNING id}); the
 * never-delete/CAS-active->revoked audit semantics.
 *
 * <p>The service NEVER deletes a row — a restore/revoke is a CAS {@code active -> revoked}
 * ({@link #revoke}) so the table stays the audit trail. {@link #insertActive} relies on the
 * partial UNIQUE index {@code (domain, user_subject, access_type) WHERE state='active'} so a
 * concurrent double-grant/ban surfaces as a unique-violation (PG) the service treats as
 * "already set"; on H2 the service's findActive-before-insert is the guard.
 *
 * <p>The READS are DOMAIN-scoped via {@link DomainScope}: a scoped admin only ever resolves
 * rows in THEIR brand; a {@code null} domain is the SUPERADMIN cross-domain bypass (the
 * un-pinned finders). The request-time enforcement read ({@link #activeFor}) is scoped to
 * the principal's own brand.
 */
@Repository
public class WikiAccessRepository {

    private final WikiAccessDataRepository data;

    public WikiAccessRepository(WikiAccessDataRepository data) {
        this.data = data;
    }

    // ── writes (insert active; CAS active->revoked) ────────────────────

    /**
     * Insert a new ACTIVE override (DB-generated id) in the given brand and return it.
     * The caller is expected to have checked for an existing active row of the same type
     * first; the partial unique index is the backstop against a race. {@code expiresAt}
     * null = PERMANENT; a future instant = a TIMEOUT (a 'banned' row that auto-restores).
     * The {@code domain} is the acting principal's brand (defaulted to the platform
     * {@code default} domain for a SUPERADMIN unscoped write so a stamped row always
     * carries a concrete brand). The {@code id} is DB-generated
     * ({@code (gen_random_uuid())::text} DEFAULT, changelog 006): the INSERT omits it and
     * {@code RETURNING id} hands back the generated id, which we render into the returned
     * {@link WikiAccess} (same field order/values as before).
     *
     * @param userSubject the Casdoor subject of the principal being granted or banned
     * @param accessType  {@link WikiAccess#TYPE_READ} or {@link WikiAccess#TYPE_BANNED}
     * @param expiresAt   {@code null} for a permanent override; a future instant for a timeout
     * @param reason      optional free-text audit reason; {@code null} is bound as SQL NULL
     * @param grantedBy   Casdoor subject of the admin issuing the override
     * @param domain      brand/tenant for the row; unscoped (SUPERADMIN) values are
     *                    normalised to {@link DomainRegistry#DEFAULT_DOMAIN}
     * @return a {@link Mono} emitting the newly constructed {@link WikiAccess} record
     * @throws org.springframework.dao.DataIntegrityViolationException if a concurrent
     *         double-grant/ban races past the caller's pre-check and hits the partial
     *         unique index {@code (domain, user_subject, access_type) WHERE state='active'}
     */
    public Mono<WikiAccess> insertActive(String userSubject, String accessType, Instant expiresAt,
                                         String reason, String grantedBy, String domain) {
        String dom = DomainScope.isUnscoped(domain) ? DomainRegistry.DEFAULT_DOMAIN : domain;
        return data.insertActiveRow(dom, userSubject, accessType, grantedBy, reason, offset(expiresAt))
                .map(genId -> new WikiAccess(genId, dom, userSubject, accessType,
                        WikiAccess.STATE_ACTIVE, grantedBy, reason, Instant.now(), expiresAt, null));
    }

    /**
     * Compare-and-swap {@code active -> revoked} for one row id, stamping
     * {@code revoked_at}, guarded on the current state so only ONE writer wins the
     * transition (returns the rows-updated count: 1 = this caller won, 0 = already
     * revoked). The row is never deleted — the revoke is the audit of WHEN the override
     * was lifted.
     *
     * @param id the surrogate UUID of the {@code wiki_access} row to revoke
     * @return a {@link Mono} emitting {@code 1L} if this caller performed the transition,
     *         or {@code 0L} if the row was already in {@code revoked} state
     */
    public Mono<Long> revoke(String id) {
        return data.revokeActive(id);
    }

    // ── reads (domain-scoped) ──────────────────────────────────────────

    /**
     * The active override of a given type for a (user) VISIBLE to the given tenant
     * scope. A scoped admin only ever resolves a row in THEIR brand (so it can never
     * clear / re-target another brand's override); SUPERADMIN ({@code null} domain)
     * resolves it in any brand. The predicate is added in-SQL so the row is invisible —
     * not merely hidden after the fact.
     *
     * @param userSubject the Casdoor subject of the principal whose override to look up
     * @param accessType  the override type to match ({@link WikiAccess#TYPE_READ} or
     *                    {@link WikiAccess#TYPE_BANNED})
     * @param domain      the caller's brand scope; {@code null} = SUPERADMIN cross-domain bypass
     * @return a {@link Mono} emitting the most-recently-created matching active row,
     *         or empty if none exists within the given scope
     */
    public Mono<WikiAccess> findActive(String userSubject, String accessType, String domain) {
        return DomainScope.isUnscoped(domain)
                ? data.findFirstByUserSubjectAndAccessTypeAndStateOrderByCreatedAtDescIdAsc(
                        userSubject, accessType, WikiAccess.STATE_ACTIVE)
                : data.findFirstByUserSubjectAndAccessTypeAndStateAndDomainOrderByCreatedAtDescIdAsc(
                        userSubject, accessType, WikiAccess.STATE_ACTIVE, domain);
    }

    /**
     * Every ACTIVE override for a principal WITHIN a brand — the request-time enforcement
     * working set ({@link WikiAccessGuard} reads this to apply the grant/ban precedence).
     * Scoped to the brand so a principal's overrides in brand X never affect brand Y. A
     * {@code null} domain is the SUPERADMIN cross-domain bypass (every brand's rows) —
     * but the guard never needs that path (SUPERADMIN is exempt from the overlay).
     *
     * @param userSubject the Casdoor subject of the principal whose active overrides to load
     * @param domain      the caller's brand scope; {@code null} = SUPERADMIN cross-domain bypass
     * @return a {@link Flux} of all active {@link WikiAccess} rows for the principal in scope,
     *         ordered by {@code access_type}, then {@code created_at DESC}
     */
    public Flux<WikiAccess> activeFor(String userSubject, String domain) {
        return DomainScope.isUnscoped(domain)
                ? data.findByUserSubjectAndStateOrderByAccessTypeAscCreatedAtDesc(
                        userSubject, WikiAccess.STATE_ACTIVE)
                : data.findByUserSubjectAndStateAndDomainOrderByAccessTypeAscCreatedAtDesc(
                        userSubject, WikiAccess.STATE_ACTIVE, domain);
    }

    /**
     * All ACTIVE overrides VISIBLE to the given tenant scope — the MANAGE read surface.
     * A scoped admin sees ONLY their brand's rows; SUPERADMIN ({@code null} domain) sees
     * every brand's.
     *
     * @param domain the caller's brand scope; {@code null} = SUPERADMIN cross-domain bypass
     * @return a {@link Flux} of all active {@link WikiAccess} rows visible to the given scope,
     *         ordered by {@code domain}, {@code user_subject}, {@code access_type}
     */
    public Flux<WikiAccess> listActive(String domain) {
        return DomainScope.isUnscoped(domain)
                ? data.findByStateOrderByDomainAscUserSubjectAscAccessTypeAsc(WikiAccess.STATE_ACTIVE)
                : data.findByStateAndDomainOrderByDomainAscUserSubjectAscAccessTypeAsc(
                        WikiAccess.STATE_ACTIVE, domain);
    }

    /**
     * The full history (active + revoked) for a principal WITHIN a brand — the audit
     * read (state=revoked rows are RETAINED). A {@code null} domain is the SUPERADMIN
     * cross-domain bypass.
     *
     * @param userSubject the Casdoor subject of the principal whose full override history to load
     * @param domain      the caller's brand scope; {@code null} = SUPERADMIN cross-domain bypass
     * @return a {@link Flux} of all {@link WikiAccess} rows (any state) for the principal
     *         in scope, ordered by {@code created_at DESC}, then {@code id}
     */
    public Flux<WikiAccess> historyFor(String userSubject, String domain) {
        return DomainScope.isUnscoped(domain)
                ? data.findByUserSubjectOrderByCreatedAtDescIdAsc(userSubject)
                : data.findByUserSubjectAndDomainOrderByCreatedAtDescIdAsc(userSubject, domain);
    }

    // ── timestamp helper (R2DBC maps timestamptz <-> OffsetDateTime on H2/PG) ──
    private static OffsetDateTime offset(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }
}
