package systems.hugmesh.wikibff.wiki;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;
import systems.hugmesh.wikibff.access.WikiAccessGuard;
import systems.hugmesh.wikibff.config.SecurityConfig;
import systems.hugmesh.wikibff.web.ApiExceptionHandler;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * Regression for the unbounded in-memory attachment-upload DoS (finding #4): the upload
 * handler used to {@code reduce(new byte[0], ...)} the WHOLE multipart part into one heap
 * {@code byte[]} with O(n^2) reallocation and NO size ceiling, so an oversize part exhausted
 * heap. The handler now bounds the read with {@code DataBufferUtils.join(content, cap + 1)}
 * and rejects an over-cap part with {@code 413 Payload Too Large} BEFORE it reaches the store.
 *
 * <p>The cap is driven by {@code app.wiki.attachment.max-bytes}, set to a tiny value here so a
 * small body proves the bound without allocating megabytes. The companion
 * {@link WikiAttachmentControllerTest} continues to prove a legitimate (under-cap) upload at
 * the production default. The server-level {@code spring.webflux.multipart} disk limit is
 * raised in this slice so the over-cap body reaches the handler's own join cap (the unit under
 * test), not the codec's part-size guard.
 */
@WebFluxTest(controllers = WikiAttachmentController.class)
@Import({SecurityConfig.class, ApiExceptionHandler.class})
@TestPropertySource(properties = {
        "app.wiki.attachment.max-bytes=8",
        "spring.webflux.multipart.max-in-memory-size=1MB",
        "spring.webflux.multipart.max-disk-usage-per-part=1MB"
})
class WikiAttachmentUploadSizeCapTest {

    @Autowired ApplicationContext context;

    WebTestClient web;

    @MockitoBean WikiAttachmentService attachmentService;
    @MockitoBean WikiAccessGuard accessGuard;
    @MockitoBean ReactiveJwtDecoder jwtDecoder;

    private static final WikiAttachment ATTACHMENT = new WikiAttachment(
            "id-1", "page-1", "default", "small.bin", "application/octet-stream",
            new byte[]{1, 2, 3}, Instant.now());

    @BeforeEach
    void setUp() {
        web = WebTestClient.bindToApplicationContext(context).apply(springSecurity()).build();
        when(accessGuard.assertCanRead(any())).thenReturn(Mono.empty());
    }

    private WebTestClient asAdmin() {
        return web.mutateWith(mockJwt().authorities(
                new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static ByteArrayResource part(byte[] bytes, String name) {
        return new ByteArrayResource(bytes) {
            @Override public String getFilename() { return name; }
        };
    }

    /** EXPLOIT: a part larger than the configured ceiling is rejected with 413 and NEVER
     *  reaches the store — proving the bound the prior unbounded reduce(byte[]) lacked. */
    @Test
    void upload_overCap_rejected413_andNotStored() {
        byte[] oversize = new byte[64]; // > 8-byte cap
        asAdmin().post().uri("/bff/wiki/admin/pages/home/attachments")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData("file", part(oversize, "big.bin")))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        verify(attachmentService, never()).upload(any(), any(), any(), any(), any());
    }

    /** A legitimate (within-cap) upload still succeeds and is stored. */
    @Test
    void upload_withinCap_ok() {
        when(attachmentService.upload(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(ATTACHMENT));
        byte[] small = new byte[]{1, 2, 3}; // <= 8-byte cap
        asAdmin().post().uri("/bff/wiki/admin/pages/home/attachments")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData("file", part(small, "small.bin")))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.filename").isEqualTo("small.bin");
        verify(attachmentService).upload(any(), any(), any(), any(), any());
    }
}
