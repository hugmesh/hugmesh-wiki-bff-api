package net.hugmesh.wikibff.wiki;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import net.hugmesh.commons.security.DomainRegistry;
import net.hugmesh.wikibff.security.PrincipalDomain;
import net.hugmesh.commons.web.ApiException;

/**
 * Reactive service for the per-page binary attachment lifecycle — list, download, upload,
 * and delete. Bridges the {@link WikiAttachmentController} to the
 * {@link WikiAttachmentRepository} and {@link WikiAuditLogRepository} (the attachment
 * sibling of {@link WikiService}).
 *
 * <p><b>Security.</b> Upload and delete are gated {@code WIKI_WRITE}
 * (ADMIN | SUPERADMIN) at the controller via {@code @PreAuthorize}; list and download are
 * gated {@code WIKI_READ_AUTHENTICATED} and additionally pass through
 * {@link net.hugmesh.wikibff.access.WikiAccessGuard} before this service is called.
 *
 * <p><b>Domain-scoped.</b> The {@code domain} is derived SERVER-SIDE from the validated
 * JWT issuer via {@link PrincipalDomain#forScope} — never a request field. A scoped ADMIN
 * of brand X can only list/download/upload/delete attachments for brand X's pages;
 * SUPERADMIN is unscoped (the cross-domain bypass).
 *
 * <p><b>Audit.</b> Upload ({@code wiki.attachment.upload}) and delete
 * ({@code wiki.attachment.delete}) are recorded to {@code wiki_audit_log}. A missing page
 * or attachment is a 404 (so a cross-brand row is invisible, not leaked as a 403).
 *
 * @see WikiAttachmentController
 * @see WikiAttachmentRepository
 * @see WikiAuditLogRepository
 */
@Service
public class WikiAttachmentService {

    private final WikiAttachmentRepository attachments;
    private final WikiPageRepository pages;
    private final WikiAuditLogRepository audit;
    private final PrincipalDomain principalDomain;

    public WikiAttachmentService(WikiAttachmentRepository attachments, WikiPageRepository pages,
                                  WikiAuditLogRepository audit, PrincipalDomain principalDomain) {
        this.attachments = attachments;
        this.pages = pages;
        this.audit = audit;
        this.principalDomain = principalDomain;
    }

    // ── public API ────────────────────────────────────────────────────

    /**
     * Lists attachment metadata (id, filename, mimeType, createdAt — no binary data)
     * for a page by slug in the caller's domain. The binary {@code data} field is
     * present on the returned records but is not projected out by the controller.
     *
     * @param slug the human-readable page slug
     * @param auth the validated JWT principal used to derive the caller's domain scope
     * @return a {@link Flux} of {@link WikiAttachment} records for the page (may be empty)
     */
    public Flux<WikiAttachment> list(String slug, Authentication auth) {
        return attachments.findAllBySlugAndDomain(slug, scopeOf(auth));
    }

    /**
     * Downloads a single attachment's raw bytes for a page by slug + filename in the
     * caller's domain. Emits an error if the attachment is not found.
     *
     * @param slug     the human-readable page slug
     * @param filename the attachment filename to retrieve
     * @param auth     the validated JWT principal used to derive the caller's domain scope
     * @return a {@link Mono} emitting the full {@link WikiAttachment} record (including
     *         the binary {@code data} field)
     * @throws ApiException (404) if no attachment with the given slug + filename exists
     *                      in the caller's domain
     */
    public Mono<WikiAttachment> download(String slug, String filename, Authentication auth) {
        return attachments.findBySlugAndFilename(slug, scopeOf(auth), filename)
                .switchIfEmpty(Mono.error(ApiException.notFound(
                        "attachment not found: " + filename + " on page " + slug)));
    }

    /**
     * Uploads a binary attachment to a page: verifies the page exists in the caller's
     * domain, inserts the attachment row, and audits the action as
     * {@code wiki.attachment.upload}.
     *
     * @param slug     the human-readable page slug identifying the target page
     * @param filename the display/download name for the attachment
     * @param mimeType the content type to serve on download
     * @param data     the raw bytes to persist as BYTEA
     * @param auth     the validated JWT principal; provides the domain scope and actor subject
     * @return a {@link Mono} emitting the persisted {@link WikiAttachment} record
     * @throws ApiException (404) if no wiki page with the given slug exists in the
     *                      caller's domain
     */
    public Mono<WikiAttachment> upload(String slug, String filename, String mimeType,
                                       byte[] data, Authentication auth) {
        String domain = scopeOf(auth);
        String actor = subject(auth);
        return pages.findBySlug(slug, domain)
                .switchIfEmpty(Mono.error(ApiException.notFound("wiki page not found: " + slug)))
                .flatMap(page -> attachments.insert(page.id(), domain, filename, mimeType, data))
                .flatMap(saved -> audit.record(actor, "wiki.attachment.upload", slug,
                        saved.filename() + " (" + saved.mimeType() + ")", domain)
                        .thenReturn(saved));
    }

    /**
     * Deletes a single attachment by slug + filename in the caller's domain, then audits
     * the action as {@code wiki.attachment.delete}. Emits an error if the attachment is
     * not found.
     *
     * @param slug     the human-readable page slug
     * @param filename the attachment filename to delete
     * @param auth     the validated JWT principal; provides the domain scope and actor subject
     * @return a {@link Mono} that completes empty on success
     * @throws ApiException (404) if no attachment with the given slug + filename exists
     *                      in the caller's domain
     */
    public Mono<Void> delete(String slug, String filename, Authentication auth) {
        String domain = scopeOf(auth);
        String actor = subject(auth);
        return attachments.deleteBySlugAndFilename(slug, domain, filename)
                .flatMap(rows -> {
                    if (rows == 0) {
                        return Mono.error(ApiException.notFound(
                                "attachment not found: " + filename + " on page " + slug));
                    }
                    return audit.record(actor, "wiki.attachment.delete", slug, filename, domain).then();
                });
    }

    /** The caller's effective domain-scope, derived SERVER-SIDE from the validated JWT
     *  issuer ({@link PrincipalDomain}): {@code null} for SUPERADMIN (cross-domain bypass),
     *  else the concrete {@code DOMAIN_<x>}; the platform {@code default} domain for a
     *  non-JWT (test) principal. NEVER read from a request field. */
    private String scopeOf(Authentication auth) {
        return principalDomain.resolveScope(auth, DomainRegistry.DEFAULT_DOMAIN);
    }

    /** The acting principal's Casdoor subject — the validated JWT {@code sub} when the
     *  principal is a {@link Jwt} (the runtime shape), else {@code auth.getName()};
     *  {@code @system} when there is no principal (defensive). NEVER read from a request
     *  body. */
    private static String subject(Authentication auth) {
        return PrincipalDomain.resolveSubject(auth, "@system");
    }
}
