package systems.hugmesh.wikibff.access;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import systems.hugmesh.wikibff.wiki.WikiController;

import java.util.Map;

/**
 * The grant / timeout / ban OVERLAY surface — the write side of the {@code wiki_access}
 * desired-state that {@link WikiAccessGuard} enforces on the read path. CLONED in role
 * model from the admin-bff suspension endpoints.
 *
 * <p>ROLE-GATE (matching the wiki moderation model):
 * <ul>
 *   <li>grant / timeout / ban / restore (revoke) — {@link WikiController#WIKI_WRITE}
 *       (ADMIN | SUPERADMIN). Mounted under {@code /bff/wiki/admin/access/**} so the
 *       SecurityConfig path gate is ADMIN|SUPERADMIN end-to-end AND the method-level
 *       {@code @PreAuthorize} re-checks (defence in depth). An ADMIN acts only WITHIN
 *       their own brand (server-derived domain); SUPERADMIN is cross-domain.</li>
 *   <li>list-all / history (manage-all) — {@link WikiController#WIKI_MANAGE} (SUPERADMIN),
 *       mounted under {@code /bff/wiki/superadmin/access} so the path gate is SUPERADMIN.</li>
 * </ul>
 * Every write is DOMAIN-SCOPED in the service; {@code state=revoked} rows are RETAINED
 * (audit), never hard-deleted.
 */
@Tag(name = "Wiki Access", description = "Wiki grant/timeout/ban overlay (wiki_access): "
        + "grant/timeout/ban/restore (WIKI_WRITE), list-all/history (WIKI_MANAGE). "
        + "Domain-scoped; enforced at request time by WikiAccessGuard.")
@RestController
@RequestMapping("/bff/wiki")
@Validated
public class WikiAccessController {

    private final WikiAccessService access;

    /**
     * Constructs the controller with its sole dependency.
     *
     * @param access the service that owns all {@code wiki_access} read/write logic
     */
    public WikiAccessController(WikiAccessService access) {
        this.access = access;
    }

    // ── grant / timeout / ban / restore (WIKI_WRITE) ───────────────────

    @Operation(
            operationId = "wikiAccessGrant",
            summary = "GRANT a USER permanent wiki read access (an active wiki_access "
                    + "read override). ADMIN/SUPERADMIN, own-brand.",
            description = "Creates (or returns existing active) a {@code read} override row for the "
                    + "target user in the actor's brand. Idempotent: if an active {@code read} row "
                    + "already exists for this user in this brand it is returned unchanged."
                    + "<table>"
                    + "<tr><th>Role</th><th>Permitted</th><th>Scope</th></tr>"
                    + "<tr><td>ADMIN</td><td>Yes</td><td>Own brand only</td></tr>"
                    + "<tr><td>SUPERADMIN</td><td>Yes</td><td>Cross-domain (platform default)</td></tr>"
                    + "<tr><td>MOD / USER</td><td>No</td><td>—</td></tr>"
                    + "</table>")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Active wiki_access read override returned "
                    + "(newly created or pre-existing idempotent row)."),
            @ApiResponse(responseCode = "400", description = "Request body validation failed "
                    + "(e.g. missing or blank userSubject).", content = @Content),
            @ApiResponse(responseCode = "401", description = "No valid JWT present in the request.", content = @Content),
            @ApiResponse(responseCode = "403", description = "Caller does not hold ADMIN or SUPERADMIN role.", content = @Content),
            @ApiResponse(responseCode = "500", description = "Unexpected DB error writing the override row.", content = @Content)
    })
    @PreAuthorize(WikiController.WIKI_WRITE)
    @PostMapping("/admin/access/grant")
    public Mono<ResponseEntity<WikiAccessDto.Access>> grant(
            @Valid @RequestBody WikiAccessDto.GrantOrBan body, Authentication auth) {
        return access.grant(body.userSubject(), body.reason(), body.targetDomain(), auth)
                .map(a -> ResponseEntity.ok(WikiAccessDto.Access.of(a)));
    }

    @Operation(
            operationId = "wikiAccessTimeout",
            summary = "TIMEOUT a USER from wiki read for a bounded window (an active "
                    + "banned override with a future expires_at; auto-restores on expiry). "
                    + "ADMIN/SUPERADMIN, own-brand.",
            description = "Creates (or returns existing active) a {@code banned} override row with a "
                    + "future {@code expires_at} computed from {@code ttlSeconds}. Once the timestamp "
                    + "passes, {@link WikiAccessGuard} treats the row as inactive — no write is needed "
                    + "on expiry. Idempotent: an existing active {@code banned} row is returned unchanged."
                    + "<table>"
                    + "<tr><th>Role</th><th>Permitted</th><th>Scope</th></tr>"
                    + "<tr><td>ADMIN</td><td>Yes</td><td>Own brand only</td></tr>"
                    + "<tr><td>SUPERADMIN</td><td>Yes</td><td>Cross-domain (platform default)</td></tr>"
                    + "<tr><td>MOD / USER</td><td>No</td><td>—</td></tr>"
                    + "</table>")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Active wiki_access banned override returned "
                    + "(newly created or pre-existing idempotent row)."),
            @ApiResponse(responseCode = "400", description = "Request body validation failed "
                    + "(e.g. missing userSubject or non-positive ttlSeconds).", content = @Content),
            @ApiResponse(responseCode = "401", description = "No valid JWT present in the request.", content = @Content),
            @ApiResponse(responseCode = "403", description = "Caller does not hold ADMIN or SUPERADMIN role.", content = @Content),
            @ApiResponse(responseCode = "500", description = "Unexpected DB error writing the override row.", content = @Content)
    })
    @PreAuthorize(WikiController.WIKI_WRITE)
    @PostMapping("/admin/access/timeout")
    public Mono<ResponseEntity<WikiAccessDto.Access>> timeout(
            @Valid @RequestBody WikiAccessDto.Timeout body, Authentication auth) {
        return access.timeout(body.userSubject(), body.ttlSeconds(), body.reason(),
                        body.targetDomain(), auth)
                .map(a -> ResponseEntity.ok(WikiAccessDto.Access.of(a)));
    }

    @Operation(
            operationId = "wikiAccessBan",
            summary = "BAN a USER from wiki read permanently (an active banned override, "
                    + "expires_at NULL). Denies even a role-holder/grantee. ADMIN/SUPERADMIN, own-brand.",
            description = "Creates (or returns existing active) a permanent {@code banned} override row "
                    + "({@code expires_at} NULL). The ban takes precedence over any role-based read "
                    + "permission; {@link WikiAccessGuard} denies access on every request until the row "
                    + "is revoked. Idempotent: an existing active {@code banned} row is returned unchanged."
                    + "<table>"
                    + "<tr><th>Role</th><th>Permitted</th><th>Scope</th></tr>"
                    + "<tr><td>ADMIN</td><td>Yes</td><td>Own brand only</td></tr>"
                    + "<tr><td>SUPERADMIN</td><td>Yes</td><td>Cross-domain (platform default)</td></tr>"
                    + "<tr><td>MOD / USER</td><td>No</td><td>—</td></tr>"
                    + "</table>")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Active wiki_access banned override returned "
                    + "(newly created or pre-existing idempotent row)."),
            @ApiResponse(responseCode = "400", description = "Request body validation failed "
                    + "(e.g. missing or blank userSubject).", content = @Content),
            @ApiResponse(responseCode = "401", description = "No valid JWT present in the request.", content = @Content),
            @ApiResponse(responseCode = "403", description = "Caller does not hold ADMIN or SUPERADMIN role.", content = @Content),
            @ApiResponse(responseCode = "500", description = "Unexpected DB error writing the override row.", content = @Content)
    })
    @PreAuthorize(WikiController.WIKI_WRITE)
    @PostMapping("/admin/access/ban")
    public Mono<ResponseEntity<WikiAccessDto.Access>> ban(
            @Valid @RequestBody WikiAccessDto.GrantOrBan body, Authentication auth) {
        return access.ban(body.userSubject(), body.reason(), body.targetDomain(), auth)
                .map(a -> ResponseEntity.ok(WikiAccessDto.Access.of(a)));
    }

    @Operation(
            operationId = "wikiAccessRevoke",
            summary = "RESTORE/REVOKE: lift the active override of a type for a user "
                    + "(CAS active->revoked; the row is retained = audit). accessType = read|banned: "
                    + "revoke 'banned' to restore read; revoke 'read' to remove a grant. "
                    + "ADMIN/SUPERADMIN, own-brand.",
            description = "CAS-transitions the active override of the given {@code accessType} to "
                    + "{@code revoked} state. The row is never deleted (audit retention). If no active "
                    + "row of that type exists for the user in the actor's brand the call is a no-op and "
                    + "still returns 200. {@code accessType} must be exactly {@code read} or "
                    + "{@code banned}; any other value yields 400."
                    + "<table>"
                    + "<tr><th>Role</th><th>Permitted</th><th>Scope</th></tr>"
                    + "<tr><td>ADMIN</td><td>Yes</td><td>Own brand only</td></tr>"
                    + "<tr><td>SUPERADMIN</td><td>Yes</td><td>Cross-domain (platform default)</td></tr>"
                    + "<tr><td>MOD / USER</td><td>No</td><td>—</td></tr>"
                    + "</table>")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Override revoked (or was already absent — "
                    + "no-op). Body contains state=revoked, accessType, userSubject."),
            @ApiResponse(responseCode = "400", description = "accessType path variable is not 'read' or 'banned'.", content = @Content),
            @ApiResponse(responseCode = "401", description = "No valid JWT present in the request.", content = @Content),
            @ApiResponse(responseCode = "403", description = "Caller does not hold ADMIN or SUPERADMIN role.", content = @Content),
            @ApiResponse(responseCode = "500", description = "Unexpected DB error during the CAS update.", content = @Content)
    })
    @PreAuthorize(WikiController.WIKI_WRITE)
    @DeleteMapping("/admin/access/{accessType}/{userSubject}")
    public Mono<ResponseEntity<Map<String, Object>>> revoke(
            @PathVariable String accessType, @PathVariable String userSubject, Authentication auth) {
        // Validate the path token against the closed read|banned vocabulary via the
        // AccessType lens (replaces the hand-rolled read|banned||null ternary); an
        // unknown token is rejected 400 exactly as before. The canonical lowercase
        // value() is handed to the service, so the stored/transmitted token is identical.
        AccessType kind = AccessType.fromWire(accessType);
        if (kind == null) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of(
                    "error", "accessType must be 'read' or 'banned'")));
        }
        String type = kind.value();
        return access.revoke(userSubject, type, auth)
                .thenReturn(ResponseEntity.ok(Map.<String, Object>of(
                        "state", "revoked", "accessType", accessType, "userSubject", userSubject)));
    }

    // ── manage-all (WIKI_MANAGE) ───────────────────────────────────────

    @Operation(
            operationId = "wikiAccessListActive",
            summary = "List all ACTIVE wiki_access overrides (scoped to the caller's "
                    + "brand; SUPERADMIN sees all). SUPERADMIN.",
            description = "Returns all currently active (non-revoked, non-expired) {@code wiki_access} "
                    + "rows visible to the caller. A scoped SUPERADMIN will see every brand's active "
                    + "overrides (the repository applies no domain filter when the actor's scope is null). "
                    + "Returns an empty list when no active overrides exist — never 404."
                    + "<table>"
                    + "<tr><th>Role</th><th>Permitted</th><th>Scope</th></tr>"
                    + "<tr><td>SUPERADMIN</td><td>Yes</td><td>All brands</td></tr>"
                    + "<tr><td>ADMIN / MOD / USER</td><td>No</td><td>—</td></tr>"
                    + "</table>")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of active wiki_access overrides "
                    + "(may be an empty list when none exist)."),
            @ApiResponse(responseCode = "401", description = "No valid JWT present in the request.", content = @Content),
            @ApiResponse(responseCode = "403", description = "Caller does not hold SUPERADMIN role.", content = @Content),
            @ApiResponse(responseCode = "500", description = "Unexpected DB error reading override rows.", content = @Content)
    })
    @PreAuthorize(WikiController.WIKI_MANAGE)
    @GetMapping("/superadmin/access")
    public Mono<WikiAccessDto.AccessList> listActive(Authentication auth) {
        return access.listActive(auth).map(WikiAccessDto.Access::of)
                .collectList().map(WikiAccessDto.AccessList::new);
    }

    @Operation(
            operationId = "wikiAccessHistory",
            summary = "The full grant/timeout/ban HISTORY (active + revoked = audit) for "
                    + "a principal (scoped to the caller's brand; SUPERADMIN sees all). SUPERADMIN.",
            description = "Returns all {@code wiki_access} rows (both active and revoked) for the given "
                    + "{@code userSubject}. Revoked rows are retained permanently for audit. Returns an "
                    + "empty list — never 404 — when no history exists for the subject."
                    + "<table>"
                    + "<tr><th>Role</th><th>Permitted</th><th>Scope</th></tr>"
                    + "<tr><td>SUPERADMIN</td><td>Yes</td><td>All brands</td></tr>"
                    + "<tr><td>ADMIN / MOD / USER</td><td>No</td><td>—</td></tr>"
                    + "</table>")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Full audit history for the subject "
                    + "(active + revoked rows). Empty list when no history exists — never 404."),
            @ApiResponse(responseCode = "401", description = "No valid JWT present in the request.", content = @Content),
            @ApiResponse(responseCode = "403", description = "Caller does not hold SUPERADMIN role.", content = @Content),
            @ApiResponse(responseCode = "500", description = "Unexpected DB error reading history rows.", content = @Content)
    })
    @PreAuthorize(WikiController.WIKI_MANAGE)
    @GetMapping("/superadmin/access/{userSubject}/history")
    public Mono<WikiAccessDto.AccessList> history(@PathVariable String userSubject, Authentication auth) {
        return access.historyFor(userSubject, auth).map(WikiAccessDto.Access::of)
                .collectList().map(WikiAccessDto.AccessList::new);
    }
}
