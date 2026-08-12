package net.hugmesh.wikibff.wiki;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.context.annotation.Import;
import net.hugmesh.commons.testsupport.PostgresTestSupport;
import net.hugmesh.wikibff.support.TestDatabaseConfig;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAW-API-DENY (data layer) — REAL cross-tenant isolation PROBE for the
 * {@code wiki_attachment} slug-keyed query path.
 *
 * <p><b>Why this suite exists.</b> The repository's {@code @Query} javadoc and
 * {@link WikiAttachmentRepository} class comment both flag a deliberate
 * <em>"no domain predicate / cross-domain bypass"</em> branch
 * ({@code FIND_ALL_BY_SLUG} / {@code FIND_BY_SLUG_AND_FILENAME}) reached when the
 * passed domain {@code isUnscoped} (null/blank). The org-synthesis HELD-question was:
 * can a domain-A (non-SUPERADMIN) caller EVER reach domain-B attachments through the
 * slug-keyed JOIN read path? The existing
 * {@code WikiAttachmentCrossDomainControllerTest} proves the controller/service pass
 * the issuer-derived scope, but it MOCKS the repository — so the ACTUAL SQL (the JOIN
 * to {@code wiki_page} + the {@code a.domain = :domain} predicate, and the un-pinned
 * bypass variant) is NEVER exercised against a database. This suite closes that gap
 * the GOLD-EXEMPLAR way: a REAL Liquibase-applied Testcontainers-Postgres schema (commons
 * {@link PostgresTestSupport}) with REAL rows, so a missing/leaky predicate in the literal
 * {@code @Query} string would surface here and nowhere else.
 *
 * <p><b>Threat model under test (CROSS-USER DENY + isolation).</b> Two brands own a
 * page at the SAME slug, each with its OWN attachments. A scoped (brand) caller
 * must:
 * <ul>
 *   <li>read ONLY its own brand's attachments by slug (list + by-filename) — the
 *       same-slug other-brand rows are INVISIBLE, not leaked;</li>
 *   <li>a same-FILENAME collision across brands must still resolve to the caller's own
 *       brand's bytes, never the other brand's;</li>
 *   <li>a {@code __deny__} sentinel scope (the {@link
 *       net.hugmesh.wikibff.security.PrincipalDomain} fail-closed value for a
 *       non-SUPERADMIN whose issuer can't resolve) reads NOTHING.</li>
 * </ul>
 * The {@code null} (SUPERADMIN) bypass IS intentionally cross-domain — asserted as the
 * documented exception (not a leak) so a future refactor that accidentally narrowed it
 * is also caught.
 *
 * <p><b>Mutation-resistant.</b> No re-implementation in-test — the assertions read
 * back through the REAL repository over the REAL schema. If the {@code domain}
 * predicate were dropped from {@code FIND_ALL_BY_SLUG_AND_DOMAIN} /
 * {@code FIND_BY_SLUG_FILENAME_AND_DOMAIN}, the scoped reads would start returning the
 * other brand's rows and these tests would FAIL (the cross-tenant leak the HELD
 * question asked about).
 */
@DataR2dbcTest
@Import({TestDatabaseConfig.class, WikiPageRepository.class, WikiAttachmentRepository.class})
class WikiAttachmentCrossTenantQueryDenyTest extends PostgresTestSupport {

    @Autowired WikiPageRepository pages;
    @Autowired WikiAttachmentRepository attachments;

    /** Unique-per-test keys so sibling tests sharing the @DataR2dbcTest context never bleed. */
    private static final AtomicInteger SEQ = new AtomicInteger();
    private String slug(String tag) { return tag + "-" + SEQ.incrementAndGet(); }

    /** Seed a page in {@code domain} at {@code slug} and attach {@code filename} with the
     *  given marker bytes; returns the persisted page id. */
    private String seedPageWithAttachment(String domain, String slug, String filename, byte[] data) {
        WikiPage page = pages.create(slug, "T-" + domain, "# md", "<h1>md</h1>", "author-" + domain, domain)
                .block();
        assertThat(page).isNotNull();
        attachments.insert(page.id(), domain, filename, "image/png", data).block();
        return page.id();
    }

    // ── CROSS-USER DENY — scoped LIST by slug never crosses brands ────────────
    @Nested
    class ScopedListBySlug {

        @Test
        void brandA_listBySlug_seesOnlyBrandA_notBrandB_sameSlug() {
            String s = slug("xt-list");
            // Same slug in BOTH brands, each with its own attachment.
            seedPageWithAttachment("xt-brandA", s, "a-secret.png", new byte[]{0x0A});
            seedPageWithAttachment("xt-brandB", s, "b-secret.png", new byte[]{0x0B});

            List<WikiAttachment> a = attachments.findAllBySlugAndDomain(s, "xt-brandA").collectList().block();
            assertThat(a).hasSize(1);
            assertThat(a.get(0).domain()).isEqualTo("xt-brandA");
            assertThat(a.get(0).filename()).isEqualTo("a-secret.png");
            // The cross-tenant DENY: brand-B's attachment is INVISIBLE to brand-A's slug list.
            assertThat(a).noneSatisfy(att -> assertThat(att.domain()).isEqualTo("xt-brandB"));

            // And symmetrically for brand-B.
            List<WikiAttachment> b = attachments.findAllBySlugAndDomain(s, "xt-brandB").collectList().block();
            assertThat(b).hasSize(1);
            assertThat(b.get(0).domain()).isEqualTo("xt-brandB");
            assertThat(b.get(0).filename()).isEqualTo("b-secret.png");
        }

        @Test
        void scopedListBySlug_returnsEmptyWhenSlugOwnedByOtherBrandOnly() {
            String s = slug("xt-listOther");
            // Slug exists ONLY in brand-B.
            seedPageWithAttachment("xt-onlyB", s, "f.png", new byte[]{0x0B});

            // A brand-A caller's scoped slug list finds NOTHING (the slug+attachment are
            // brand-B's; the JOIN + a.domain predicate excludes them).
            assertThat(attachments.findAllBySlugAndDomain(s, "xt-otherA").collectList().block())
                    .as("a brand-A scoped slug list cannot see a brand-B-only page's attachments")
                    .isEmpty();
        }
    }

    // ── CROSS-USER DENY — scoped DOWNLOAD by slug+filename never crosses brands ─
    @Nested
    class ScopedDownloadBySlugFilename {

        @Test
        void brandA_downloadBySlugFilename_returnsEmptyForBrandBOnlyFile() {
            String s = slug("xt-dl");
            seedPageWithAttachment("xt-dlB", s, "b-only.png", new byte[]{0x0B});

            // The file lives only in brand-B; a brand-A scoped lookup is EMPTY (404 at the
            // service), never the brand-B bytes.
            assertThat(attachments.findBySlugAndFilename(s, "xt-dlA", "b-only.png").blockOptional())
                    .as("a brand-A caller cannot download a brand-B-only attachment by slug+filename")
                    .isEmpty();
        }

        @Test
        void sameSlugSameFilenameCollision_resolvesToCallerOwnBrandBytes_neverOther() {
            String s = slug("xt-collide");
            // SAME slug AND SAME filename in two brands, but DIFFERENT bytes — the strongest
            // leak probe: a dropped a.domain predicate would let one brand read the other's
            // bytes (a non-deterministic / cross-tenant content leak).
            seedPageWithAttachment("xt-collA", s, "shared.png", new byte[]{(byte) 0xAA});
            seedPageWithAttachment("xt-collB", s, "shared.png", new byte[]{(byte) 0xBB});

            WikiAttachment a = attachments.findBySlugAndFilename(s, "xt-collA", "shared.png").block();
            WikiAttachment b = attachments.findBySlugAndFilename(s, "xt-collB", "shared.png").block();
            assertThat(a).isNotNull();
            assertThat(b).isNotNull();
            assertThat(a.domain()).isEqualTo("xt-collA");
            assertThat(b.domain()).isEqualTo("xt-collB");
            assertThat(a.data()).containsExactly(0xAA);
            assertThat(b.data())
                    .as("each brand reads ITS OWN bytes for the colliding slug+filename, never the other's")
                    .containsExactly(0xBB);
            assertThat(a.data()).isNotEqualTo(b.data());
        }
    }

    // ── CROSS-USER DENY — the __deny__ fail-closed sentinel reads NOTHING ──────
    @Nested
    class DenySentinel {

        @Test
        void denySentinelScope_listBySlug_readsNothing() {
            String s = slug("xt-deny");
            seedPageWithAttachment("xt-denyReal", s, "f.png", new byte[]{0x01});
            // __deny__ is the PrincipalDomain fail-closed value for a non-SUPERADMIN whose
            // issuer can't resolve. It is a concrete (non-null) domain, so it takes the
            // SCOPED query path and matches no row.
            assertThat(attachments.findAllBySlugAndDomain(s, "__deny__").collectList().block()).isEmpty();
            assertThat(attachments.findBySlugAndFilename(s, "__deny__", "f.png").blockOptional()).isEmpty();
        }
    }

    // ── DOCUMENTED EXCEPTION — the null (SUPERADMIN) bypass IS cross-domain ─────
    @Nested
    class SuperadminBypassIsIntentional {

        @Test
        void nullScope_listBySlug_seesEveryBrand_documentedSuperadminBypass() {
            String s = slug("xt-su");
            seedPageWithAttachment("xt-suA", s, "a.png", new byte[]{0x0A});
            seedPageWithAttachment("xt-suB", s, "b.png", new byte[]{0x0B});

            // null == SUPERADMIN cross-domain bypass: the un-pinned FIND_ALL_BY_SLUG sees
            // BOTH brands. Asserted so a refactor that broke the documented bypass is caught
            // (and so the non-null DENY above is clearly the deliberate boundary, not luck).
            List<WikiAttachment> all = attachments.findAllBySlugAndDomain(s, null).collectList().block();
            assertThat(all).hasSize(2);
            assertThat(all).extracting(WikiAttachment::domain)
                    .containsExactlyInAnyOrder("xt-suA", "xt-suB");
        }
    }
}
