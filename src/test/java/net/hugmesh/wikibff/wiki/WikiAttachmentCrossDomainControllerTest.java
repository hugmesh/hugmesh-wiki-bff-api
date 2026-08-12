package net.hugmesh.wikibff.wiki;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import net.hugmesh.wikibff.access.WikiAccessGuard;
import net.hugmesh.wikibff.config.SecurityConfig;
import net.hugmesh.wikibff.security.PrincipalDomain;
import net.hugmesh.wikibff.web.ApiExceptionHandler;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * WAVE-2 HTTP-LAYER CROSS-DOMAIN for the {@link WikiAttachmentController} write/read seam
 * (upload / download / delete). The existing {@code WikiAttachmentControllerTest} mocks
 * {@link WikiAttachmentService} and proves only the COARSE role gate; the issuer-derived
 * domain scope was exercised at the service layer but NOT asserted at the boundary. This
 * suite closes that gap, MUTATION-RESISTANTLY.
 *
 * <p>Approach (same as the wiki/access cross-domain suites): wire the REAL
 * {@link WikiAttachmentService} over MOCKED repositories + the REAL {@link PrincipalDomain}
 * on a MULTI-BRAND registry (driven via slice {@code properties}); capture the
 * {@code domain} STRING the repo actually receives ({@code attachments.insert(..., domain)}
 * / {@code attachments.findBySlugAndFilename(slug, domain, filename)} /
 * {@code attachments.deleteBySlugAndFilename(slug, domain, filename)}). A broken scope
 * changes the captured value and FAILS the test — no re-implementation in-test.
 */
@WebFluxTest(controllers = WikiAttachmentController.class, properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://id.brandx.example",
        "app.tenancy.issuers=https://id.brandx.example=brandx,https://id.brandy.example=brandy"
})
@Import({SecurityConfig.class, ApiExceptionHandler.class,
        WikiAttachmentCrossDomainControllerTest.RealServiceConfig.class})
class WikiAttachmentCrossDomainControllerTest {

    private static final String BRANDX_ISS = "https://id.brandx.example";
    private static final String BRANDY_ISS = "https://id.brandy.example";

    /** The REAL attachment service over MOCKED repos, consuming the REAL multi-brand
     *  PrincipalDomain that SecurityConfig imports — the scope derivation is exercised. */
    @TestConfiguration
    static class RealServiceConfig {
        @Bean
        WikiAttachmentService wikiAttachmentService(WikiAttachmentRepository attachments,
                                                    WikiPageRepository pages, WikiAuditLogRepository audit,
                                                    PrincipalDomain principalDomain) {
            return new WikiAttachmentService(attachments, pages, audit, principalDomain);
        }
    }

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

    @MockitoBean WikiAttachmentRepository attachments;
    @MockitoBean WikiPageRepository pages;
    @MockitoBean WikiAuditLogRepository auditRepo;
    @MockitoBean WikiAccessGuard accessGuard;
    @MockitoBean ReactiveJwtDecoder jwtDecoder;

    private static Consumer<Jwt.Builder> jwt(String issuer, List<String> roles) {
        return b -> b.claim("iss", issuer).issuer(issuer).subject("actor-1")
                .claim("roles", roles)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600));
    }

    private WebTestClient as(String issuer, String role) {
        return web.mutateWith(mockJwt()
                .jwt(jwt(issuer, List.of(role)))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("ROLE_" + role.toUpperCase())));
    }

    private static WikiAttachment attachment(String domain, String filename) {
        return new WikiAttachment("att-1", "page-1", domain, filename, "image/png",
                new byte[]{1, 2, 3}, Instant.now());
    }

    private static WikiPage pageRow(String domain, String slug) {
        return new WikiPage("page-1", domain, slug, "Title", "# md", "<h1>md</h1>",
                "author-1", 1L, Instant.now(), Instant.now(), false, null);
    }

    // ── UPLOAD — page lookup + insert are issuer-derived scoped ───────────────

    @Test
    void upload_brandXAdmin_pageLookupAndInsertScopedToBrandX() {
        // upload() first verifies the page exists in the caller's brand, then inserts the
        // attachment stamped with that brand. Both flow the issuer-derived domain.
        when(pages.findBySlug(eq("home"), any())).thenReturn(Mono.just(pageRow("brandx", "home")));
        when(attachments.insert(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(attachment("brandx", "img.png")));
        when(auditRepo.record(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(new WikiAuditLog("a-1", "brandx", "actor-1",
                        "wiki.attachment.upload", "home", "img.png", Instant.now())));

        as(BRANDX_ISS, "admin").post().uri("/bff/wiki/admin/pages/home/attachments")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData("file",
                        new ByteArrayResource(new byte[]{1, 2, 3}) {
                            @Override public String getFilename() { return "img.png"; }
                        }))
                .exchange().expectStatus().isOk();

        // MUTATION-RESISTANT: both the page pre-check AND the insert are brand-X scoped.
        verify(pages).findBySlug("home", "brandx");
        org.mockito.ArgumentCaptor<String> domain = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(attachments).insert(any(), domain.capture(), eq("img.png"), any(), any());
        assertThat(domain.getValue())
                .as("the uploaded attachment is stamped brandx (issuer-derived), never a body/param")
                .isEqualTo("brandx");
    }

    @Test
    void upload_brandXAdmin_pageInBrandYInvisible_404_neverInserts() {
        // The page exists only in brand-Y; a brand-X admin's upload pre-checks brand-X
        // (where the page is absent) -> 404, and NEVER inserts. A leaked scope would find
        // the brand-Y page and wrongly insert.
        when(pages.findBySlug(eq("home"), eq("brandx"))).thenReturn(Mono.empty());
        when(pages.findBySlug(eq("home"), eq("brandy"))).thenReturn(Mono.just(pageRow("brandy", "home")));

        as(BRANDX_ISS, "admin").post().uri("/bff/wiki/admin/pages/home/attachments")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData("file",
                        new ByteArrayResource(new byte[]{1}) {
                            @Override public String getFilename() { return "f.bin"; }
                        }))
                .exchange().expectStatus().isNotFound();

        verify(pages).findBySlug("home", "brandx");
        verify(pages, never()).findBySlug("home", "brandy");
        verify(attachments, never()).insert(any(), any(), any(), any(), any());
    }

    // ── DOWNLOAD — read is issuer-derived scoped ──────────────────────────────

    @Test
    void download_brandYAdmin_repoQueriedScopedToBrandY() {
        when(accessGuard.assertCanRead(any())).thenReturn(Mono.empty());
        when(attachments.findBySlugAndFilename(eq("home"), any(), eq("img.png")))
                .thenReturn(Mono.just(attachment("brandy", "img.png")));

        as(BRANDY_ISS, "admin").get().uri("/bff/wiki/pages/home/attachments/img.png")
                .exchange().expectStatus().isOk()
                .expectHeader().contentType("image/png");

        org.mockito.ArgumentCaptor<String> domain = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(attachments).findBySlugAndFilename(eq("home"), domain.capture(), eq("img.png"));
        assertThat(domain.getValue())
                .as("a brand-Y admin downloads scoped to brandy (issuer-derived)")
                .isEqualTo("brandy");
    }

    @Test
    void download_brandXAdmin_brandYAttachmentInvisible_404() {
        // The attachment exists only in brand-Y; the brand-X reader's scoped lookup is
        // empty -> 404 (a cross-brand attachment is invisible, not leaked).
        when(accessGuard.assertCanRead(any())).thenReturn(Mono.empty());
        when(attachments.findBySlugAndFilename(eq("home"), eq("brandx"), eq("img.png")))
                .thenReturn(Mono.empty());
        when(attachments.findBySlugAndFilename(eq("home"), eq("brandy"), eq("img.png")))
                .thenReturn(Mono.just(attachment("brandy", "img.png")));

        as(BRANDX_ISS, "admin").get().uri("/bff/wiki/pages/home/attachments/img.png")
                .exchange().expectStatus().isNotFound();

        verify(attachments).findBySlugAndFilename("home", "brandx", "img.png");
        verify(attachments, never()).findBySlugAndFilename("home", "brandy", "img.png");
    }

    // ── DELETE — delete is issuer-derived scoped ──────────────────────────────

    @Test
    void delete_brandXAdmin_repoDeleteScopedToBrandX() {
        when(attachments.deleteBySlugAndFilename(eq("home"), any(), eq("img.png")))
                .thenReturn(Mono.just(1L));
        when(auditRepo.record(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(new WikiAuditLog("a-1", "brandx", "actor-1",
                        "wiki.attachment.delete", "home", "img.png", Instant.now())));

        as(BRANDX_ISS, "admin").delete().uri("/bff/wiki/admin/pages/home/attachments/img.png")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.state").isEqualTo("deleted");

        org.mockito.ArgumentCaptor<String> domain = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(attachments).deleteBySlugAndFilename(eq("home"), domain.capture(), eq("img.png"));
        assertThat(domain.getValue())
                .as("a brand-X admin's delete is scoped to brandx (issuer-derived)")
                .isEqualTo("brandx");
    }

    // ── role/auth boundary (KEPT — least privilege) ──────────────────────────

    @Test
    void upload_anonymous_unauthorized() {
        web.post().uri("/bff/wiki/admin/pages/home/attachments")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData("file",
                        new ByteArrayResource(new byte[]{1}) {
                            @Override public String getFilename() { return "f.bin"; }
                        }))
                .exchange().expectStatus().isUnauthorized();
        verify(attachments, never()).insert(any(), any(), any(), any(), any());
    }

    @Test
    void delete_forbiddenForPlainUser_writersAreAdminsOnly() {
        as(BRANDX_ISS, "user").delete().uri("/bff/wiki/admin/pages/home/attachments/img.png")
                .exchange().expectStatus().isForbidden();
        verify(attachments, never()).deleteBySlugAndFilename(any(), any(), any());
    }
}
