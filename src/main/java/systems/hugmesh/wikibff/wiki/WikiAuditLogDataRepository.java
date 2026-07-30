package systems.hugmesh.wikibff.wiki;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Spring Data R2DBC store for the {@code wiki_audit_log} table — the reactive-repository
 * idiom (matches the hugmesh-federation-api exemplar). The domain-scoped delegating
 * {@link WikiAuditLogRepository} chooses between the scoped finder (a NON-null domain ⇒
 * {@code WHERE domain = ?}) and the un-pinned variant (the SUPERADMIN cross-domain bypass).
 *
 * <p>The id is DB-GENERATED (the {@code wiki_audit_log.id} column carries a
 * {@code (gen_random_uuid())::text} DEFAULT — changelog 006), so the INSERT (with
 * {@code now()} stamping) OMITS {@code id} and reads it back via {@code RETURNING id} (a
 * plain, non-{@code @Modifying} {@code @Query} returning {@code Mono<String>}); the new row
 * is then re-selected via the inherited {@code findById}.
 */
public interface WikiAuditLogDataRepository extends R2dbcRepository<WikiAuditLog, String> {

    // ── LIST (bounded newest-first scan; a RUNTIME LIMIT a derived method cannot
    // express, so an explicit static-final @Query per the data-access standard). The
    // ORDER BY (created_at DESC, id ASC) is IDENTICAL to the prior derived finders'
    // OrderByCreatedAtDescIdAsc — the only change is the trailing LIMIT :limit, so the
    // newest-first contract is unchanged and the read can no longer drain the whole
    // wiki_audit_log table on a busy multi-brand instance. ──

    /** Domain-scoped audit trail, newest first then id, capped at {@code limit}. */
    String LIST_SCOPED = "SELECT * FROM wiki_audit_log WHERE domain = :domain "
            + "ORDER BY created_at DESC, id ASC LIMIT :limit";

    /** SUPERADMIN cross-domain audit trail, newest first then id, capped at {@code limit}. */
    String LIST_UNSCOPED = "SELECT * FROM wiki_audit_log "
            + "ORDER BY created_at DESC, id ASC LIMIT :limit";

    /** Domain-scoped audit trail, newest first then id, capped at {@code limit} (= the
     *  scoped {@code list}). The cap is applied (clamped) in {@link WikiAuditLogRepository}. */
    @Query(LIST_SCOPED)
    Flux<WikiAuditLog> listScoped(@Param("domain") String domain, @Param("limit") int limit);

    /** SUPERADMIN cross-domain audit trail, newest first then id, capped at {@code limit}
     *  (= the un-pinned {@code list}). The cap is applied (clamped) in
     *  {@link WikiAuditLogRepository}. */
    @Query(LIST_UNSCOPED)
    Flux<WikiAuditLog> listUnscoped(@Param("limit") int limit);

    /** DB-generated PK INSERT with {@code now()} stamping. The {@code id} is OMITTED so
     *  Postgres fills it from the {@code (gen_random_uuid())::text} column DEFAULT
     *  (changelog 006); {@code RETURNING id} reads the generated id back. */
    String INSERT_RETURNING_ID = """
            INSERT INTO wiki_audit_log (domain, actor_subject, action, target, detail, created_at) \
            VALUES (:domain, :actor, :action, :target, :detail, now()) \
            RETURNING id""";

    /** The DB-generated-id INSERT — yields the database-minted id (plain {@code @Query},
     *  NOT {@code @Modifying}: {@code RETURNING id} reads the new id back). */
    @Query(INSERT_RETURNING_ID)
    Mono<String> insertEntry(@Param("domain") String domain,
                             @Param("actor") String actorSubject, @Param("action") String action,
                             @Param("target") String target, @Param("detail") String detail);
}
