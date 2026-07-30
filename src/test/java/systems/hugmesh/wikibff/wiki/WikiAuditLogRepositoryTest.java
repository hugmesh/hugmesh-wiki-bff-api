package systems.hugmesh.wikibff.wiki;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.context.annotation.Import;
import systems.hugmesh.commons.testsupport.PostgresTestSupport;
import systems.hugmesh.wikibff.support.TestDatabaseConfig;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R2DBC slice test for {@link WikiAuditLogRepository} over a Testcontainers-Postgres store
 * (commons {@link PostgresTestSupport}) that Liquibase applies the REAL production changelog
 * over (the {@code wiki_audit_log} table + changelog 006's DB-generated-id DEFAULT). Verifies the
 * record/list round-trip AND the multi-brand DATA-LAYER isolation boundary: a scoped
 * admin of brand X reads ONLY brand X's audit trail, SUPERADMIN ({@code null} domain)
 * reads every brand's, and the {@code __deny__} sentinel reads NOTHING.
 *
 * <p>Also verifies the BOUND on the unbounded read (the {@code GET /bff/wiki/superadmin/audit}
 * full-table-drain finding): the {@code list(domain, limit)} read is a newest-first scan
 * capped at the clamped page size — a request can never widen it past {@link
 * WikiAuditLogRepository#MAX_LIMIT}, an unset/non-positive limit falls back to {@link
 * WikiAuditLogRepository#DEFAULT_LIMIT}, and the cap keeps the NEWEST rows (ORDER BY
 * created_at DESC LIMIT), not an arbitrary slice.
 */
@DataR2dbcTest
@Import({TestDatabaseConfig.class, WikiAuditLogRepository.class})
class WikiAuditLogRepositoryTest extends PostgresTestSupport {

    @Autowired WikiAuditLogRepository repo;

    private static final AtomicInteger SEQ = new AtomicInteger();
    private String actor(String tag) { return tag + "-" + SEQ.incrementAndGet(); }

    @Test
    void record_thenList_roundTrips() {
        String a = actor("rt");
        WikiAuditLog saved = repo.record(a, "wiki.page.create", "home", "v1", "brandx").block();
        assertThat(saved.id()).isNotBlank();
        assertThat(saved.action()).isEqualTo("wiki.page.create");
        assertThat(saved.domain()).isEqualTo("brandx");
        assertThat(saved.createdAt()).isNotNull();

        List<WikiAuditLog> brandx = repo.list("brandx").collectList().block();
        assertThat(brandx).anySatisfy(r -> assertThat(r.actorSubject()).isEqualTo(a));
    }

    @Test
    void list_domainScoped_neverCrossesBrands() {
        String tag = actor("iso");
        repo.record(tag + "-a", "wiki.page.create", "p", "v1", tag + "-bx").block();
        repo.record(tag + "-b", "wiki.page.create", "p", "v1", tag + "-by").block();

        assertThat(repo.list(tag + "-bx").collectList().block())
                .allSatisfy(r -> assertThat(r.domain()).isEqualTo(tag + "-bx"));
        assertThat(repo.list(tag + "-by").collectList().block())
                .allSatisfy(r -> assertThat(r.domain()).isEqualTo(tag + "-by"));
    }

    @Test
    void superadmin_nullDomain_readsEveryBrand() {
        String a = actor("su");
        repo.record(a, "wiki.page.update", "p1", "v2", "su-bx").block();
        repo.record(a, "wiki.page.update", "p2", "v2", "su-by").block();

        List<WikiAuditLog> all = repo.list(null).collectList().block();
        assertThat(all).filteredOn(r -> r.actorSubject().equals(a))
                .extracting(WikiAuditLog::domain).contains("su-bx", "su-by");
    }

    @Test
    void denySentinelScope_readsNothing() {
        repo.record(actor("deny"), "wiki.page.create", "p", "v1", "brandx").block();
        assertThat(repo.list("__deny__").collectList().block()).isEmpty();
    }

    // ── BOUND on the previously-unbounded read (the full-table-drain finding) ──

    @Test
    void list_capsAtRequestedLimit_andKeepsTheNewestRows() {
        String dom = "cap-bx-" + SEQ.incrementAndGet();
        // 5 OLDER rows, then a timestamp gap, then 3 NEWER rows. The cap (limit=3) must
        // return exactly the 3 NEWEST — the ORDER BY created_at DESC LIMIT, not a head slice.
        for (int i = 0; i < 5; i++) {
            repo.record(actor("old"), "wiki.page.create", "old-" + i, "v1", dom).block();
        }
        sleepMillis(20); // guarantee a created_at gap so "newest 3" is deterministic
        Instant boundary = Instant.now();
        for (int i = 0; i < 3; i++) {
            repo.record(actor("new"), "wiki.page.update", "new-" + i, "v2", dom).block();
        }

        List<WikiAuditLog> capped = repo.list(dom, 3).collectList().block();

        assertThat(capped).hasSize(3);
        // The cap kept the NEWEST rows (all on/after the boundary), not the older ones.
        assertThat(capped).allSatisfy(r ->
                assertThat(r.createdAt()).isAfterOrEqualTo(boundary));
        assertThat(capped).extracting(WikiAuditLog::action)
                .containsOnly("wiki.page.update");
    }

    @Test
    void list_isNewestFirst() {
        String dom = "order-bx-" + SEQ.incrementAndGet();
        for (int i = 0; i < 4; i++) {
            repo.record(actor("ord"), "wiki.page.create", "p-" + i, "v" + i, dom).block();
            sleepMillis(5); // distinct created_at per row
        }

        List<WikiAuditLog> trail = repo.list(dom, 10).collectList().block();

        assertThat(trail).hasSize(4);
        // Newest first: each row's createdAt is >= the next row's (non-increasing).
        assertThat(trail).isSortedAccordingTo(
                (a, b) -> b.createdAt().compareTo(a.createdAt()));
    }

    @Test
    void list_requestOverMaxLimit_isClampedToMaxNotHonoured() {
        String dom = "clampmax-bx-" + SEQ.incrementAndGet();
        // A handful of rows is plenty: assert the HARD cap, not row count. A caller asking
        // for MAX_LIMIT + 1000 must NOT be able to widen the read past MAX_LIMIT.
        for (int i = 0; i < 3; i++) {
            repo.record(actor("cm"), "wiki.page.create", "p-" + i, "v1", dom).block();
        }
        int requested = WikiAuditLogRepository.MAX_LIMIT + 1000;

        // With only 3 rows the result is 3 either way; the load-bearing guarantee is that the
        // SQL LIMIT bound the repo applies is the clamped MAX_LIMIT, never the caller's number.
        List<WikiAuditLog> rows = repo.list(dom, requested).collectList().block();
        assertThat(rows).hasSize(3);
        assertThat(WikiAuditLogRepository.clampForTest(requested))
                .isEqualTo(WikiAuditLogRepository.MAX_LIMIT);
    }

    @Test
    void list_nonPositiveLimit_fallsBackToDefault() {
        assertThat(WikiAuditLogRepository.clampForTest(0))
                .isEqualTo(WikiAuditLogRepository.DEFAULT_LIMIT);
        assertThat(WikiAuditLogRepository.clampForTest(-5))
                .isEqualTo(WikiAuditLogRepository.DEFAULT_LIMIT);
        assertThat(WikiAuditLogRepository.clampForTest(50)).isEqualTo(50);
    }

    private static void sleepMillis(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while spacing audit timestamps", e);
        }
    }
}
