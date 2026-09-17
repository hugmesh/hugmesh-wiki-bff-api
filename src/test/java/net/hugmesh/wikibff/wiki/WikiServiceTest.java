package net.hugmesh.wikibff.wiki;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import net.hugmesh.commons.security.DomainRegistry;
import net.hugmesh.wikibff.security.PrincipalDomain;
import net.hugmesh.wikibff.support.TestDatabaseConfig;
import net.hugmesh.commons.testsupport.PostgresTestSupport;
import net.hugmesh.commons.web.ApiException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wiki domain service over REAL repos + the REAL markdown renderer + a multi-brand
 * {@link PrincipalDomain} (so the issuer-derived domain scoping is exercised end to
 * end). Delineated per the test standard:
 *
 * <ul>
 *   <li><b>USER-SITUATIONAL</b> — create renders markdown->HTML, stamps author from the
 *       JWT subject + domain from the issuer, audits; update bumps version.</li>
 *   <li><b>CROSS-USER (ALLOW + DENY isolation + domain isolation)</b> — a brand-X author
 *       reads its own page (ALLOW) but a brand-Y caller cannot see it (DENY, 404 not a
 *       leak); the audit trail is per-brand; SUPERADMIN reads across brands.</li>
 *   <li><b>CONCURRENT-CONFLICTING</b> — two racing updates at the same expectedVersion:
 *       exactly one succeeds, the other is a 409 stale-edit; the version bumps once.</li>
 *   <li><b>DELETE SCOPING (issue #15)</b> — a SUPERADMIN delete removes exactly the row its
 *       cross-brand resolve found (and that row's attachments, via the FK cascade), never a
 *       same-slug row in {@code default} or any other brand, and audits in the page's own
 *       brand; a scoped ADMIN still cannot reach another brand's page.</li>
 * </ul>
 */
@DataR2dbcTest
@Import({TestDatabaseConfig.class, WikiPageRepository.class, WikiAuditLogRepository.class,
        WikiAttachmentRepository.class})
class WikiServiceTest extends PostgresTestSupport {

    @Autowired WikiPageRepository pages;
    @Autowired WikiAuditLogRepository audit;
    @Autowired WikiAttachmentRepository attachments;
    /** Raw handles for the delete tests: prove rows/attachments exist or not by PRIMARY KEY,
     *  and plant a same-slug {@code default}-brand row with a chosen id. */
    @Autowired WikiAttachmentDataRepository attachmentData;
    @Autowired DatabaseClient db;

    private WikiService service;

    /** Multi-brand registry: brandx + brandy issuers; everything else is unknown. */
    private static final DomainRegistry MULTI = new DomainRegistry(
            "https://id.brandx.example",
            "https://id.brandx.example=brandx,https://id.brandy.example=brandy");

    private static final AtomicInteger SEQ = new AtomicInteger();
    private String slug(String tag) { return tag + "-" + SEQ.incrementAndGet(); }

    @BeforeEach
    void setUp() {
        service = new WikiService(pages, audit, new MarkdownRenderer(), new PrincipalDomain(MULTI));
    }

    /** A JWT principal for an issuer (brand) + subject + roles — the real shape the
     *  service's {@code scopeOf} reads (issuer-derived domain + subject author). */
    private static Authentication jwtAuth(String issuer, String subject, String... roles) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "none")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
                .subject(subject).claim("iss", issuer).issuer(issuer)
                .claim("roles", List.of(roles));
        Jwt jwt = b.build();
        List<GrantedAuthority> auths = new java.util.ArrayList<>();
        auths.add(new SimpleGrantedAuthority("ROLE_USER"));
        for (String r : roles) auths.add(new SimpleGrantedAuthority("ROLE_" + r.toUpperCase()));
        return new UsernamePasswordAuthenticationToken(jwt, "n/a", auths);
    }

    private static WikiDto.CreatePage create(String slug, String title, String md) {
        return new WikiDto.CreatePage(slug, title, md);
    }

    // ── USER-SITUATIONAL ──────────────────────────────────────────────────────
    @Nested
    class UserSituational {

        @Test
        void create_rendersHtml_stampsAuthorAndDomain_audits() {
            String s = slug("us-create");
            Authentication admin = jwtAuth("https://id.brandx.example", "admin-x", "admin");

            WikiDto.Page page = service.create(create(s, "Hello", "# Hello"), admin).block();
            assertThat(page.contentHtml()).contains("<h1>Hello</h1>"); // rendered at write
            assertThat(page.authorSubject()).isEqualTo("admin-x");      // from JWT subject, not body
            assertThat(page.domain()).isEqualTo("brandx");              // from issuer, not body
            assertThat(page.version()).isEqualTo(1L);

            // The create was audited in the actor's brand.
            List<WikiAuditLog> trail = audit.list("brandx").collectList().block();
            assertThat(trail).anySatisfy(a -> assertThat(a.action()).isEqualTo("wiki.page.create"));
        }

        @Test
        void update_bumpsVersion_andReRendersHtml() {
            String s = slug("us-update");
            Authentication admin = jwtAuth("https://id.brandx.example", "admin-x", "admin");
            service.create(create(s, "T1", "a"), admin).block();

            WikiDto.Page updated = service.update(s,
                    new WikiDto.UpdatePage("T2", "## b", 1L), admin).block();
            assertThat(updated.version()).isEqualTo(2L);
            assertThat(updated.contentHtml()).contains("<h2>b</h2>");
        }

        @Test
        void publish_setsIsPublishedAndPublishedAt_andAudits() {
            String s = slug("us-publish");
            Authentication admin = jwtAuth("https://id.brandx.example", "admin-x", "admin");
            service.create(create(s, "PubTest", "# Pub"), admin).block();

            // A newly created page is in DRAFT state (not published).
            assertThat(service.get(s, admin).block().published()).isFalse();
            assertThat(service.get(s, admin).block().publishedAt()).isNull();

            WikiDto.Page published = service.publish(s, admin).block();
            assertThat(published).isNotNull();
            assertThat(published.published()).isTrue();
            assertThat(published.publishedAt()).isNotNull();

            // The publish was audited.
            List<WikiAuditLog> trail = audit.list("brandx").collectList().block();
            assertThat(trail).anySatisfy(a -> assertThat(a.action()).isEqualTo("wiki.page.publish"));
        }

        @Test
        void publish_unknownSlug_returns404() {
            Authentication admin = jwtAuth("https://id.brandx.example", "admin-x", "admin");
            StepVerifier.create(service.publish("no-such-slug-" + SEQ.incrementAndGet(), admin))
                    .expectErrorSatisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(404))
                    .verify();
        }
    }

    // ── CROSS-USER (ALLOW + DENY isolation) ───────────────────────────────────
    @Nested
    class CrossUser {

        /** ALLOW: the authoring brand reads its own page back. DENY: another brand's
         *  caller gets a 404 (the page is invisible, not leaked as a 403). */
        @Test
        void brandX_page_isInvisibleToBrandY() {
            String s = slug("cu-iso");
            Authentication adminX = jwtAuth("https://id.brandx.example", "admin-x", "admin");
            Authentication adminY = jwtAuth("https://id.brandy.example", "admin-y", "admin");
            service.create(create(s, "X-only", "secret"), adminX).block();

            // ALLOW — brand X sees its own page.
            assertThat(service.get(s, adminX).block().title()).isEqualTo("X-only");
            // DENY — brand Y cannot see brand X's page (404, the isolation boundary).
            StepVerifier.create(service.get(s, adminY))
                    .expectErrorSatisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(404))
                    .verify();
        }

        /** The same slug is independently creatable per brand (no cross-brand conflict). */
        @Test
        void sameSlug_createdIndependentlyPerBrand() {
            String s = slug("cu-sameSlug");
            Authentication adminX = jwtAuth("https://id.brandx.example", "admin-x", "admin");
            Authentication adminY = jwtAuth("https://id.brandy.example", "admin-y", "admin");

            service.create(create(s, "X title", "x"), adminX).block();
            // brand Y creating the SAME slug is NOT a conflict (different brand).
            assertThat(service.create(create(s, "Y title", "y"), adminY).block().title())
                    .isEqualTo("Y title");
            assertThat(service.get(s, adminX).block().title()).isEqualTo("X title");
        }

        /** A second create of the SAME slug WITHIN a brand is a 409 conflict. */
        @Test
        void duplicateSlug_inSameBrand_is409() {
            String s = slug("cu-dup");
            Authentication adminX = jwtAuth("https://id.brandx.example", "admin-x", "admin");
            service.create(create(s, "first", "a"), adminX).block();

            StepVerifier.create(service.create(create(s, "second", "b"), adminX))
                    .expectErrorSatisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(409))
                    .verify();
        }

        /** SUPERADMIN (null-domain bypass) reads across brands. */
        @Test
        void superadmin_seesAcrossBrands() {
            String sx = slug("cu-su-x");
            Authentication adminX = jwtAuth("https://id.brandx.example", "admin-x", "admin");
            Authentication su = jwtAuth("https://id.brandx.example", "root", "superadmin");
            service.create(create(sx, "X page", "x"), adminX).block();
            // SUPERADMIN resolves the slug regardless of the authoring brand.
            assertThat(service.get(sx, su).block().title()).isEqualTo("X page");
        }
    }

    // ── DELETE SCOPING (issue #15) — over the REAL store, so the FK cascade + the audit
    //    stamp are exercised, not mocked ───────────────────────────────────────────────
    @Nested
    class DeleteScoping {

        private final Authentication adminX = jwtAuth("https://id.brandx.example", "admin-x", "admin");
        private final Authentication adminY = jwtAuth("https://id.brandy.example", "admin-y", "admin");
        private final Authentication su = jwtAuth("https://id.brandx.example", "root", "superadmin");

        private String attach(String pageId, String domain, String name) {
            return attachments.insert(pageId, domain, name, "image/png", new byte[] {1, 2, 3}).block().id();
        }

        private void assertDeleteAuditedIn(String domain, String slug) {
            assertThat(audit.list(domain).collectList().block())
                    .as("wiki.page.delete for %s audited under %s", slug, domain)
                    .anySatisfy(a -> {
                        assertThat(a.action()).isEqualTo("wiki.page.delete");
                        assertThat(a.target()).isEqualTo(slug);
                    });
        }

        /** The issue's headline case: the page lives in a NON-default brand and NO default-brand
         *  row shares its slug. The old delete ran {@code WHERE domain='default'}, removed 0 rows
         *  and still completed as a success — with the attachments already destroyed. */
        @Test
        void superadmin_deletesNonDefaultBrandPage_pageAndAttachmentsGone_auditedInThatBrand() {
            String s = slug("del-su-y");
            WikiDto.Page page = service.create(create(s, "Y runbook", "y"), adminY).block();
            String a1 = attach(page.id(), "brandy", "one.png");
            String a2 = attach(page.id(), "brandy", "two.png");

            service.delete(s, su).block();

            assertThat(pages.findBySlug(s, "brandy").blockOptional()).as("the brand-Y page is gone").isEmpty();
            assertThat(attachmentData.existsById(a1).block()).as("attachment 1 reaped by the FK cascade").isFalse();
            assertThat(attachmentData.existsById(a2).block()).as("attachment 2 reaped by the FK cascade").isFalse();
            assertDeleteAuditedIn("brandy", s);
            assertThat(audit.list("default").collectList().block())
                    .as("nothing about this page is audited under 'default'")
                    .noneMatch(a -> s.equals(a.target()));
        }

        /** The collateral case from the issue thread: a {@code default}-brand row shares the
         *  slug. It is planted with an id that sorts LAST, so the SUPERADMIN resolve (first by
         *  id) deterministically picks the brand-Y row — the old code would then have deleted
         *  the default row instead and reported success. */
        @Test
        void superadmin_sameSlugInDefault_deletesOnlyTheResolvedRow_defaultTwinSurvives() {
            String s = slug("del-su-twin");
            WikiDto.Page target = service.create(create(s, "Y page", "y"), adminY).block();
            String targetAtt = attach(target.id(), "brandy", "y.png");
            String twinId = "ffffffff-ffff-4fff-8fff-" + String.format("%012x", SEQ.incrementAndGet());
            db.sql("INSERT INTO wiki_page (id, domain, slug, title, content_markdown, content_html, author_subject) "
                            + "VALUES (:id, 'default', :slug, 'Default twin', 'd', '<p>d</p>', '@system')")
                    .bind("id", twinId).bind("slug", s).fetch().rowsUpdated().block();
            String twinAtt = attach(twinId, "default", "d.png");

            // The cross-brand resolve the delete performs is deterministic here (target id < twin id).
            assertThat(pages.findBySlug(s, null).block().id()).isEqualTo(target.id());

            service.delete(s, su).block();

            assertThat(pages.findById(target.id(), null).blockOptional()).as("the resolved brand-Y row is gone").isEmpty();
            assertThat(attachmentData.existsById(targetAtt).block()).as("its attachment went with it").isFalse();
            assertThat(pages.findById(twinId, null).blockOptional()).as("default's same-slug page is untouched").isPresent();
            assertThat(attachmentData.existsById(twinAtt).block()).as("default's attachment is untouched").isTrue();
            assertDeleteAuditedIn("brandy", s);
            assertThat(audit.list("default").collectList().block()).noneMatch(a -> s.equals(a.target()));
        }

        /** Two brands share the slug: whichever row the cross-brand resolve picks is the ONLY
         *  row removed, and the other keeps its attachment. */
        @Test
        void superadmin_sameSlugInTwoBrands_removesExactlyTheResolvedRow() {
            String s = slug("del-su-pair");
            WikiDto.Page px = service.create(create(s, "X", "x"), adminX).block();
            WikiDto.Page py = service.create(create(s, "Y", "y"), adminY).block();
            String ax = attach(px.id(), "brandx", "x.png");
            String ay = attach(py.id(), "brandy", "y.png");
            WikiPage resolved = pages.findBySlug(s, null).block();
            boolean xResolved = resolved.id().equals(px.id());
            String otherId = xResolved ? py.id() : px.id();
            String resolvedAtt = xResolved ? ax : ay;
            String otherAtt = xResolved ? ay : ax;

            service.delete(s, su).block();

            assertThat(pages.findById(resolved.id(), null).blockOptional()).as("the resolved row is gone").isEmpty();
            assertThat(attachmentData.existsById(resolvedAtt).block()).isFalse();
            assertThat(pages.findById(otherId, null).blockOptional()).as("the other brand's row survives").isPresent();
            assertThat(attachmentData.existsById(otherAtt).block()).as("with its attachment").isTrue();
            assertDeleteAuditedIn(resolved.domain(), s);
        }

        /** A scoped ADMIN still cannot reach another brand's page through delete: 404, and
         *  neither the page nor its attachment is touched. */
        @Test
        void brandXAdmin_cannotDeleteBrandYPage_404_nothingRemoved() {
            String s = slug("del-x-vs-y");
            WikiDto.Page py = service.create(create(s, "Y only", "y"), adminY).block();
            String ay = attach(py.id(), "brandy", "y.png");

            StepVerifier.create(service.delete(s, adminX))
                    .expectErrorSatisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(404))
                    .verify();

            assertThat(pages.findById(py.id(), null).blockOptional()).isPresent();
            assertThat(attachmentData.existsById(ay).block()).isTrue();
        }
    }

    // ── CONCURRENT-CONFLICTING ────────────────────────────────────────────────
    @Nested
    class ConcurrentConflicting {

        /** Two racing updates at the SAME expectedVersion: exactly one succeeds and
         *  bumps the version to 2; the other is a 409 stale-edit. The version is bumped
         *  exactly once (never double-applied). */
        @Test
        void concurrentUpdatesAtSameVersion_oneWins_otherIs409() {
            String s = slug("cc-update");
            Authentication admin = jwtAuth("https://id.brandx.example", "admin-x", "admin");
            service.create(create(s, "T0", "v0"), admin).block();

            int racers = 12;
            // Each racer claims expectedVersion=1; one wins, the rest 409 (stale).
            Long results = Flux.range(0, racers)
                    .flatMap(i -> service.update(s, new WikiDto.UpdatePage("T" + i, "v" + i, 1L), admin)
                            .map(p -> 1L)
                            .onErrorResume(ApiException.class, e -> {
                                assertThat(e.status().value()).isEqualTo(409); // stale-edit
                                return reactor.core.publisher.Mono.just(0L);
                            }))
                    .reduce(0L, Long::sum)
                    .block(Duration.ofSeconds(30));

            assertThat(results).isEqualTo(1L);   // exactly one winner
            assertThat(service.get(s, admin).block().version()).isEqualTo(2L); // bumped once
        }
    }
}
