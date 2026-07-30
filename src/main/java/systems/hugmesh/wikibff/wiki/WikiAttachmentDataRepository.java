package systems.hugmesh.wikibff.wiki;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Spring Data R2DBC store for the {@code wiki_attachment} table — the reactive-repository
 * idiom (matches the hugmesh-federation-api exemplar). The slug-keyed reads JOIN
 * {@code wiki_page} to resolve the human-readable slug, so they cannot be derived and are
 * {@code @Query} statements bound to {@code static final String} constants (one scoped, one
 * un-pinned per read, matching the original {@code DatabaseClient} SUPERADMIN bypass). The
 * per-page delete is a derived method; the single-row delete uses a subquery-join
 * {@code @Query}.
 */
public interface WikiAttachmentDataRepository extends R2dbcRepository<WikiAttachment, String> {

    String COLS = "a.id, a.page_id, a.domain, a.filename, a.mime_type, a.data, a.created_at";

    /** Domain-scoped: all attachments for a page identified by slug, within a brand. */
    String FIND_ALL_BY_SLUG_AND_DOMAIN = "SELECT " + COLS
            + " FROM wiki_attachment a JOIN wiki_page p ON a.page_id = p.id"
            + " WHERE p.slug = :slug AND a.domain = :domain";

    /** SUPERADMIN cross-domain: all attachments for a page identified by slug, any brand. */
    String FIND_ALL_BY_SLUG = "SELECT " + COLS
            + " FROM wiki_attachment a JOIN wiki_page p ON a.page_id = p.id"
            + " WHERE p.slug = :slug";

    /** Domain-scoped: one attachment by slug + filename, within a brand. */
    String FIND_BY_SLUG_FILENAME_AND_DOMAIN = "SELECT " + COLS
            + " FROM wiki_attachment a JOIN wiki_page p ON a.page_id = p.id"
            + " WHERE p.slug = :slug AND a.filename = :filename AND a.domain = :domain";

    /** SUPERADMIN cross-domain: one attachment by slug + filename, any brand. */
    String FIND_BY_SLUG_AND_FILENAME = "SELECT " + COLS
            + " FROM wiki_attachment a JOIN wiki_page p ON a.page_id = p.id"
            + " WHERE p.slug = :slug AND a.filename = :filename";

    /** Single-row delete located via a subquery-join on the page slug, within a brand. */
    String DELETE_BY_SLUG_AND_FILENAME = "DELETE FROM wiki_attachment WHERE id IN ("
            + "SELECT a.id FROM wiki_attachment a JOIN wiki_page p ON a.page_id = p.id"
            + " WHERE p.slug = :slug AND a.filename = :filename AND a.domain = :domain)";

    /** DB-generated PK INSERT with {@code now()} stamping. The {@code id} is OMITTED so
     *  Postgres fills it from the {@code (gen_random_uuid())::text} column DEFAULT
     *  (changelog 006); {@code RETURNING id} reads the generated id back. */
    String INSERT_RETURNING_ID =
            "INSERT INTO wiki_attachment (page_id, domain, filename, mime_type, data, created_at)"
            + " VALUES (:pageId, :domain, :filename, :mimeType, :data, now()) RETURNING id";

    @Query(FIND_ALL_BY_SLUG_AND_DOMAIN)
    Flux<WikiAttachment> findAllBySlugAndDomain(@Param("slug") String slug, @Param("domain") String domain);

    @Query(FIND_ALL_BY_SLUG)
    Flux<WikiAttachment> findAllBySlug(@Param("slug") String slug);

    @Query(FIND_BY_SLUG_FILENAME_AND_DOMAIN)
    Mono<WikiAttachment> findBySlugFilenameAndDomain(@Param("slug") String slug,
                                                     @Param("filename") String filename,
                                                     @Param("domain") String domain);

    @Query(FIND_BY_SLUG_AND_FILENAME)
    Mono<WikiAttachment> findBySlugAndFilename(@Param("slug") String slug,
                                               @Param("filename") String filename);

    /** Derived per-page delete (= {@code DELETE FROM wiki_attachment WHERE page_id = ?}); returns the deleted count. */
    Mono<Long> deleteByPageId(String pageId);

    @Modifying
    @Query(DELETE_BY_SLUG_AND_FILENAME)
    Mono<Long> deleteBySlugFilenameAndDomain(@Param("slug") String slug,
                                             @Param("filename") String filename,
                                             @Param("domain") String domain);

    /** The DB-generated-id INSERT — yields the database-minted id (plain {@code @Query},
     *  NOT {@code @Modifying}: {@code RETURNING id} reads the new id back). */
    @Query(INSERT_RETURNING_ID)
    Mono<String> insertAttachment(@Param("pageId") String pageId,
                                  @Param("domain") String domain, @Param("filename") String filename,
                                  @Param("mimeType") String mimeType, @Param("data") byte[] data);
}
