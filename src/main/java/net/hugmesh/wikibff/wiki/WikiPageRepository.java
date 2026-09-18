package net.hugmesh.wikibff.wiki;

import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import net.hugmesh.commons.security.DomainRegistry;
import net.hugmesh.commons.tenancy.DomainScope;

/**
 * Domain-scoped store for the {@code wiki_page} content table — TIER 1 (WRITE). Delegates
 * to the Spring Data {@link WikiPageDataRepository} (the reactive-repository idiom, matching
 * the hugmesh-federation-api exemplar); the id is DB-generated (the {@code wiki_page.id}
 * column carries a {@code (gen_random_uuid())::text} DEFAULT — changelog 006), so the INSERT
 * omits {@code id}, reads the generated id back via {@code RETURNING id}, and the new row is
 * re-selected and returned.
 *
 * <p><b>Domain-scoped (Liquibase {@code 001}).</b> Each page carries the brand/tenant
 * {@code domain} (stamped SERVER-SIDE from the author's validated-issuer brand). The read
 * methods take a {@code domain} predicate ({@link DomainScope}) so a scoped ADMIN of brand
 * X reads only brand X's pages; a {@code null} domain is the SUPERADMIN cross-domain bypass
 * (the un-pinned finders). The {@code (domain, slug)} pair is UNIQUE, so a slug may exist
 * independently per brand.
 *
 * <p><b>Optimistic concurrency.</b> {@link #updateById} bumps {@code version} and guards
 * on {@code version = :expected}, so a concurrent/stale edit updates ZERO rows (the service
 * maps that to a 409). The atomic conditional UPDATE is the concurrent-edit serialization
 * point.
 */
@Repository
public class WikiPageRepository {

    private final WikiPageDataRepository data;

    public WikiPageRepository(WikiPageDataRepository data) {
        this.data = data;
    }

    // ── reads (domain-scoped) ──────────────────────────────────────────

    /**
     * The page list for one brand (newest-updated first); a {@code null} domain is the
     * SUPERADMIN cross-domain bypass (every brand's pages).
     *
     * @param domain the brand/tenant to scope the query to; {@code null} returns all brands
     * @return a {@link Flux} of {@link WikiPage} rows ordered by {@code updated_at DESC}
     */
    public Flux<WikiPage> list(String domain) {
        return DomainScope.isUnscoped(domain)
                ? data.findAllByOrderByUpdatedAtDescIdAsc()
                : data.findByDomainOrderByUpdatedAtDescIdAsc(domain);
    }

    /**
     * The page for a slug WITHIN a brand, if any; a {@code null} domain is the
     * SUPERADMIN cross-domain bypass (the slug in any brand — first by id).
     *
     * @param slug   the URL-stable page key to look up
     * @param domain the brand/tenant to scope the lookup to; {@code null} searches all brands
     * @return a {@link Mono} emitting the matching {@link WikiPage}, or empty if not found
     */
    public Mono<WikiPage> findBySlug(String slug, String domain) {
        return DomainScope.isUnscoped(domain)
                ? data.findFirstBySlugOrderByIdAsc(slug)
                : data.findFirstBySlugAndDomainOrderByIdAsc(slug, domain);
    }

    /**
     * The page for an id WITHIN a brand, if any; a {@code null} domain is the SUPERADMIN
     * cross-domain bypass.
     *
     * @param id     the DB-generated UUID primary key of the page (changelog 006)
     * @param domain the brand/tenant to scope the lookup to; {@code null} searches all brands
     * @return a {@link Mono} emitting the matching {@link WikiPage}, or empty if not found
     */
    public Mono<WikiPage> findById(String id, String domain) {
        return DomainScope.isUnscoped(domain)
                ? data.findById(id)
                : data.findByIdAndDomain(id, domain);
    }

    // ── writes ─────────────────────────────────────────────────────────

    /**
     * Insert a new page (DB-generated id, version 1) in the author's domain and return
     * the persisted row. The {@code domain} + {@code authorSubject} are stamped
     * SERVER-SIDE, never a request field. A SUPERADMIN ({@code domain} null/blank)
     * defaults the row to the platform {@code default} domain so a stamped page always
     * carries a concrete brand. Relies on the {@code (domain, slug)} UNIQUE constraint —
     * a duplicate slug within a brand surfaces as the driver's integrity error (the
     * service pre-checks and maps it to a 409). The {@code id} is DB-generated
     * ({@code (gen_random_uuid())::text} DEFAULT, changelog 006): the INSERT omits it and
     * {@code RETURNING id} hands back the generated id, which we re-select by.
     *
     * @param slug            the URL-stable page key; must be unique within {@code domain}
     * @param title           the page title
     * @param contentMarkdown the markdown edit source (stored as-is)
     * @param contentHtml     the rendered HTML snapshot (computed by the service before calling this)
     * @param authorSubject   the Casdoor {@code sub} of the creating principal (server-derived)
     * @param domain          the brand/tenant; null/blank SUPERADMIN callers are coerced to {@code default}
     * @return a {@link Mono} emitting the newly persisted {@link WikiPage} (version = 1)
     */
    public Mono<WikiPage> create(String slug, String title, String contentMarkdown,
                                 String contentHtml, String authorSubject, String domain) {
        String dom = DomainScope.isUnscoped(domain) ? DomainRegistry.DEFAULT_DOMAIN : domain;
        return data.insertPage(dom, slug, title, contentMarkdown, contentHtml, authorSubject)
                .flatMap(genId -> findById(genId, dom));
    }

    /**
     * Conditionally update ONE page by its primary key, bumping {@code version} — guarded
     * on {@code version = :expected} for optimistic concurrency. The update-path
     * counterpart to {@link #deleteById} (issue #15's pattern; applied here for decision
     * 59 / issue #33). The caller has ALREADY resolved the row in its own scope
     * ({@link #findBySlug}), so this takes NO domain on purpose: the other write paths
     * used to coerce an unscoped (SUPERADMIN) domain to {@code default}, and an update that
     * did the same would silently miss the resolved row after a cross-brand resolve —
     * matching zero rows and reporting a self-contradicting 409 (the row IS at the
     * expected version, just in the wrong — coerced — brand). Pinning the id means the
     * statement can only ever touch the row that was resolved.
     *
     * <p>Returns the updated row, or {@link Mono#empty()} when NO row matched (the id
     * vanished between the resolve and the update, OR a genuinely stale
     * {@code expectedVersion}). The atomic conditional UPDATE is the concurrent-edit
     * serialization point: exactly one of two racing writers at the same expected version
     * wins; the loser updates zero rows. The {@code authorSubject} is re-stamped to the
     * editing principal (server-side).
     *
     * @param id              the DB-generated UUID primary key of the page the caller resolved
     * @param title           the new page title
     * @param contentMarkdown the new markdown edit source
     * @param contentHtml     the new rendered HTML snapshot (computed by the service before calling this)
     * @param authorSubject   the Casdoor {@code sub} of the editing principal (server-derived)
     * @param expectedVersion the version the caller last read; the UPDATE is a no-op when
     *                        this differs from the stored version
     * @return a {@link Mono} emitting the updated {@link WikiPage}, or {@link Mono#empty()}
     *         when the id is gone or {@code expectedVersion} is stale
     */
    public Mono<WikiPage> updateById(String id, String title, String contentMarkdown,
                                     String contentHtml, String authorSubject,
                                     long expectedVersion) {
        return data.updateByIdGuarded(id, title, contentMarkdown, contentHtml, authorSubject, expectedVersion)
                .flatMap(updated -> updated > 0 ? findById(id, null) : Mono.empty());
    }

    /**
     * Mark ONE page as published (sets {@code is_published=true, published_at=now()}) by
     * its primary key. The publish-path counterpart to {@link #deleteById} (issue #15's
     * pattern; applied here for decision 59 / issue #33) — no domain predicate, for the
     * same reason as {@link #updateById}: the caller has already resolved the row in its
     * own scope, and pinning the id makes it structurally impossible to publish a
     * same-slug row in the wrong brand.
     *
     * @param id the DB-generated UUID primary key of the page the caller resolved
     * @return a {@link Mono} emitting the updated {@link WikiPage} (with
     *         {@code published=true} and {@code publishedAt} set), or {@link Mono#empty()}
     *         when the id is gone (the resolved row vanished concurrently)
     */
    public Mono<WikiPage> publishById(String id) {
        return data.publishById(id)
                .flatMap(updated -> updated > 0 ? findById(id, null) : Mono.empty());
    }

    /**
     * Delete ONE page by its primary key and return the deleted-row count. The caller has
     * ALREADY resolved the row in its own scope ({@link #findBySlug}), so this takes NO
     * domain on purpose: {@link #create} still coerces an unscoped (SUPERADMIN) domain to
     * {@code default} (a create has no existing row to resolve, so there is nothing to
     * pin), and a delete that did the same would target the wrong brand's row after a
     * cross-brand resolve — issue #15. {@link #updateById}/{@link #publishById} follow the
     * same id-pinned pattern (issue #33 / decision 59). Pinning the id means the statement
     * can only ever remove the row that was resolved; the {@code wiki_attachment} FK
     * ({@code ON DELETE CASCADE}, changelog 002) reaps its attachments in the same statement.
     *
     * @param id the DB-generated UUID primary key of the page the caller resolved
     * @return a {@link Mono} emitting the number of rows deleted — {@code 1}, or {@code 0} when
     *         the row vanished between the resolve and the delete (the service maps that to
     *         a 404, never a 204)
     */
    public Mono<Long> deleteById(String id) {
        return data.deletePageById(id);
    }
}
