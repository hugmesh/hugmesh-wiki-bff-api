package systems.hugmesh.wikibff.wiki;

import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import systems.hugmesh.commons.security.DomainRegistry;
import systems.hugmesh.commons.tenancy.DomainScope;

/**
 * Domain-scoped store for the {@code wiki_attachment} table (Liquibase {@code 002}).
 * Delegates to the Spring Data {@link WikiAttachmentDataRepository} (the reactive-repository
 * idiom, matching the hugmesh-federation-api exemplar): DB-generated ids (the
 * {@code wiki_attachment.id} column carries a {@code (gen_random_uuid())::text} DEFAULT —
 * changelog 006; the INSERT omits {@code id} and reads it back via {@code RETURNING id}); the
 * slug-based finders JOIN {@code wiki_page} to locate attachments by the page's
 * human-readable slug.
 *
 * <p>The {@code domain} column is denormalized from the owning page so attachment reads can
 * be domain-scoped without a secondary page join. A {@code null} domain is the SUPERADMIN
 * cross-domain bypass (the un-pinned join variants). BYTEA is retrieved as {@code byte[]}.
 */
@Repository
public class WikiAttachmentRepository {

    private final WikiAttachmentDataRepository data;

    public WikiAttachmentRepository(WikiAttachmentDataRepository data) {
        this.data = data;
    }

    // ── reads ─────────────────────────────────────────────────────────

    /**
     * All attachments for a page identified by slug WITHIN a brand. Joins
     * {@code wiki_page} to resolve the slug; {@code null} domain is the SUPERADMIN
     * cross-domain bypass (no domain predicate — returns any brand's attachments for
     * that slug, first page by id).
     *
     * @param slug   the human-readable page slug to look up
     * @param domain the brand/tenant to scope the query to, or {@code null} / an unscoped
     *               sentinel for SUPERADMIN cross-domain access
     * @return a {@link Flux} of matching {@link WikiAttachment} records (may be empty)
     */
    public Flux<WikiAttachment> findAllBySlugAndDomain(String slug, String domain) {
        return DomainScope.isUnscoped(domain)
                ? data.findAllBySlug(slug)
                : data.findAllBySlugAndDomain(slug, domain);
    }

    /**
     * One attachment for a page identified by slug + filename WITHIN a brand. Returns
     * {@link Mono#empty()} when not found.
     *
     * @param slug     the human-readable page slug
     * @param domain   the brand/tenant scope, or {@code null} / unscoped for SUPERADMIN
     * @param filename the attachment filename to match
     * @return a {@link Mono} emitting the matching {@link WikiAttachment}, or
     *         {@link Mono#empty()} if no matching row exists
     */
    public Mono<WikiAttachment> findBySlugAndFilename(String slug, String domain, String filename) {
        return DomainScope.isUnscoped(domain)
                ? data.findBySlugAndFilename(slug, filename)
                : data.findBySlugFilenameAndDomain(slug, filename, domain);
    }

    // ── writes ─────────────────────────────────────────────────────────

    /**
     * Delete all attachments belonging to a page (cascade helper — called when a page
     * is deleted so the audit trail can reflect the count). Returns the number of rows
     * deleted. The DB FK ON DELETE CASCADE also covers this, but an explicit delete
     * here lets the service audit the count.
     *
     * @param pageId the UUID primary key of the owning wiki page
     * @return a {@link Mono} emitting the number of rows deleted (zero or more)
     */
    public Mono<Long> deleteByPageId(String pageId) {
        return data.deleteByPageId(pageId);
    }

    /**
     * Delete a single attachment identified by page slug + filename WITHIN a brand.
     * Uses a subquery to locate the attachment id via a join to {@code wiki_page}.
     * Returns the number of deleted rows (0 = not found).
     *
     * @param slug     the human-readable page slug
     * @param domain   the brand/tenant scope; if unscoped, defaults to
     *                 {@link DomainRegistry#DEFAULT_DOMAIN}
     * @param filename the attachment filename to delete
     * @return a {@link Mono} emitting the number of rows deleted (0 if not found, 1 if deleted)
     */
    public Mono<Long> deleteBySlugAndFilename(String slug, String domain, String filename) {
        String dom = DomainScope.isUnscoped(domain) ? DomainRegistry.DEFAULT_DOMAIN : domain;
        return data.deleteBySlugFilenameAndDomain(slug, filename, dom);
    }

    /**
     * Insert a new attachment (DB-generated id) and return the persisted row by
     * re-selecting it. The {@code domain} is stamped SERVER-SIDE from the owning page's
     * domain (derived from the caller's validated JWT issuer), never a request field. The
     * {@code id} is DB-generated ({@code (gen_random_uuid())::text} DEFAULT, changelog 006):
     * the INSERT omits it and {@code RETURNING id} hands back the generated id, which we
     * re-select by.
     *
     * @param pageId   the UUID primary key of the owning wiki page
     * @param domain   the brand/tenant to stamp on the attachment; if unscoped, defaults
     *                 to {@link DomainRegistry#DEFAULT_DOMAIN}
     * @param filename the display/download name for the attachment
     * @param mimeType the content type to serve on download
     * @param data     the raw bytes to store as BYTEA; see the size concern note on
     *                 {@link WikiAttachment} for heap implications
     * @return a {@link Mono} emitting the fully persisted {@link WikiAttachment} row
     *         (including the server-assigned id and created_at timestamp)
     */
    public Mono<WikiAttachment> insert(String pageId, String domain, String filename,
                                       String mimeType, byte[] data) {
        String dom = DomainScope.isUnscoped(domain) ? DomainRegistry.DEFAULT_DOMAIN : domain;
        return this.data.insertAttachment(pageId, dom, filename, mimeType, data)
                .flatMap(this.data::findById);
    }
}
