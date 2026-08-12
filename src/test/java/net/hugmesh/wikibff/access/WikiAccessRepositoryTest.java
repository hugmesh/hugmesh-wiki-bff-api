package net.hugmesh.wikibff.access;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.context.annotation.Import;
import reactor.core.publisher.Flux;
import net.hugmesh.commons.testsupport.PostgresTestSupport;
import net.hugmesh.wikibff.support.TestDatabaseConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R2DBC slice test for {@link WikiAccessRepository} over a Testcontainers-Postgres store
 * (commons {@link PostgresTestSupport}) that Liquibase applies the REAL production changelog
 * over (the {@code 004-wiki-access} table + changelog 006's DB-generated-id DEFAULT).
 * Delineated per the test standard:
 *
 * <ul>
 *   <li><b>USER-SITUATIONAL</b> — insert active grant/ban round-trips; CAS active->revoked
 *       stamps revoked_at and is the only transition (the row is RETAINED = audit, never
 *       deleted); a timed ban carries a future expires_at.</li>
 *   <li><b>CROSS-USER (DENY isolation + domain isolation)</b> — an override in brand X is
 *       invisible to brand Y's scope; findActive is brand-scoped; SUPERADMIN (null domain)
 *       reads every brand's.</li>
 *   <li><b>CONCURRENT-CONFLICTING</b> — many concurrent revokes of one row: exactly one
 *       CAS wins (active->revoked), the rest update zero rows.</li>
 * </ul>
 *
 * <p>Moved off the H2 PostgreSQL-compat store ({@code TestDatabaseConfig}) to
 * Testcontainers-Postgres because the DB-generated-id migration (changelog 006) needs
 * {@code gen_random_uuid()} + {@code INSERT ... RETURNING id}, neither of which H2 provides.
 * The partial-unique invariant (one ACTIVE row per (domain,user,type)) is a PG-only partial
 * index that now applies for real here; the at-most-one-active invariant is also enforced by
 * the SERVICE, exercised in {@link WikiAccessServiceTest}. Here we prove the audit-retain +
 * CAS + domain-scope semantics over the real Liquibase-applied schema.
 */
@DataR2dbcTest
@Import({TestDatabaseConfig.class, WikiAccessRepository.class})
class WikiAccessRepositoryTest extends PostgresTestSupport {

    @Autowired WikiAccessRepository repo;

    private static final AtomicInteger SEQ = new AtomicInteger();
    private String user(String tag) { return tag + "-" + SEQ.incrementAndGet(); }

    // ── USER-SITUATIONAL ──────────────────────────────────────────────────────
    @Nested
    class UserSituational {

        @Test
        void insertActiveGrant_roundTrips() {
            String u = user("us-grant");
            WikiAccess a = repo.insertActive(u, WikiAccess.TYPE_READ, null, "welcome", "admin-x", "brandx").block();
            assertThat(a.id()).isNotBlank();
            assertThat(a.isActive()).isTrue();
            assertThat(a.accessType()).isEqualTo(WikiAccess.TYPE_READ);
            assertThat(a.expiresAt()).isNull();           // permanent grant
            assertThat(a.domain()).isEqualTo("brandx");
            assertThat(a.grantedBySubject()).isEqualTo("admin-x");

            WikiAccess found = repo.findActive(u, WikiAccess.TYPE_READ, "brandx").block();
            assertThat(found).isNotNull();
            assertThat(found.id()).isEqualTo(a.id());
        }

        @Test
        void timedBan_carriesFutureExpiry() {
            String u = user("us-timeout");
            Instant exp = Instant.now().plusSeconds(3600);
            WikiAccess a = repo.insertActive(u, WikiAccess.TYPE_BANNED, exp, "cooldown", "admin-x", "brandx").block();
            assertThat(a.accessType()).isEqualTo(WikiAccess.TYPE_BANNED);
            assertThat(a.expiresAt()).isNotNull();
            assertThat(a.isEffectiveAt(Instant.now())).isTrue();              // in force now
            assertThat(a.isEffectiveAt(exp.plusSeconds(1))).isFalse();        // auto-restored after TTL
        }

        @Test
        void revoke_CASesActiveToRevoked_andRowIsRetainedAsAudit() {
            String u = user("us-revoke");
            WikiAccess a = repo.insertActive(u, WikiAccess.TYPE_BANNED, null, "bad", "admin-x", "brandx").block();

            assertThat(repo.revoke(a.id()).block()).isEqualTo(1L);   // CAS won
            // No longer active...
            assertThat(repo.findActive(u, WikiAccess.TYPE_BANNED, "brandx").blockOptional()).isEmpty();
            // ...but the row is RETAINED (state=revoked, revoked_at stamped) = audit trail.
            List<WikiAccess> hist = repo.historyFor(u, "brandx").collectList().block();
            assertThat(hist).hasSize(1);
            assertThat(hist.get(0).state()).isEqualTo(WikiAccess.STATE_REVOKED);
            assertThat(hist.get(0).revokedAt()).isNotNull();
        }

        @Test
        void revoke_ofAlreadyRevoked_isNoOp() {
            String u = user("us-revoke2");
            WikiAccess a = repo.insertActive(u, WikiAccess.TYPE_READ, null, null, "admin-x", "brandx").block();
            assertThat(repo.revoke(a.id()).block()).isEqualTo(1L);
            assertThat(repo.revoke(a.id()).block()).isEqualTo(0L);  // already revoked — CAS matches nothing
        }
    }

    // ── CROSS-USER (DENY isolation + domain isolation) ────────────────────────
    @Nested
    class CrossUser {

        @Test
        void override_isInvisibleFromAnotherBrandScope() {
            String u = user("cu-iso");
            repo.insertActive(u, WikiAccess.TYPE_READ, null, null, "admin-x", "brandx").block();
            assertThat(repo.findActive(u, WikiAccess.TYPE_READ, "brandx").block()).isNotNull();
            // brandy's scope cannot see brandx's override.
            assertThat(repo.findActive(u, WikiAccess.TYPE_READ, "brandy").blockOptional()).isEmpty();
            assertThat(repo.activeFor(u, "brandy").collectList().block()).isEmpty();
        }

        @Test
        void sameUser_independentOverridesPerBrand() {
            String u = user("cu-perbrand");
            repo.insertActive(u, WikiAccess.TYPE_BANNED, null, "x-ban", "admin-x", "brandx").block();
            repo.insertActive(u, WikiAccess.TYPE_READ, null, "y-grant", "admin-y", "brandy").block();

            assertThat(repo.findActive(u, WikiAccess.TYPE_BANNED, "brandx").block()).isNotNull();
            assertThat(repo.findActive(u, WikiAccess.TYPE_BANNED, "brandy").blockOptional()).isEmpty();
            assertThat(repo.findActive(u, WikiAccess.TYPE_READ, "brandy").block()).isNotNull();
        }

        @Test
        void superadmin_nullDomain_seesEveryBrand() {
            String u = user("cu-su");
            repo.insertActive(u, WikiAccess.TYPE_READ, null, null, "admin-x", "su-bx").block();
            // The null-domain bypass resolves the override regardless of brand.
            WikiAccess got = repo.findActive(u, WikiAccess.TYPE_READ, null).block();
            assertThat(got).isNotNull();
            assertThat(got.domain()).isEqualTo("su-bx");
        }

        @Test
        void listActive_domainScoped_neverCrossesBrands() {
            String u = user("cu-list");
            repo.insertActive(u + "-x", WikiAccess.TYPE_READ, null, null, "a", "isl-bx").block();
            repo.insertActive(u + "-y", WikiAccess.TYPE_READ, null, null, "a", "isl-by").block();

            assertThat(repo.listActive("isl-bx").collectList().block())
                    .allSatisfy(a -> assertThat(a.domain()).isEqualTo("isl-bx"));
            assertThat(repo.listActive("isl-by").collectList().block())
                    .allSatisfy(a -> assertThat(a.domain()).isEqualTo("isl-by"));
        }
    }

    // ── CONCURRENT-CONFLICTING (CAS revoke) ───────────────────────────────────
    @Nested
    class ConcurrentConflicting {

        /** Many concurrent revokes of ONE active row: the CAS active->revoked serializes
         *  them — exactly ONE wins (rows-updated 1), the rest match zero rows. */
        @Test
        void concurrentRevokes_exactlyOneWins() {
            String u = user("cc-revoke");
            WikiAccess a = repo.insertActive(u, WikiAccess.TYPE_BANNED, null, null, "admin-x", "brandx").block();

            int racers = 20;
            Long winners = Flux.range(0, racers)
                    .flatMap(i -> repo.revoke(a.id()))
                    .reduce(0L, Long::sum)
                    .block(Duration.ofSeconds(30));

            assertThat(winners).isEqualTo(1L);   // exactly one CAS landed active->revoked
            assertThat(repo.historyFor(u, "brandx").collectList().block()).hasSize(1);  // one row, retained
        }
    }
}
