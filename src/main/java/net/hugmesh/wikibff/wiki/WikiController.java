package net.hugmesh.wikibff.wiki;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import net.hugmesh.wikibff.access.WikiAccessGuard;

import java.util.Map;

/**
 * The wiki BFF surface ({@code /bff/wiki/**}) — TIER 1 (WRITE) of the 3-tier wiki. The
 * read floor is enforced at the fe-gateway (route-access ROLE_WIKI); SecurityConfig
 * gates the paths ({@code /bff/wiki/admin/**} ADMIN, {@code /bff/wiki/superadmin/**}
 * SUPERADMIN, the rest authenticated); and this controller re-checks the exact role
 * composites at the METHOD level via {@code @PreAuthorize} (defence in depth), including
 * the {@code MOD AND WIKI} AND-of-roles read gate that a path-level {@code hasAnyRole}
 * cannot express.
 *
 * <p>Wrap-per-role-and-action (the role cascade SUPERADMIN&gt;ADMIN&gt;MOD&gt;USER):
 * <ul>
 *   <li>READ (list/get) — {@link #WIKI_READ} = ADMIN | SUPERADMIN | (MOD AND WIKI);</li>
 *   <li>EDIT (create/update) + PUBLISH — {@link #WIKI_WRITE} = ADMIN | SUPERADMIN; the
 *       write renders markdown-&gt;HTML and bumps the page version;</li>
 *   <li>MANAGE — {@link #WIKI_MANAGE} = SUPERADMIN (the audit-trail read here; the
 *       settings + ZIM-rebuild trigger arrive in PR4).</li>
 * </ul>
 * Every surface is DOMAIN-SCOPED in the service: a scoped ADMIN sees only own-brand
 * pages; SUPERADMIN is the cross-domain bypass.
 */
@Tag(name = "Wiki", description = "Wiki BFF (TIER 1 content store): list/get (WIKI_READ), "
        + "create/update/publish (WIKI_WRITE), audit (WIKI_MANAGE). Domain-scoped per brand.")
@RestController
@RequestMapping("/bff/wiki")
@Validated
public class WikiController {

    /** WIKI_READ — the role cascade's default read set: any ADMIN or SUPERADMIN, OR a
     *  MOD who ALSO carries the per-service ROLE_WIKI (the AND a path-level hasAnyRole
     *  cannot express). Regular USERs reach read only via a grant (the wiki_access
     *  overlay, PR3).
     *
     *  <p><b>This static composite is NO LONGER the method gate on the read endpoints</b>
     *  (PR3): a USER who reaches read via a wiki_access GRANT carries NONE of these roles,
     *  so a static {@code @PreAuthorize(WIKI_READ)} would reject them BEFORE the overlay is
     *  consulted. The read endpoints therefore gate on {@link #WIKI_READ_AUTHENTICATED}
     *  (the gateway already enforces the ROLE_WIKI floor) and delegate the REAL decision —
     *  THIS exact composite OR an active grant, AND NOT an active ban, SUPERADMIN exempt —
     *  to {@link net.hugmesh.wikibff.access.WikiAccessGuard}, which re-implements
     *  this expression in code. The constant is retained as the canonical statement of the
     *  role half (and is still used by the write/manage gates' siblings). */
    public static final String WIKI_READ =
            "hasRole('ADMIN') or hasRole('SUPERADMIN') or (hasRole('MOD') and hasRole('WIKI'))";

    /** The METHOD gate on the read endpoints: authenticated only. The ROLE_WIKI route-access
     *  floor (admin|superadmin|mod+ROLE_WIKI, plus a granted USER's ROLE_WIKI) is enforced at
     *  the gateway; the EXACT read decision — the {@link #WIKI_READ} role composite OR an
     *  active wiki_access grant, AND NOT an active ban/timeout, with SUPERADMIN exempt — is
     *  enforced programmatically by {@link net.hugmesh.wikibff.access.WikiAccessGuard}
     *  so the dynamic overlay (which a static SpEL cannot express) is the load-bearing gate. */
    public static final String WIKI_READ_AUTHENTICATED = "isAuthenticated()";

    /** WIKI_WRITE — authoring (create/update/publish): ADMIN or SUPERADMIN only. */
    public static final String WIKI_WRITE = "hasRole('ADMIN') or hasRole('SUPERADMIN')";

    /** WIKI_MANAGE — manage-all features of the wiki solution: SUPERADMIN only. */
    public static final String WIKI_MANAGE = "hasRole('SUPERADMIN')";

    private final WikiService wiki;
    private final WikiAccessGuard accessGuard;

    /**
     * @param wiki        the wiki domain service (content store + audit)
     * @param accessGuard the programmatic read gate (grant/ban overlay on top of the role floor)
     */
    public WikiController(WikiService wiki, WikiAccessGuard accessGuard) {
        this.wiki = wiki;
        this.accessGuard = accessGuard;
    }

    // ── READ (WIKI_READ — enforced by WikiAccessGuard, NOT a static SpEL) ──
    // The method gate is WIKI_READ_AUTHENTICATED (authenticated only); the ROLE_WIKI floor
    // is at the gateway. The REAL read decision — the WIKI_READ role composite OR an active
    // wiki_access GRANT, AND NOT an active BAN/TIMEOUT, with SUPERADMIN exempt — is enforced
    // programmatically by accessGuard.assertCanRead(auth) BEFORE the read runs. This is the
    // load-bearing wiring that makes the wiki_access overlay NOT inert: a grant LETS a plain
    // USER in (a static @PreAuthorize(WIKI_READ) would have rejected them first), and a ban
    // KEEPS a role-holder/grantee out (a static gate could never deny them). See
    // WikiAccessGuard for the precedence.

    /**
     * List wiki page summaries (no heavy content columns) in the caller's brand.
     * The REAL read gate is {@link WikiAccessGuard} (ADMIN | SUPERADMIN | (MOD+WIKI) or an
     * active grant, AND NOT an active ban). The gateway enforces the ROLE_WIKI route-access
     * floor.
     *
     * @param auth the caller's Spring Security principal (injected by the framework)
     * @return a {@link Mono} emitting a {@link WikiDto.PageList} of summaries for the caller's brand
     */
    @Operation(
            operationId = "wikiListPages",
            summary = "List wiki pages (summaries) in the caller's brand.",
            description = "List wiki page summaries (no heavy content columns) in the caller's brand. "
                    + "The REAL read gate is WikiAccessGuard (ADMIN | SUPERADMIN | (MOD+WIKI) or an "
                    + "active grant, AND NOT an active ban). The gateway enforces the ROLE_WIKI "
                    + "route-access floor.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of page summaries for the caller's brand."),
            @ApiResponse(responseCode = "401", description = "No valid JWT presented.", content = @Content),
            @ApiResponse(responseCode = "403", description = "Caller is banned or does not hold a qualifying role or grant.", content = @Content),
            @ApiResponse(responseCode = "500", description = "Unexpected server error.", content = @Content)
    })
    @PreAuthorize(WIKI_READ_AUTHENTICATED)
    @GetMapping("/pages")
    public Mono<WikiDto.PageList> listPages(Authentication auth) {
        // Mono.defer so the read is only assembled/subscribed AFTER the guard allows — a
        // denied request never touches the content store.
        return accessGuard.assertCanRead(auth)
                .then(Mono.defer(() -> wiki.list(auth).collectList().map(WikiDto.PageList::new)));
    }

    /**
     * Get a wiki page (markdown source + rendered HTML) by slug in the caller's brand.
     * The same {@link WikiAccessGuard} gate as {@link #listPages} applies: ADMIN | SUPERADMIN |
     * (MOD+WIKI) or an active grant, AND NOT an active ban.
     *
     * @param slug the URL-stable page key within the caller's brand
     * @param auth the caller's Spring Security principal (injected by the framework)
     * @return a {@link Mono} emitting the full {@link WikiDto.Page}, or a 404 error signal
     *         when the slug is absent in the caller's brand
     */
    @Operation(
            operationId = "wikiGetPage",
            summary = "Get a wiki page (markdown + rendered HTML) by slug in the caller's brand.",
            description = "Get a wiki page (markdown source + rendered HTML) by slug in the caller's "
                    + "brand. Same WikiAccessGuard gate as listPages.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Full page (markdown source + rendered HTML snapshot)."),
            @ApiResponse(responseCode = "401", description = "No valid JWT presented.", content = @Content),
            @ApiResponse(responseCode = "403", description = "Caller is banned or does not hold a qualifying role or grant.", content = @Content),
            @ApiResponse(responseCode = "404", description = "Page not found in the caller's brand.", content = @Content),
            @ApiResponse(responseCode = "500", description = "Unexpected server error.", content = @Content)
    })
    @PreAuthorize(WIKI_READ_AUTHENTICATED)
    @GetMapping("/pages/{slug}")
    public Mono<WikiDto.Page> getPage(@PathVariable String slug, Authentication auth) {
        return accessGuard.assertCanRead(auth).then(Mono.defer(() -> wiki.get(slug, auth)));
    }

    // ── EDIT (WIKI_WRITE) ──────────────────────────────────────────────

    /**
     * Create a wiki page (renders markdown to HTML at write, version 1). The slug must be
     * unique within the caller's brand. ADMIN/SUPERADMIN only.
     *
     * @param body the create request (slug, title, contentMarkdown); author + domain are
     *             stamped server-side from the JWT, never the body
     * @param auth the caller's Spring Security principal (injected by the framework)
     * @return a {@link Mono} emitting a 200 response wrapping the created {@link WikiDto.Page}
     */
    @Operation(
            operationId = "wikiCreatePage",
            summary = "Create a wiki page (renders markdown->HTML, version 1). ADMIN/SUPERADMIN.",
            description = "Create a wiki page (renders markdown to HTML at write, version 1). "
                    + "The slug must be unique within the caller's brand. ADMIN/SUPERADMIN only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Created page (markdown source + rendered HTML snapshot, version 1)."),
            @ApiResponse(responseCode = "400", description = "Validation error — slug, title, or contentMarkdown failed a constraint.", content = @Content),
            @ApiResponse(responseCode = "401", description = "No valid JWT presented.", content = @Content),
            @ApiResponse(responseCode = "403", description = "Caller is not ADMIN or SUPERADMIN.", content = @Content),
            @ApiResponse(responseCode = "409", description = "Slug already exists in the caller's brand.", content = @Content),
            @ApiResponse(responseCode = "500", description = "Unexpected server error.", content = @Content)
    })
    @PreAuthorize(WIKI_WRITE)
    @PostMapping("/admin/pages")
    public Mono<ResponseEntity<WikiDto.Page>> createPage(@Valid @RequestBody WikiDto.CreatePage body,
                                                         Authentication auth) {
        return wiki.create(body, auth).map(ResponseEntity::ok);
    }

    /**
     * Update a wiki page by slug (renders markdown to HTML at write, bumps version). A
     * stale {@code expectedVersion} (a concurrent overwrite) is a 409. ADMIN/SUPERADMIN only.
     *
     * @param slug the URL-stable page key within the caller's brand
     * @param body the update request (title, contentMarkdown, expectedVersion)
     * @param auth the caller's Spring Security principal (injected by the framework)
     * @return a {@link Mono} emitting the updated {@link WikiDto.Page}
     */
    @Operation(
            operationId = "wikiUpdatePage",
            summary = "Update a wiki page by slug (renders markdown->HTML, bumps version; "
                    + "stale expectedVersion -> 409). ADMIN/SUPERADMIN.",
            description = "Update a wiki page by slug (renders markdown to HTML at write, bumps "
                    + "version). A stale expectedVersion (a concurrent overwrite) is a 409. "
                    + "ADMIN/SUPERADMIN only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated page (markdown source + rendered HTML snapshot, bumped version)."),
            @ApiResponse(responseCode = "400", description = "Validation error — title or contentMarkdown failed a constraint.", content = @Content),
            @ApiResponse(responseCode = "401", description = "No valid JWT presented.", content = @Content),
            @ApiResponse(responseCode = "403", description = "Caller is not ADMIN or SUPERADMIN.", content = @Content),
            @ApiResponse(responseCode = "404", description = "Slug not found in the caller's brand.", content = @Content),
            @ApiResponse(responseCode = "409", description = "Stale expectedVersion — the page was concurrently modified.", content = @Content),
            @ApiResponse(responseCode = "500", description = "Unexpected server error.", content = @Content)
    })
    @PreAuthorize(WIKI_WRITE)
    @PutMapping("/admin/pages/{slug}")
    public Mono<WikiDto.Page> updatePage(@PathVariable String slug,
                                         @Valid @RequestBody WikiDto.UpdatePage body,
                                         Authentication auth) {
        return wiki.update(slug, body, auth);
    }

    /**
     * Mark a wiki page as published (sets {@code is_published=true, published_at=now()}).
     * Audited; the next ZIM rebuild will package published pages. ADMIN/SUPERADMIN only.
     *
     * @param slug the URL-stable page key within the caller's brand
     * @param auth the caller's Spring Security principal (injected by the framework)
     * @return a {@link Mono} emitting a 200 response with a confirmation body containing
     *         {@code state}, {@code slug}, and {@code version}
     */
    @Operation(
            operationId = "wikiPublishPage",
            summary = "Publish a wiki page by slug (audited; the next ZIM rebuild packages it). "
                    + "ADMIN/SUPERADMIN.",
            description = "Mark a wiki page as published (sets is_published=true, published_at=now()). "
                    + "Audited; the next ZIM rebuild will package published pages. ADMIN/SUPERADMIN only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Confirmation body with state/slug/version."),
            @ApiResponse(responseCode = "401", description = "No valid JWT presented.", content = @Content),
            @ApiResponse(responseCode = "403", description = "Caller is not ADMIN or SUPERADMIN.", content = @Content),
            @ApiResponse(responseCode = "404", description = "Slug not found in the caller's brand.", content = @Content),
            @ApiResponse(responseCode = "500", description = "Unexpected server error.", content = @Content)
    })
    @PreAuthorize(WIKI_WRITE)
    @PostMapping("/admin/pages/{slug}/publish")
    public Mono<ResponseEntity<Map<String, Object>>> publishPage(@PathVariable String slug,
                                                                 Authentication auth) {
        return wiki.publish(slug, auth)
                .map(page -> ResponseEntity.ok(Map.<String, Object>of(
                        "state", "published", "slug", page.slug(), "version", page.version())));
    }

    /**
     * Delete a wiki page by slug in the caller's brand. Cascades to its attachments.
     * Audited. ADMIN/SUPERADMIN only.
     *
     * @param slug the URL-stable page key within the caller's brand
     * @param auth the caller's Spring Security principal (injected by the framework)
     * @return a {@link Mono} emitting a 204 No Content response on success
     */
    @Operation(
            operationId = "wikiDeletePage",
            summary = "Delete a wiki page by slug (cascades to its attachments; audited). ADMIN/SUPERADMIN.",
            description = "Delete a wiki page by slug in the caller's brand. Cascades to its "
                    + "attachments. Audited. ADMIN/SUPERADMIN only.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Page (and its attachments) deleted."),
            @ApiResponse(responseCode = "401", description = "No valid JWT presented.", content = @Content),
            @ApiResponse(responseCode = "403", description = "Caller is not ADMIN or SUPERADMIN.", content = @Content),
            @ApiResponse(responseCode = "404", description = "Slug not found in the caller's brand.", content = @Content),
            @ApiResponse(responseCode = "500", description = "Unexpected server error.", content = @Content)
    })
    @PreAuthorize(WIKI_WRITE)
    @DeleteMapping("/admin/pages/{slug}")
    public Mono<ResponseEntity<Void>> deletePage(@PathVariable String slug, Authentication auth) {
        return wiki.delete(slug, auth).thenReturn(ResponseEntity.<Void>noContent().build());
    }

    // ── MANAGE (WIKI_MANAGE) ───────────────────────────────────────────

    /**
     * Read the {@code wiki_audit_log} content-edit trail (scoped to the caller's brand;
     * SUPERADMIN sees all brands). Returns newest-first. Bounded: the optional
     * {@code ?limit} is clamped to {@code [1, 1000]} and defaults to 200 — the read is a
     * bounded newest-first scan, never a full-table drain. SUPERADMIN only.
     *
     * @param limit optional page size; clamped to {@code [1, 1000]}, defaults to 200 when unset
     * @param auth  the caller's Spring Security principal (injected by the framework)
     * @return a {@link Mono} emitting a map with key {@code "actions"} containing the
     *         list of {@link WikiAuditLog} entries, newest first
     */
    @Operation(
            operationId = "wikiAuditTrail",
            summary = "Read the wiki_audit_log content-edit trail (newest first, scoped to the "
                    + "caller's brand; SUPERADMIN sees all). Bounded: the optional ?limit is "
                    + "clamped to [1, 1000] and defaults to 200. SUPERADMIN.",
            description = "Read the wiki_audit_log content-edit trail (scoped to the caller's brand; "
                    + "SUPERADMIN sees all brands). Returns newest-first. Bounded: the optional "
                    + "?limit is clamped to [1, 1000] and defaults to 200 — the read is a bounded "
                    + "newest-first scan, never a full-table drain. SUPERADMIN only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of audit entries, newest first (bounded page)."),
            @ApiResponse(responseCode = "401", description = "No valid JWT presented.", content = @Content),
            @ApiResponse(responseCode = "403", description = "Caller is not SUPERADMIN.", content = @Content),
            @ApiResponse(responseCode = "500", description = "Unexpected server error.", content = @Content)
    })
    @PreAuthorize(WIKI_MANAGE)
    @GetMapping("/superadmin/audit")
    public Mono<Map<String, Object>> auditTrail(
            @RequestParam(name = "limit", required = false) Integer limit,
            Authentication auth) {
        // A null (unset) limit ⇒ 0, which the repository clamp turns into DEFAULT_LIMIT; a
        // supplied limit is clamped to [1, MAX_LIMIT] there. The newest-first ORDER BY + LIMIT
        // makes the read a bounded scan, never a full-table drain of wiki_audit_log.
        int requested = limit == null ? 0 : limit;
        return wiki.auditTrail(auth, requested).collectList().map(a -> Map.of("actions", a));
    }
}
