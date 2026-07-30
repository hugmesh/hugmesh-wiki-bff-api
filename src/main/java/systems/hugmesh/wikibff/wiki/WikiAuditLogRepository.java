package systems.hugmesh.wikibff.wiki;

import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import systems.hugmesh.commons.security.DomainRegistry;
import systems.hugmesh.commons.tenancy.DomainScope;

/**
 * Domain-scoped store for the {@code wiki_audit_log} table — the wiki-bff's OWN
 * content-edit audit trail. Delegates to the Spring Data {@link WikiAuditLogDataRepository}
 * (the reactive-repository idiom); the id is DB-generated (the {@code wiki_audit_log.id}
 * column carries a {@code (gen_random_uuid())::text} DEFAULT — changelog 006; the INSERT omits
 * {@code id} and reads it back via {@code RETURNING id}) and the new row is re-selected and
 * returned after the insert.
 *
 * <p><b>Domain-scoped (Liquibase {@code 003}).</b> Each row carries the brand/tenant
 * {@code domain} (stamped SERVER-SIDE from the actor's validated-issuer brand). The read
 * methods take a {@code domain} predicate ({@link DomainScope}) so a scoped ADMIN of brand
 * X reads only brand X's history; a {@code null} domain is the SUPERADMIN cross-domain
 * bypass.
 */
@Repository
public class WikiAuditLogRepository {

    /** Default audit-trail page size when the caller does not ask for one — the read is
     *  a bounded newest-first scan, never the whole table. */
    public static final int DEFAULT_LIMIT = 200;

    /** Hard cap on the audit-trail page size — a caller-supplied {@code limit} is clamped
     *  to this ceiling (and to a floor of 1) so the read can never drain the unbounded
     *  {@code wiki_audit_log} table regardless of the request. */
    public static final int MAX_LIMIT = 1000;

    private final WikiAuditLogDataRepository data;

    public WikiAuditLogRepository(WikiAuditLogDataRepository data) {
        this.data = data;
    }

    /**
     * Record an action (DB-generated id) in the actor's domain and return the persisted
     * row. The {@code domain} is stamped SERVER-SIDE (the actor's validated-issuer
     * brand), never a request field. A SUPERADMIN ({@code domain} null/blank) defaults
     * the row to the platform {@code default} domain so a stamped audit row always
     * carries a concrete brand. The {@code id} is DB-generated
     * ({@code (gen_random_uuid())::text} DEFAULT, changelog 006): the INSERT omits it and
     * {@code RETURNING id} hands back the generated id, which we re-select by.
     *
     * @param actorSubject the Casdoor {@code sub} of the acting principal
     * @param action       the audit action key (e.g. {@code wiki.page.create})
     * @param target       the slug or id of the affected resource
     * @param detail       a human-readable annotation (e.g. {@code "v2"}); may be blank
     * @param domain       the brand/tenant to stamp on the row; null/blank SUPERADMIN callers
     *                     are coerced to {@code default}
     * @return a {@link Mono} emitting the persisted {@link WikiAuditLog} row
     */
    public Mono<WikiAuditLog> record(String actorSubject, String action, String target,
                                     String detail, String domain) {
        String dom = DomainScope.isUnscoped(domain) ? DomainRegistry.DEFAULT_DOMAIN : domain;
        return data.insertEntry(dom, actorSubject, action, target, detail == null ? "" : detail)
                .flatMap(data::findById);
    }

    /** The audit trail for one brand (newest first) at the {@link #DEFAULT_LIMIT} page
     *  size; a {@code null} domain is the SUPERADMIN cross-domain bypass (every brand's
     *  actions). */
    public Flux<WikiAuditLog> list(String domain) {
        return list(domain, DEFAULT_LIMIT);
    }

    /**
     * The audit trail for one brand (newest first then id), bounded to a clamped page size;
     * a {@code null}/blank domain is the SUPERADMIN cross-domain bypass (every brand's
     * actions). The {@code limit} is CLAMPED to {@code [1, MAX_LIMIT]} (a non-positive
     * request falls back to {@link #DEFAULT_LIMIT}) so a caller can never widen the read
     * past the hard cap nor turn it back into an unbounded full-table scan.
     *
     * @param domain the brand/tenant to scope the query to; {@code null}/blank returns all brands
     * @param limit  the requested page size; clamped to {@code [1, MAX_LIMIT]}, non-positive
     *               values fall back to {@link #DEFAULT_LIMIT}
     * @return a {@link Flux} of {@link WikiAuditLog} rows ordered by {@code created_at DESC, id}
     */
    public Flux<WikiAuditLog> list(String domain, int limit) {
        int capped = clamp(limit);
        return DomainScope.isUnscoped(domain)
                ? data.listUnscoped(capped)
                : data.listScoped(domain, capped);
    }

    /** Clamp a requested page size into {@code [1, MAX_LIMIT]}; a non-positive request
     *  (unset/0/negative) becomes {@link #DEFAULT_LIMIT}. */
    private static int clamp(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /** Package-visible view of {@link #clamp} so the cap/floor/default contract can be
     *  asserted directly (no DB needed) — the load-bearing bound on the previously
     *  unbounded audit read. */
    static int clampForTest(int limit) {
        return clamp(limit);
    }
}
