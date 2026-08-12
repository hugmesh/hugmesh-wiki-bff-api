package net.hugmesh.wikibff.access;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;

/**
 * Spring Data R2DBC store for {@code wiki_access} — the grant/timeout/ban OVERLAY's
 * desired-state table (Liquibase {@code db/changelog/004-wiki-access.xml}). The
 * reactive-repository idiom (matches the hugmesh-federation-api exemplar). The reads are
 * DERIVED (one domain-scoped, one un-pinned per read — the SUPERADMIN cross-domain bypass);
 * the DB-generated-PK INSERT (the {@code wiki_access.id} column carries a
 * {@code (gen_random_uuid())::text} DEFAULT — changelog 006; the INSERT omits {@code id} and
 * reads it back via {@code RETURNING id}, a plain non-{@code @Modifying} {@code @Query}) and the
 * CAS {@code active -> revoked} UPDATE cannot be derived and are {@code @Query} statements
 * bound to {@code static final String} constants.
 *
 * <p>The service NEVER deletes a row — a restore/revoke is the CAS {@link #revoke} so the
 * table stays the audit trail.
 */
public interface WikiAccessDataRepository extends R2dbcRepository<WikiAccess, String> {

    // ── reads (derived) ────────────────────────────────────────────────

    /** Domain-scoped active override of a given type for a user, newest first (= scoped {@code findActive}). */
    Mono<WikiAccess> findFirstByUserSubjectAndAccessTypeAndStateAndDomainOrderByCreatedAtDescIdAsc(
            String userSubject, String accessType, String state, String domain);

    /** SUPERADMIN cross-domain active override of a given type for a user (= un-pinned {@code findActive}). */
    Mono<WikiAccess> findFirstByUserSubjectAndAccessTypeAndStateOrderByCreatedAtDescIdAsc(
            String userSubject, String accessType, String state);

    /** Domain-scoped active working set for a principal (= scoped {@code activeFor}). */
    Flux<WikiAccess> findByUserSubjectAndStateAndDomainOrderByAccessTypeAscCreatedAtDesc(
            String userSubject, String state, String domain);

    /** SUPERADMIN cross-domain active working set for a principal (= un-pinned {@code activeFor}). */
    Flux<WikiAccess> findByUserSubjectAndStateOrderByAccessTypeAscCreatedAtDesc(
            String userSubject, String state);

    /** Domain-scoped manage surface — all active overrides in a brand (= scoped {@code listActive}). */
    Flux<WikiAccess> findByStateAndDomainOrderByDomainAscUserSubjectAscAccessTypeAsc(
            String state, String domain);

    /** SUPERADMIN cross-domain manage surface — every brand's active overrides (= un-pinned {@code listActive}). */
    Flux<WikiAccess> findByStateOrderByDomainAscUserSubjectAscAccessTypeAsc(String state);

    /** Domain-scoped full history (active + revoked) for a principal (= scoped {@code historyFor}). */
    Flux<WikiAccess> findByUserSubjectAndDomainOrderByCreatedAtDescIdAsc(String userSubject, String domain);

    /** SUPERADMIN cross-domain full history for a principal (= un-pinned {@code historyFor}). */
    Flux<WikiAccess> findByUserSubjectOrderByCreatedAtDescIdAsc(String userSubject);

    // ── writes (@Query — non-derivable) ────────────────────────────────

    /** DB-generated PK INSERT of a new ACTIVE override with {@code now()} stamping. The
     *  {@code id} is OMITTED so Postgres fills it from the {@code (gen_random_uuid())::text}
     *  column DEFAULT (changelog 006); {@code RETURNING id} reads the generated id back. */
    String INSERT_ACTIVE_RETURNING_ID = """
            INSERT INTO wiki_access \
            (domain, user_subject, access_type, state, granted_by_subject, \
             reason, created_at, expires_at, revoked_at) \
            VALUES (:domain, :user, :type, 'active', :by, :reason, now(), :exp, NULL) \
            RETURNING id""";

    /** CAS {@code active -> revoked} for one row id, stamping {@code revoked_at}. */
    String REVOKE = """
            UPDATE wiki_access SET state = 'revoked', revoked_at = now() \
            WHERE id = :id AND state = 'active'""";

    /** The DB-generated-id INSERT — yields the database-minted id (plain {@code @Query},
     *  NOT {@code @Modifying}: {@code RETURNING id} reads the new id back). */
    @Query(INSERT_ACTIVE_RETURNING_ID)
    Mono<String> insertActiveRow(@Param("domain") String domain,
                                 @Param("user") String userSubject, @Param("type") String accessType,
                                 @Param("by") String grantedBy, @Param("reason") String reason,
                                 @Param("exp") OffsetDateTime expiresAt);

    @Modifying
    @Query(REVOKE)
    Mono<Long> revokeActive(@Param("id") String id);
}
