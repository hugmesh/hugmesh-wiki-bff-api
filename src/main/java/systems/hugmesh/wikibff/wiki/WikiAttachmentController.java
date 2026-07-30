package systems.hugmesh.wikibff.wiki;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import systems.hugmesh.commons.web.ApiException;
import systems.hugmesh.wikibff.access.WikiAccessGuard;

import java.util.Map;

/**
 * REST surface for per-page binary attachments ({@code /bff/wiki/.../attachments}) over
 * the {@code wiki_attachment} table — list metadata, download raw bytes, upload, and
 * delete; the sibling of {@link WikiController} for non-markdown content.
 *
 * <h2>Security</h2>
 * <table border="1">
 *   <caption>Role matrix</caption>
 *   <tr><th>Operation</th><th>Required authority</th><th>Additional gate</th></tr>
 *   <tr><td>List metadata</td><td>WIKI_READ_AUTHENTICATED</td><td>{@link WikiAccessGuard} (ADMIN | SUPERADMIN | MOD+WIKI, or active grant; banned users rejected)</td></tr>
 *   <tr><td>Download bytes</td><td>WIKI_READ_AUTHENTICATED</td><td>{@link WikiAccessGuard} (same as list)</td></tr>
 *   <tr><td>Upload</td><td>WIKI_WRITE (ADMIN | SUPERADMIN)</td><td>—</td></tr>
 *   <tr><td>Delete</td><td>WIKI_WRITE (ADMIN | SUPERADMIN)</td><td>—</td></tr>
 * </table>
 *
 * <h2>Domain scoping</h2>
 * The brand/tenant ({@code domain}) is derived <em>server-side</em> from the validated
 * JWT issuer via {@link systems.hugmesh.wikibff.security.PrincipalDomain#forScope}
 * — it is never accepted as a request field. Every surface is DOMAIN-SCOPED in
 * {@link WikiAttachmentService}: a scoped ADMIN only ever touches own-brand attachments;
 * SUPERADMIN is the cross-domain bypass.
 *
 * <h2>Mount paths</h2>
 * <ul>
 *   <li>Read surface: {@code /bff/wiki/pages/{slug}/attachments}</li>
 *   <li>Write surface: {@code /bff/wiki/admin/pages/{slug}/attachments}</li>
 * </ul>
 */
@Tag(name = "Wiki Attachments", description = "Per-page binary attachments (wiki_attachment table). "
        + "Upload/delete: ADMIN/SUPERADMIN. Download/list: USER (authenticated).")
@RestController
@RequestMapping("/bff/wiki")
public class WikiAttachmentController {

    private final WikiAttachmentService attachmentService;
    private final WikiAccessGuard accessGuard;
    private final long maxAttachmentBytes;

    /**
     * Constructs the controller with its service and access-guard dependencies.
     *
     * @param attachmentService  service handling attachment lifecycle (list/download/upload/delete)
     * @param accessGuard        guard that enforces per-caller wiki read-access rules
     * @param maxAttachmentBytes per-attachment upload ceiling (bytes); enforced on upload so an
     *                           oversize part is rejected with 413 rather than accumulated in
     *                           heap. Default 25 MiB (= 26214400, the
     *                           {@code app.wiki.attachment.max-bytes} default in application.yml).
     */
    public WikiAttachmentController(WikiAttachmentService attachmentService,
                                    WikiAccessGuard accessGuard,
                                    @Value("${app.wiki.attachment.max-bytes:26214400}")
                                    long maxAttachmentBytes) {
        this.attachmentService = attachmentService;
        this.accessGuard = accessGuard;
        this.maxAttachmentBytes = maxAttachmentBytes;
    }

    /**
     * Lists attachment metadata (filename, mimeType, createdAt — no binary data) for a
     * page by slug in the caller's brand.
     *
     * <p>Requires {@code WIKI_READ_AUTHENTICATED} plus {@link WikiAccessGuard} (ADMIN |
     * SUPERADMIN | MOD+WIKI, or an active grant; rejected if actively banned).
     *
     * @param slug the human-readable page slug
     * @param auth the validated JWT principal carrying the caller's roles and issuer
     * @return a {@code Mono} emitting a map with key {@code "attachments"} whose value is
     *         a list of metadata maps (id, filename, mimeType, createdAt)
     */
    @Operation(
            operationId = "wikiListAttachments",
            summary = "List attachment metadata for a page (no binary data). USER/ADMIN/SUPERADMIN via WikiAccessGuard.",
            description = "List attachment metadata (filename, mimeType, createdAt — no binary data) for a page "
                    + "by slug in the caller's brand. Requires WIKI_READ_AUTHENTICATED + WikiAccessGuard "
                    + "(ADMIN | SUPERADMIN | MOD+WIKI, or an active grant; rejected if actively banned).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of attachment metadata (no binary data)"),
            @ApiResponse(responseCode = "401", description = "No valid JWT supplied", content = @Content),
            @ApiResponse(responseCode = "403", description = "Banned caller, or no qualifying role/grant", content = @Content),
            @ApiResponse(responseCode = "404", description = "Page slug not found", content = @Content),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content)
    })
    @PreAuthorize(WikiController.WIKI_READ_AUTHENTICATED)
    @GetMapping("/pages/{slug}/attachments")
    public Mono<Map<String, Object>> listAttachments(@PathVariable String slug, Authentication auth) {
        return accessGuard.assertCanRead(auth).then(
                Mono.defer(() -> attachmentService.list(slug, auth)
                        .map(a -> Map.<String, Object>of(
                                "id", a.id(), "filename", a.filename(),
                                "mimeType", a.mimeType(), "createdAt", a.createdAt()))
                        .collectList()
                        .map(items -> Map.<String, Object>of("attachments", items))));
    }

    /**
     * Downloads a single attachment's raw bytes for a page by slug + filename, served
     * with the MIME type stored at upload time.
     *
     * <p>Requires {@code WIKI_READ_AUTHENTICATED} plus {@link WikiAccessGuard} (same gate
     * as {@link #listAttachments}).
     *
     * @param slug     the human-readable page slug
     * @param filename the attachment filename to retrieve
     * @param auth     the validated JWT principal
     * @return a {@code Mono} emitting a {@link ResponseEntity} whose body is the raw bytes
     *         and whose {@code Content-Type} is set to the stored MIME type
     */
    @Operation(
            operationId = "wikiDownloadAttachment",
            summary = "Download a single attachment (streams the raw bytes with the stored mime type). USER/ADMIN/SUPERADMIN via WikiAccessGuard.",
            description = "Download a single attachment's raw bytes for a page by slug + filename, served with the "
                    + "stored MIME type. Same WikiAccessGuard gate as listAttachments.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Raw bytes with Content-Type set to the stored mimeType"),
            @ApiResponse(responseCode = "401", description = "No valid JWT supplied", content = @Content),
            @ApiResponse(responseCode = "403", description = "Banned caller, or no qualifying role/grant", content = @Content),
            @ApiResponse(responseCode = "404", description = "Attachment not found for the given slug and filename", content = @Content),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content)
    })
    @PreAuthorize(WikiController.WIKI_READ_AUTHENTICATED)
    // produces = octet-stream so the global springdoc default-produces (application/json)
    // does NOT clobber this raw-bytes endpoint. The actual runtime Content-Type is the
    // stored mime type (set via ResponseEntity.contentType below); declaring octet-stream
    // here keeps the OpenAPI 200 response a BINARY type so ng-openapi-gen emits a Blob
    // download client method, not a JSON one (which would corrupt binary downloads).
    @GetMapping(value = "/pages/{slug}/attachments/{filename}", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public Mono<ResponseEntity<byte[]>> downloadAttachment(@PathVariable String slug,
                                                            @PathVariable String filename,
                                                            Authentication auth) {
        return accessGuard.assertCanRead(auth).then(
                Mono.defer(() -> attachmentService.download(slug, filename, auth)
                        .map(a -> ResponseEntity.ok()
                                .contentType(MediaType.parseMediaType(a.mimeType()))
                                .body(a.data()))));
    }

    /**
     * Uploads a binary attachment to a page via {@code multipart/form-data}. The file
     * part must be named {@code "file"}. The domain is derived server-side from the
     * caller's JWT. ADMIN/SUPERADMIN only.
     *
     * <p>The multipart part is read into a single BOUNDED {@code byte[]} before
     * persistence (see {@link #readCappedBytes}); an upload past the configured
     * {@code app.wiki.attachment.max-bytes} ceiling is rejected with 413.
     *
     * @param slug     the human-readable page slug for the target page
     * @param filePart the multipart file part (part name = {@code "file"})
     * @param auth     the validated JWT principal (must carry ADMIN or SUPERADMIN)
     * @return a {@code Mono} emitting a {@link ResponseEntity} containing saved attachment
     *         metadata (id, filename, mimeType, createdAt)
     */
    @Operation(
            operationId = "wikiUploadAttachment",
            summary = "Upload a binary attachment to a page (multipart/form-data; file part = 'file'). ADMIN/SUPERADMIN.",
            description = "Upload a binary attachment to a page (multipart/form-data; part name = 'file'). "
                    + "The domain is server-derived from the caller's JWT. ADMIN/SUPERADMIN only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Saved attachment metadata (id, filename, mimeType, createdAt)"),
            @ApiResponse(responseCode = "400", description = "Missing or malformed file part", content = @Content),
            @ApiResponse(responseCode = "401", description = "No valid JWT supplied", content = @Content),
            @ApiResponse(responseCode = "403", description = "Not ADMIN or SUPERADMIN", content = @Content),
            @ApiResponse(responseCode = "404", description = "Page slug not found", content = @Content),
            @ApiResponse(responseCode = "413", description = "Attachment exceeds the configured size ceiling", content = @Content),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content)
    })
    @PreAuthorize(WikiController.WIKI_WRITE)
    @PostMapping(value = "/admin/pages/{slug}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> uploadAttachment(@PathVariable String slug,
                                                                       @RequestPart("file") FilePart filePart,
                                                                       Authentication auth) {
        String filename = filePart.filename();
        String mimeType = filePart.headers().getContentType() != null
                ? filePart.headers().getContentType().toString()
                : "application/octet-stream";
        return readCappedBytes(filePart)
                .flatMap(data -> attachmentService.upload(slug, filename, mimeType, data, auth))
                .map(saved -> ResponseEntity.ok(Map.<String, Object>of(
                        "id", saved.id(), "filename", saved.filename(),
                        "mimeType", saved.mimeType(), "createdAt", saved.createdAt())));
    }

    /**
     * Read the multipart file part into a BOUNDED {@code byte[]}, rejecting (413) any upload
     * past {@link #maxAttachmentBytes}. Replaces the prior {@code reduce(byte[])} accumulator,
     * which reallocated + arraycopied the whole running buffer on every {@link DataBuffer}
     * (O(n^2) copy cost) AND had no size ceiling — a multi-hundred-MB part exhausted heap.
     *
     * <p>The cap is enforced AT the join (bounded by {@code maxAttachmentBytes + 1}): a single
     * bounded concatenation (O(n)) into one {@link DataBuffer}, and an oversize part overflows
     * the join as a {@link DataBufferLimitException} — mapped to a clean 413 — rather than being
     * buffered unbounded. Mirrors the {@code ProfileController.readCappedBytes} idiom in
     * hugmesh-user-bff-api.
     */
    private Mono<byte[]> readCappedBytes(FilePart filePart) {
        return DataBufferUtils.join(filePart.content(),
                        (int) Math.min(maxAttachmentBytes + 1, Integer.MAX_VALUE))
                .flatMap(this::toCappedByteArray)
                .onErrorMap(WikiAttachmentController::isJoinLimit, this::oversize);
    }

    /** Convert a joined {@link DataBuffer} to a capped {@code byte[]}, always releasing the
     *  buffer; fail with 413 when it exceeds {@link #maxAttachmentBytes}. */
    private Mono<byte[]> toCappedByteArray(DataBuffer joined) {
        try {
            int size = joined.readableByteCount();
            if (size > maxAttachmentBytes) {
                return Mono.error(oversize(null));
            }
            byte[] bytes = new byte[size];
            joined.read(bytes);
            return Mono.just(bytes);
        } finally {
            DataBufferUtils.release(joined);
        }
    }

    /** The join's size cap overflows as a {@link DataBufferLimitException}. */
    private static boolean isJoinLimit(Throwable t) {
        return t instanceof DataBufferLimitException;
    }

    /** The canonical 413 for an attachment that exceeds {@link #maxAttachmentBytes}. */
    private ApiException oversize(Throwable ignored) {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                "attachment exceeds the " + (maxAttachmentBytes / (1024 * 1024)) + "MB limit");
    }

    /**
     * Deletes an attachment from a page by slug + filename in the caller's brand.
     * ADMIN/SUPERADMIN only.
     *
     * @param slug     the human-readable page slug
     * @param filename the attachment filename to delete
     * @param auth     the validated JWT principal (must carry ADMIN or SUPERADMIN)
     * @return a {@code Mono} emitting a {@link ResponseEntity} confirming deletion
     *         (state, slug, filename)
     */
    @Operation(
            operationId = "wikiDeleteAttachment",
            summary = "Delete an attachment from a page by filename. ADMIN/SUPERADMIN.",
            description = "Delete an attachment by page slug + filename in the caller's brand. ADMIN/SUPERADMIN only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Deleted confirmation map (state, slug, filename)"),
            @ApiResponse(responseCode = "401", description = "No valid JWT supplied", content = @Content),
            @ApiResponse(responseCode = "403", description = "Not ADMIN or SUPERADMIN", content = @Content),
            @ApiResponse(responseCode = "404", description = "Attachment not found for the given slug and filename", content = @Content),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content)
    })
    @PreAuthorize(WikiController.WIKI_WRITE)
    @DeleteMapping("/admin/pages/{slug}/attachments/{filename}")
    public Mono<ResponseEntity<Map<String, Object>>> deleteAttachment(@PathVariable String slug,
                                                                       @PathVariable String filename,
                                                                       Authentication auth) {
        return attachmentService.delete(slug, filename, auth)
                .thenReturn(ResponseEntity.ok(Map.<String, Object>of(
                        "state", "deleted", "slug", slug, "filename", filename)));
    }
}
