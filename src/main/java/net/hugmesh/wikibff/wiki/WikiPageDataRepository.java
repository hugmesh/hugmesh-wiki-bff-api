package net.hugmesh.wikibff.wiki;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Spring Data R2DBC store for the {@code wiki_page} content table — the reactive-repository
 * idiom (matches hugmesh-federation-api's {@code R2dbcRepository} exemplar). The
 * domain-scoped delegating {@link WikiPageRepository} chooses between the scoped finders
 * (a NON-null domain ⇒ {@code AND domain = ?}) and the un-pinned variants (the SUPERADMIN
 * cross-domain bypass), preserving the original {@code DatabaseClient} semantics exactly.
 *
 * <p>The id is DB-GENERATED (the {@code wiki_page.id} column carries a
 * {@code (gen_random_uuid())::text} DEFAULT — changelog 006), so the create INSERT OMITS
 * {@code id} and reads the generated value back via {@code RETURNING id} (a plain,
 * non-{@code @Modifying} {@code @Query} returning {@code Mono<String>}; the app no longer
 * mints {@code UUID.randomUUID().toString()}). The version-guarded update / publish / delete
 * write paths remain {@code @Query} statements bound to {@code static final String}
 * constants (never inline literals) — derivation cannot express the optimistic-concurrency
 * version guard or the {@code now()} stamping.
 */
public interface WikiPageDataRepository extends R2dbcRepository<WikiPage, String> {

    // ── reads (derived) ────────────────────────────────────────────────

    /** Domain-scoped page list, newest-updated first then id (= the scoped {@code list}). */
    Flux<WikiPage> findByDomainOrderByUpdatedAtDescIdAsc(String domain);

    /** SUPERADMIN cross-domain page list, newest-updated first then id (= the un-pinned {@code list}). */
    Flux<WikiPage> findAllByOrderByUpdatedAtDescIdAsc();

    /** Domain-scoped get-by-slug (first by id) (= scoped {@code findBySlug}). */
    Mono<WikiPage> findFirstBySlugAndDomainOrderByIdAsc(String slug, String domain);

    /** SUPERADMIN cross-domain get-by-slug (first by id) (= un-pinned {@code findBySlug}). */
    Mono<WikiPage> findFirstBySlugOrderByIdAsc(String slug);

    /** Domain-scoped get-by-id (= scoped {@code findById}). */
    Mono<WikiPage> findByIdAndDomain(String id, String domain);

    // ── writes (@Query — non-derivable) ────────────────────────────────

    /** DB-generated PK INSERT with {@code now()} stamping and {@code version = 1}. The
     *  {@code id} is OMITTED so Postgres fills it from the {@code (gen_random_uuid())::text}
     *  column DEFAULT (changelog 006); {@code RETURNING id} reads the generated id back. */
    String INSERT_RETURNING_ID = """
            INSERT INTO wiki_page (domain, slug, title, content_markdown, content_html, \
            author_subject, version, created_at, updated_at) \
            VALUES (:domain, :slug, :title, :md, :html, :author, 1, now(), now()) \
            RETURNING id""";

    /** Optimistic-concurrency UPDATE guarded on {@code version = :expected}; bumps version + stamps {@code updated_at}. */
    String UPDATE_BY_SLUG = """
            UPDATE wiki_page SET title = :title, content_markdown = :md, content_html = :html, \
            author_subject = :author, version = version + 1, updated_at = now() \
            WHERE slug = :slug AND domain = :domain AND version = :expected""";

    /** Mark a page published (set {@code is_published = true, published_at = now()}) within a brand. */
    String PUBLISH_BY_SLUG = """
            UPDATE wiki_page SET is_published = true, published_at = now() \
            WHERE slug = :slug AND domain = :domain""";

    /** Delete ONE page by its primary key — the row the service already resolved in the
     *  caller's scope. Deliberately carries NO domain predicate: the scope was applied at the
     *  resolve, and pinning the id is what makes it impossible to hit a row in a brand the
     *  caller did not name (issue #15). */
    String DELETE_BY_ID = "DELETE FROM wiki_page WHERE id = :id";

    /** The DB-generated-id INSERT — always yields the database-minted id (plain
     *  {@code @Query}, NOT {@code @Modifying}: {@code RETURNING id} reads the new id back). */
    @Query(INSERT_RETURNING_ID)
    Mono<String> insertPage(@Param("domain") String domain,
                            @Param("slug") String slug, @Param("title") String title,
                            @Param("md") String contentMarkdown, @Param("html") String contentHtml,
                            @Param("author") String authorSubject);

    @Modifying
    @Query(UPDATE_BY_SLUG)
    Mono<Long> updateBySlugGuarded(@Param("slug") String slug, @Param("title") String title,
                                   @Param("md") String contentMarkdown, @Param("html") String contentHtml,
                                   @Param("author") String authorSubject,
                                   @Param("expected") long expectedVersion, @Param("domain") String domain);

    @Modifying
    @Query(PUBLISH_BY_SLUG)
    Mono<Long> publishBySlugInDomain(@Param("slug") String slug, @Param("domain") String domain);

    /** Declared (not derived) so the deleted-row COUNT comes back: the inherited
     *  {@code deleteById} is {@code Mono<Void>} and cannot tell "removed" from "matched
     *  nothing", and the service must answer 404 — never 204 — for the latter. */
    @Modifying
    @Query(DELETE_BY_ID)
    Mono<Long> deletePageById(@Param("id") String id);
}
