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
 * The wiki domain service — owns the cross-cutting rules the controller re-checks on
 * top of the gateway/security gates (defence in depth):
 *
 * <ul>
 *   <li><b>Domain-scope</b> — the {@code wiki_page} content store + the
 *       {@code wiki_audit_log} trail are PER-BRAND. The service derives the caller's
 *       {@code domain} SERVER-SIDE from the validated-issuer brand
 *       ({@link PrincipalDomain}) and stamps/scopes those rows, so a scoped ADMIN of
 *       brand X never reads or edits brand Y's pages; SUPERADMIN is unscoped.</li>
 *   <li><b>Markdown render at write</b> — {@link #create}/{@link #update} render the
 *       authored markdown to HTML ({@link MarkdownRenderer}) at WRITE time and store
 *       BOTH, so the read path / ZIM (re)build carries no render step.</li>
 *   <li><b>Version bump + optimistic concurrency</b> — every update bumps
 *       {@code version}; a stale {@code expectedVersion} (a concurrent overwrite) is a
 *       409 rather than a silent last-writer-wins clobber.</li>
 *   <li><b>Author tracking</b> — {@code author_subject} is set from the JWT subject (the
 *       owner track), never the request body, so a caller cannot author under another
 *       subject.</li>
 *   <li><b>Audit</b> — every create/update/publish/delete is recorded to
 *       {@code wiki_audit_log} in the brand the page lives in.</li>
 * </ul>
 */
@Service
public class WikiService {

    private final WikiPageRepository pages;
    private final WikiAuditLogRepository audit;
    private final MarkdownRenderer markdown;
    private final PrincipalDomain principalDomain;

    public WikiService(WikiPageRepository pages, WikiAuditLogRepository audit,
                       MarkdownRenderer markdown, PrincipalDomain principalDomain) {
        this.pages = pages;
        this.audit = audit;
        this.markdown = markdown;
        this.principalDomain = principalDomain;
    }

    /**
     * The caller's effective domain-scope, derived SERVER-SIDE from the validated JWT
     * issuer ({@link PrincipalDomain}): {@code null} for SUPERADMIN (cross-domain
     * bypass), else the concrete {@code DOMAIN_<x>}. When the principal is not a
     * {@link Jwt} (e.g. a test stub with a non-JWT principal) we fall back to the
     * platform {@code default} domain so single-tenant/test paths are unchanged. NEVER
     * read from a request field.
     *
     * @param auth the caller's Spring Security principal
     * @return the resolved domain string ({@code null} for SUPERADMIN, {@code default}
     *         for non-JWT callers, or the concrete brand for scoped principals)
     */
    private String scopeOf(Authentication auth) {
        return principalDomain.resolveScope(auth, DomainRegistry.DEFAULT_DOMAIN);
    }

    // ── reads (domain-scoped) ──────────────────────────────────────────

    /**
     * The page list the caller may read — SCOPED to the caller's brand (a scoped ADMIN
     * sees only own-brand pages); SUPERADMIN reads every brand's. Summaries only (the
     * list view omits the heavy content columns).
     *
     * @param auth the caller's Spring Security principal
     * @return a {@link Flux} of {@link WikiDto.PageSummary} records for the caller's brand,
     *         newest-updated first
     */
    public Flux<WikiDto.PageSummary> list(Authentication auth) {
        return pages.list(scopeOf(auth)).map(WikiDto.PageSummary::of);
    }

    /**
     * The full page for a slug WITHIN the caller's brand; a 404 when the slug is absent
     * in that brand (so a cross-brand slug is invisible — not leaked as a 403).
     *
     * @param slug the URL-stable page key to look up
     * @param auth the caller's Spring Security principal
     * @return a {@link Mono} emitting the full {@link WikiDto.Page}
     * @throws ApiException 404 when the slug is not found in the caller's brand
     */
    public Mono<WikiDto.Page> get(String slug, Authentication auth) {
        return pages.findBySlug(slug, scopeOf(auth))
                .map(WikiDto.Page::of)
                .switchIfEmpty(Mono.error(ApiException.notFound("wiki page not found: " + slug)));
    }

    // ── writes (render + version + audit, domain-scoped) ───────────────

    /**
     * Create a page in the caller's brand. Renders markdown->HTML at write, stamps the
     * author from the JWT subject and the domain from the validated issuer (NEVER the
     * body), and audits {@code wiki.page.create}. A slug that already exists WITHIN the
     * brand is a 409 (the same slug may exist independently in another brand).
     *
     * @param body the create request (slug, title, contentMarkdown)
     * @param auth the caller's Spring Security principal
     * @return a {@link Mono} emitting the created {@link WikiDto.Page} (version = 1)
     * @throws ApiException 409 when a page with the same slug already exists in the caller's brand
     */
    public Mono<WikiDto.Page> create(WikiDto.CreatePage body, Authentication auth) {
        String domain = scopeOf(auth);
        String author = subject(auth);
        String html = markdown.toHtml(body.contentMarkdown());
        return pages.findBySlug(body.slug(), domain)
                .flatMap(existing -> Mono.<WikiPage>error(
                        ApiException.conflict("a wiki page with slug '" + body.slug() + "' already exists")))
                .switchIfEmpty(pages.create(body.slug(), body.title(), body.contentMarkdown(),
                        html, author, domain))
                .flatMap(saved -> audit.record(author, "wiki.page.create", saved.slug(),
                                "v" + saved.version(), domain)
                        .thenReturn(WikiDto.Page.of(saved)));
    }

    /**
     * Update a page by slug. The page is resolved in the caller's scope ({@link #scopeOf}:
     * a brand for a scoped ADMIN, the cross-brand bypass for SUPERADMIN) and then updated
     * <b>by the id of that resolved row</b> — never by slug+domain — so the update can only
     * ever touch the row the resolve found. Issue #33 / decision 59: a SUPERADMIN's
     * {@code null} scope used to be coerced to {@code default} on the update, which for a
     * page outside {@code default} matched zero rows and answered a self-contradicting 409
     * ("page is at version 1, not 1" — the secondary read that built that message was
     * ITSELF cross-brand, so it reported the resolved row's true version back as if it
     * were stale against itself).
     *
     * <p>Renders markdown-&gt;HTML at write, bumps {@code version} (guarded on
     * {@code expectedVersion} for optimistic concurrency), re-stamps the author from the
     * JWT subject, and audits {@code wiki.page.update} in the brand the page actually
     * lives in ({@code page.domain()}, never the caller's — possibly {@code null} —
     * scope). A missing slug in the caller's scope is a 404; a stale
     * {@code expectedVersion} (a concurrent overwrite, or the resolved id vanishing) is
     * distinguished by a secondary read on that SAME id:
     *
     * <pre>
     * findBySlug(slug, scope) ─ empty ──→ 404 page not found
     *                          └─ page ──→ updateById(page.id(), ..., expectedVersion)
     *                                      ├─ row ───→ audit.record(..., page.domain()) ──→ Page.of(saved)
     *                                      └─ empty ─→ findById(page.id(), null)
     *                                                  ├─ found ──→ 409 stale edit (existing.version != expected)
     *                                                  └─ empty ──→ 404 page not found (vanished concurrently)
     * </pre>
     *
     * @param slug the URL-stable page key (within the caller's brand; any brand for SUPERADMIN)
     * @param body the update request (title, contentMarkdown, expectedVersion)
     * @param auth the caller's Spring Security principal
     * @return a {@link Mono} emitting the updated {@link WikiDto.Page}
     * @throws ApiException 404 when the slug is not found in the caller's scope, or the
     *                      resolved row was gone by the time the update ran
     * @throws ApiException 409 when {@code expectedVersion} does not match the stored version
     */
    public Mono<WikiDto.Page> update(String slug, WikiDto.UpdatePage body, Authentication auth) {
        String domain = scopeOf(auth);
        String author = subject(auth);
        String html = markdown.toHtml(body.contentMarkdown());
        return pages.findBySlug(slug, domain)
                .switchIfEmpty(Mono.error(ApiException.notFound("wiki page not found: " + slug)))
                .flatMap(page -> updateResolvedPage(page, slug, body, author, html));
    }

    /**
     * The second half of {@link #update}: apply the version-guarded UPDATE to the ALREADY
     * resolved row's id, audit success in the row's own brand, and on a zero-row result
     * distinguish "id vanished" (404) from "stale version" (409) via a re-read BY THE SAME
     * ID — split out from {@link #update} purely to keep that lambda short.
     *
     * @param page   the row {@link #update} already resolved in the caller's scope
     * @param slug   the URL-stable page key (for the 404/409 messages)
     * @param body   the update request (title, contentMarkdown, expectedVersion)
     * @param author the Casdoor {@code sub} of the editing principal (server-derived)
     * @param html   the newly rendered HTML for {@code body.contentMarkdown()}
     * @return a {@link Mono} emitting the updated {@link WikiDto.Page}
     */
    private Mono<WikiDto.Page> updateResolvedPage(WikiPage page, String slug, WikiDto.UpdatePage body,
                                                   String author, String html) {
        return pages.updateById(page.id(), body.title(), body.contentMarkdown(), html,
                        author, body.expectedVersion())
                .flatMap(saved -> audit.record(author, "wiki.page.update", saved.slug(),
                                "v" + saved.version(), page.domain())
                        .thenReturn(WikiDto.Page.of(saved)))
                .switchIfEmpty(Mono.defer(() -> pages.findById(page.id(), null)
                        .flatMap(existing -> Mono.<WikiDto.Page>error(ApiException.conflict(
                                "stale edit: page '" + slug + "' is at version " + existing.version()
                                        + ", not " + body.expectedVersion())))
                        .switchIfEmpty(Mono.error(ApiException.notFound("wiki page not found: " + slug)))));
    }

    /**
     * Publish a page (mark the current content as the published snapshot the ZIM
     * (re)build packages). The page is resolved in the caller's scope ({@link #scopeOf})
     * and then published <b>by the id of that resolved row</b> — never by slug+domain —
     * so the publish can only ever touch the row the resolve found. Issue #33 / decision
     * 59: a SUPERADMIN's {@code null} scope used to be coerced to {@code default} on the
     * publish, which for a page outside {@code default} matched zero rows and answered a
     * 404 for a page the SAME caller could {@code GET} moments earlier.
     *
     * <p>The content store already holds the rendered HTML at write, so publish flips
     * {@code is_published}/{@code published_at} plus the audit record — the explicit
     * signal that the page is ready for the next ZIM rebuild (PR4, out of repo, wires the
     * actual ZIM trigger). The audit row is stamped with the brand the page actually
     * lives in ({@code page.domain()}), never the caller's — possibly {@code null} —
     * scope. A missing slug in the caller's scope is a 404; so is the resolved id
     * vanishing concurrently between the resolve and the publish.
     *
     * @param slug the URL-stable page key (within the caller's brand; any brand for SUPERADMIN)
     * @param auth the caller's Spring Security principal
     * @return a {@link Mono} emitting the published {@link WikiDto.Page} (with
     *         {@code published=true} and {@code publishedAt} set)
     * @throws ApiException 404 when the slug is not found in the caller's scope, or the
     *                      resolved row was gone by the time the publish ran
     */
    public Mono<WikiDto.Page> publish(String slug, Authentication auth) {
        String domain = scopeOf(auth);
        String actor = subject(auth);
        return pages.findBySlug(slug, domain)
                .switchIfEmpty(Mono.error(ApiException.notFound("wiki page not found: " + slug)))
                .flatMap(page -> pages.publishById(page.id())
                        .switchIfEmpty(Mono.error(ApiException.notFound("wiki page not found: " + slug)))
                        .flatMap(published -> audit.record(actor, "wiki.page.publish", published.slug(),
                                        "v" + published.version(), page.domain())
                                .thenReturn(WikiDto.Page.of(published))));
    }

    /**
     * Delete a page by slug. The page is resolved in the caller's scope ({@link #scopeOf}:
     * a brand for a scoped ADMIN, the cross-brand bypass for SUPERADMIN) and then deleted
     * <b>by the id of that resolved row</b> — never by slug+domain — so the delete can only
     * ever remove the row the resolve found. Issue #15: a SUPERADMIN's {@code null} scope
     * used to be coerced to {@code default} on the delete, which either matched nothing and
     * still answered 204, or destroyed {@code default}'s same-slug page instead — after the
     * target's attachments had already been deleted.
     *
     * <p>Attachments go with the page in the SAME statement via the {@code wiki_attachment}
     * FK {@code ON DELETE CASCADE} (changelog 002); nothing is deleted ahead of the page, so
     * a delete that matches nothing destroys nothing. A zero-row delete (the resolved row
     * vanished concurrently) is a 404, not a silent success. The audit row is stamped with
     * the brand the page actually lived in ({@code page.domain()}), never the caller's scope
     * (which is {@code null} — coerced to {@code default} — for a SUPERADMIN).
     *
     * <pre>
     * findBySlug(slug, scope) ─ empty ──→ 404 page not found
     *                          └─ page ──→ deleteById(page.id())
     *                                      ├─ 0 rows ──→ 404 (vanished; nothing audited)
     *                                      └─ 1 row ───→ audit.record(..., page.domain())
     * </pre>
     *
     * @param slug the URL-stable page key (within the caller's brand; any brand for SUPERADMIN)
     * @param auth the caller's Spring Security principal
     * @return a {@link Mono} that completes empty once exactly one row was removed
     * @throws ApiException 404 when the slug is not found in the caller's scope, or when the
     *                      resolved row was gone by the time the delete ran
     */
    public Mono<Void> delete(String slug, Authentication auth) {
        String domain = scopeOf(auth);
        String actor = subject(auth);
        return pages.findBySlug(slug, domain)
                .switchIfEmpty(Mono.error(ApiException.notFound("wiki page not found: " + slug)))
                .flatMap(page -> pages.deleteById(page.id())
                        .filter(deleted -> deleted > 0)
                        .switchIfEmpty(Mono.error(ApiException.notFound("wiki page not found: " + slug)))
                        .flatMap(deleted -> audit.record(actor, "wiki.page.delete", slug,
                                "deleted page and its attachments", page.domain())))
                .then();
    }

    /**
     * The audit trail the caller may read — SCOPED to the caller's brand; SUPERADMIN
     * reads every brand's. The domain is server-derived, never a request field. The
     * {@code limit} is a BOUND on the newest-first page size, clamped to
     * {@code [1, MAX_LIMIT]} (a non-positive value ⇒ the default) in the repository so
     * the read can never drain the unbounded {@code wiki_audit_log} table.
     *
     * @param auth  the caller's Spring Security principal
     * @param limit the requested page size; clamped in the repository, non-positive
     *              values fall back to the default
     * @return a {@link Flux} of {@link WikiAuditLog} entries for the caller's brand,
     *         newest first
     */
    public Flux<WikiAuditLog> auditTrail(Authentication auth, int limit) {
        return audit.list(scopeOf(auth), limit);
    }

    // ── helpers ────────────────────────────────────────────────────────

    /**
     * The acting principal's Casdoor subject (the author/owner key) — read from the
     * validated JWT {@code sub} when the principal is a {@link Jwt} (the runtime shape;
     * also the robust path since {@code Authentication.getName()} depends on the token
     * type), else {@code auth.getName()}. {@code @system} when there is no principal
     * (defensive; e.g. a seed job). NEVER read from a request body.
     *
     * @param auth the caller's Spring Security principal; may be {@code null}
     * @return the Casdoor subject string, or {@code @system} when the principal is absent
     */
    private static String subject(Authentication auth) {
        return PrincipalDomain.resolveSubject(auth, "@system");
    }
}
