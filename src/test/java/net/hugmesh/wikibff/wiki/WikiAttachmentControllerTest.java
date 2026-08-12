package net.hugmesh.wikibff.wiki;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import net.hugmesh.wikibff.access.WikiAccessGuard;
import net.hugmesh.wikibff.config.SecurityConfig;
import net.hugmesh.commons.web.ApiException;
import net.hugmesh.wikibff.web.ApiExceptionHandler;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * Wiki attachment surface gating + contract (reactive @WebFluxTest). Tests the
 * WIKI_READ_AUTHENTICATED + WikiAccessGuard read gate and the WIKI_WRITE (ADMIN/SUPERADMIN)
 * upload/delete gate. Guard logic is mocked here — guard semantics are separately proven in
 * WikiAccessGuardTest.
 */
@WebFluxTest(controllers = WikiAttachmentController.class)
@Import({SecurityConfig.class, ApiExceptionHandler.class})
class WikiAttachmentControllerTest {

    @Autowired ApplicationContext context;

    WebTestClient web;

    // Spring Security 7: mockJwt()'s SecurityContext is only honoured when the
    // springSecurity() configurer's MutatorFilter is in the chain. A WebTestClient
    // autowired from @WebFluxTest lacks that configurer (mutator dropped -> 401), so
    // build it from the ApplicationContext with .apply(springSecurity()). Anonymous
    // legs still resolve to 401 exactly as before.
    @BeforeEach
    void setUpSecuredClient() {
        web = WebTestClient.bindToApplicationContext(context).apply(springSecurity()).build();
    }

    @MockitoBean WikiAttachmentService attachmentService;
    @MockitoBean WikiAccessGuard accessGuard;
    @MockitoBean ReactiveJwtDecoder jwtDecoder;

    private static final WikiAttachment ATTACHMENT = new WikiAttachment(
            "id-1", "page-1", "default", "img.png", "image/png",
            new byte[]{1, 2, 3}, Instant.now());

    @BeforeEach
    void allowReadByDefault() {
        when(accessGuard.assertCanRead(any())).thenReturn(Mono.empty());
    }

    private WebTestClient asUser() {
        return web.mutateWith(mockJwt().authorities(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private WebTestClient asModWiki() {
        return web.mutateWith(mockJwt().authorities(
                new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_MOD"),
                new SimpleGrantedAuthority("ROLE_WIKI")));
    }

    private WebTestClient asAdmin() {
        return web.mutateWith(mockJwt().authorities(
                new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private WebTestClient asSuperadmin() {
        return web.mutateWith(mockJwt().authorities(
                new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_SUPERADMIN")));
    }

    private void guardDenies() {
        when(accessGuard.assertCanRead(any()))
                .thenReturn(Mono.error(ApiException.forbidden("denied")));
    }

    // ── LIST ──────────────────────────────────────────────────────────────

    @Test
    void list_allowedForAdmin() {
        when(attachmentService.list(any(), any())).thenReturn(Flux.just(ATTACHMENT));
        asAdmin().get().uri("/bff/wiki/pages/home/attachments").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.attachments[0].filename").isEqualTo("img.png");
    }

    @Test
    void list_allowedForModWithWiki() {
        when(attachmentService.list(any(), any())).thenReturn(Flux.just(ATTACHMENT));
        asModWiki().get().uri("/bff/wiki/pages/home/attachments").exchange()
                .expectStatus().isOk();
    }

    @Test
    void list_forbiddenWhenGuardDenies() {
        guardDenies();
        when(attachmentService.list(any(), any())).thenReturn(Flux.empty());
        asAdmin().get().uri("/bff/wiki/pages/home/attachments").exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void list_anonymous_401() {
        web.get().uri("/bff/wiki/pages/home/attachments").exchange()
                .expectStatus().isUnauthorized();
    }

    // ── DOWNLOAD ──────────────────────────────────────────────────────────

    @Test
    void download_allowedForAdmin() {
        when(attachmentService.download(any(), any(), any())).thenReturn(Mono.just(ATTACHMENT));
        asAdmin().get().uri("/bff/wiki/pages/home/attachments/img.png").exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("image/png");
    }

    @Test
    void download_anonymous_401() {
        web.get().uri("/bff/wiki/pages/home/attachments/img.png").exchange()
                .expectStatus().isUnauthorized();
    }

    // ── UPLOAD ────────────────────────────────────────────────────────────

    @Test
    void upload_allowedForAdmin() {
        when(attachmentService.upload(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(ATTACHMENT));
        asAdmin().post().uri("/bff/wiki/admin/pages/home/attachments")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData("file",
                        new ByteArrayResource(new byte[]{1, 2, 3}) {
                            @Override public String getFilename() { return "img.png"; }
                        }))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.filename").isEqualTo("img.png");
    }

    @Test
    void upload_forbiddenForModWiki() {
        asModWiki().post().uri("/bff/wiki/admin/pages/home/attachments")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData("file",
                        new ByteArrayResource(new byte[]{1}) {
                            @Override public String getFilename() { return "f.bin"; }
                        }))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void upload_forbiddenForPlainUser() {
        asUser().post().uri("/bff/wiki/admin/pages/home/attachments")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData("file",
                        new ByteArrayResource(new byte[]{1}) {
                            @Override public String getFilename() { return "f.bin"; }
                        }))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void upload_anonymous_401() {
        web.post().uri("/bff/wiki/admin/pages/home/attachments")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData("file",
                        new ByteArrayResource(new byte[]{1}) {
                            @Override public String getFilename() { return "f.bin"; }
                        }))
                .exchange().expectStatus().isUnauthorized();
    }

    // ── DELETE ────────────────────────────────────────────────────────────

    @Test
    void delete_allowedForAdmin() {
        when(attachmentService.delete(any(), any(), any())).thenReturn(Mono.empty());
        asAdmin().delete().uri("/bff/wiki/admin/pages/home/attachments/img.png").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.state").isEqualTo("deleted");
    }

    @Test
    void delete_allowedForSuperadmin() {
        when(attachmentService.delete(any(), any(), any())).thenReturn(Mono.empty());
        asSuperadmin().delete().uri("/bff/wiki/admin/pages/home/attachments/img.png").exchange()
                .expectStatus().isOk();
    }

    @Test
    void delete_forbiddenForModWiki() {
        asModWiki().delete().uri("/bff/wiki/admin/pages/home/attachments/img.png").exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void delete_anonymous_401() {
        web.delete().uri("/bff/wiki/admin/pages/home/attachments/img.png").exchange()
                .expectStatus().isUnauthorized();
    }
}
