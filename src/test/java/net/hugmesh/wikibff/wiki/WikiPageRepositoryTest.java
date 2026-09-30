package net.hugmesh.wikibff.wiki;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.context.annotation.Import;
import reactor.core.publisher.Flux;
import net.hugmesh.commons.testsupport.PostgresTestSupport;
import net.hugmesh.wikibff.support.TestDatabaseConfig;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R2DBC slice test for {@link WikiPageRepository} over a Testcontainers-Postgres store
 * (commons {@link PostgresTestSupport}) that Liquibase applies the REAL production changelog
 * over (the {@code wiki_page} table with the {@code (domain,slug)} UNIQUE constraint + the
 * {@code version} column + changelog 006's DB-generated-id DEFAULT). Delineated per the test
 * standard:
 *
 * <ul>
 *   <li><b>USER-SITUATIONAL</b> — create/get/update round-trip; a write stamps + reads
 *       back in its own brand; the update bumps version.</li>
 *   <li><b>CROSS-USER (ISOLATION DENY + domain isolation)</b> — a page in brand X is
 *       invisible to brand Y's scope; the SAME slug exists independently per brand; the
 *       {@code __deny__} sentinel reads NOTHING; SUPERADMIN (null domain) reads every
 *       brand's.</li>
 *   <li><b>CONCURRENT-CONFLICTING</b> — two racing updates at the SAME expectedVersion:
 *       exactly one wins (the conditional version-guarded UPDATE), the loser updates
 *       zero rows; the final version is bumped exactly once.</li>
 * </ul>
 */
@DataR2dbcTest
@Import({TestDatabaseConfig.class, WikiPageRepository.class})
class WikiPageRepositoryTest extends PostgresTestSupport {

    @Autowired WikiPageRepository repo;

    /** Unique-per-test keys so the shared @DataR2dbcTest context's rows from sibling
     *  tests never bleed into a count. */
    private static final AtomicInteger SEQ = new AtomicInteger();
    private String slug(String tag) { return tag + "-" + SEQ.incrementAndGet(); }

    // ── USER-SITUATIONAL ──────────────────────────────────────────────────────
    @Nested
    class UserSituational {

        @Test
        void create_thenFindBySlug_roundTrips() {
            String s = slug("us-create");
            WikiPage saved = repo.create(s, "Title", "# md", "<h1>md</h1>", "author-1", "brandx").block();
            assertThat(saved.id()).isNotBlank();
            assertThat(saved.version()).isEqualTo(1L);
            assertThat(saved.domain()).isEqualTo("brandx");
            assertThat(saved.contentHtml()).isEqualTo("<h1>md</h1>");
            assertThat(saved.createdAt()).isNotNull();

            WikiPage got = repo.findBySlug(s, "brandx").block();
            assertThat(got).isNotNull();
            assertThat(got.title()).isEqualTo("Title");
            assertThat(got.authorSubject()).isEqualTo("author-1");
        }

        @Test
        void update_bumpsVersionAndReplacesContent() {
            String s = slug("us-update");
            WikiPage created = repo.create(s, "T1", "a", "<p>a</p>", "author-1", "brandx").block();

            WikiPage updated = repo.updateById(created.id(), "T2", "b", "<p>b</p>", "author-2", 1L).block();
            assertThat(updated).isNotNull();
            assertThat(updated.version()).isEqualTo(2L);
            assertThat(updated.title()).isEqualTo("T2");
            assertThat(updated.contentHtml()).isEqualTo("<p>b</p>");
            assertThat(updated.authorSubject()).isEqualTo("author-2");
        }

        @Test
        void publishById_setsIsPublishedAndPublishedAt() {
            String s = slug("us-publish");
            WikiPage created = repo.create(s, "T", "m", "<p>m</p>", "author-1", "brandx").block();

            // Newly created page is in DRAFT state.
            WikiPage draft = repo.findBySlug(s, "brandx").block();
            assertThat(draft.published()).isFalse();
            assertThat(draft.publishedAt()).isNull();

            WikiPage pub = repo.publishById(created.id()).block();
            assertThat(pub).isNotNull();
            assertThat(pub.published()).isTrue();
            assertThat(pub.publishedAt()).isNotNull();
        }

        @Test
        void publishById_unknownId_returnsEmpty() {
            String absentId = "aaaaaaaa-aaaa-4aaa-8aaa-" + String.format("%012x", SEQ.incrementAndGet());
            assertThat(repo.publishById(absentId).blockOptional()).isEmpty();
        }

        @Test
        void update_staleVersion_updatesNothing() {
            String s = slug("us-stale");
            WikiPage created = repo.create(s, "T1", "a", "<p>a</p>", "author-1", "brandx").block();
            // The page is at version 1; an update claiming version 5 (stale) matches no row.
            assertThat(repo.updateById(created.id(), "T2", "b", "<p>b</p>", "author-2", 5L)
                    .blockOptional()).isEmpty();
            // The page is unchanged at version 1.
            assertThat(repo.findBySlug(s, "brandx").block().version()).isEqualTo(1L);
        }

        @Test
        void updateById_unknownId_returnsEmpty() {
            String absentId = "aaaaaaaa-aaaa-4aaa-8aaa-" + String.format("%012x", SEQ.incrementAndGet());
            assertThat(repo.updateById(absentId, "T", "m", "<p>m</p>", "author-1", 1L)
                    .blockOptional()).isEmpty();
        }
    }

    // ── CROSS-USER (ISOLATION DENY + domain isolation) ────────────────────────
    @Nested
    class CrossUser {

        @Test
        void page_isInvisibleFromAnotherBrandScope() {
            String s = slug("cu-iso");
            repo.create(s, "T", "a", "<p>a</p>", "author-1", "brandx").block();

            assertThat(repo.findBySlug(s, "brandx").block()).isNotNull();
            // The same slug under brandy's scope does not exist.
            assertThat(repo.findBySlug(s, "brandy").blockOptional()).isEmpty();
        }

        @Test
        void sameSlug_existsIndependentlyPerBrand() {
            String s = slug("cu-sameSlug");
            repo.create(s, "X title", "x", "<p>x</p>", "author-x", "brandx").block();
            repo.create(s, "Y title", "y", "<p>y</p>", "author-y", "brandy").block();

            assertThat(repo.findBySlug(s, "brandx").block().title()).isEqualTo("X title");
            assertThat(repo.findBySlug(s, "brandy").block().title()).isEqualTo("Y title");
        }

        @Test
        void list_domainScoped_neverCrossesBrands() {
            String s = slug("cu-list");
            repo.create(s + "-x", "Tx", "x", "<p>x</p>", "a", "iso-bx").block();
            repo.create(s + "-y", "Ty", "y", "<p>y</p>", "a", "iso-by").block();

            List<WikiPage> x = repo.list("iso-bx").collectList().block();
            List<WikiPage> y = repo.list("iso-by").collectList().block();
            assertThat(x).allSatisfy(p -> assertThat(p.domain()).isEqualTo("iso-bx"));
            assertThat(y).allSatisfy(p -> assertThat(p.domain()).isEqualTo("iso-by"));
        }

        @Test
        void denySentinelScope_readsNothing() {
            String s = slug("cu-deny");
            repo.create(s, "T", "a", "<p>a</p>", "author-1", "brandx").block();
            assertThat(repo.findBySlug(s, "__deny__").blockOptional()).isEmpty();
        }

        @Test
        void superadmin_nullDomain_seesEveryBrand() {
            String s = slug("cu-su");
            repo.create(s, "T", "a", "<p>a</p>", "author-1", "su-bx").block();
            // The null-domain bypass resolves the slug regardless of brand.
            WikiPage got = repo.findBySlug(s, null).block();
            assertThat(got).isNotNull();
            assertThat(got.domain()).isEqualTo("su-bx");
        }
    }

    // ── CONCURRENT-CONFLICTING (version-guarded update) ───────────────────────
    @Nested
    class ConcurrentConflicting {

        /** Two racing updates at the SAME expectedVersion (1): the conditional
         *  version-guarded UPDATE serializes them — exactly ONE wins and bumps the
         *  version to 2; the loser matches zero rows (a stale edit). The final version
         *  is bumped exactly once, never twice. */
        @Test
        void concurrentUpdatesAtSameVersion_exactlyOneWins() {
            String s = slug("cc-update");
            WikiPage created = repo.create(s, "T0", "v0", "<p>v0</p>", "author-0", "brandx").block();

            int racers = 20;
            // All racers claim expectedVersion=1; only the first to land the UPDATE wins.
            Long winners = Flux.range(0, racers)
                    .flatMap(i -> repo.updateById(created.id(), "T" + i, "v" + i, "<p>v" + i + "</p>",
                                    "author-" + i, 1L)
                            .map(p -> 1L).defaultIfEmpty(0L))
                    .reduce(0L, Long::sum)
                    .block(Duration.ofSeconds(30));

            assertThat(winners).isEqualTo(1L);    // exactly one racer's version-1 update landed
            // The page is at version 2 — bumped exactly once, never double-applied.
            assertThat(repo.findBySlug(s, "brandx").block().version()).isEqualTo(2L);
        }

        /** Many concurrent CREATEs across two brands keep each brand's list count
         *  correct (no row leaks across the domain predicate under contention). */
        @Test
        void concurrentCreatesAcrossBrands_keepPerBrandCounts() {
            String tag = slug("cc-create");
            int n = 40;
            Flux.range(0, n)
                    .flatMap(i -> repo.create(tag + "-" + i, "T" + i, "m", "<p>m</p>", "a",
                            (i % 2 == 0) ? tag + "-bx" : tag + "-by"))
                    .then().block(Duration.ofSeconds(30));

            assertThat(repo.list(tag + "-bx").collectList().block()).hasSize(n / 2);
            assertThat(repo.list(tag + "-by").collectList().block()).hasSize(n / 2);
        }
    }
}
