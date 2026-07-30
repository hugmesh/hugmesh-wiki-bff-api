package systems.hugmesh.wikibff.access;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import systems.hugmesh.commons.security.DomainRegistry;
import systems.hugmesh.wikibff.security.PrincipalDomain;
import systems.hugmesh.commons.testsupport.PostgresTestSupport;
import systems.hugmesh.wikibff.support.TestDatabaseConfig;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wiki-access write service over the REAL repo + a multi-brand {@link PrincipalDomain}
 * (so the issuer-derived domain scoping of every grant/timeout/ban/revoke is exercised end
 * to end). Delineated per the test standard:
 *
 * <ul>
 *   <li><b>USER-SITUATIONAL</b> — grant writes an active read row stamped with the actor's
 *       brand + subject; timeout writes a banned row with a future expiry; ban writes a
 *       permanent banned row; revoke CASes active->revoked (audit-retained).</li>
 *   <li><b>MULTIUSER / idempotency</b> — a re-grant/re-ban while active is a no-op (the same
 *       row), so at most one active row of a type exists per (brand,user) — the SERVICE's
 *       findActive-before-insert guard, now backed by the real PG partial-unique index
 *       (the slice runs on Testcontainers-Postgres).</li>
 *   <li><b>CROSS-USER (DENY isolation)</b> — an admin of brand X cannot revoke brand Y's
 *       override (it is invisible to its scope); SUPERADMIN acts cross-domain.</li>
 * </ul>
 */
@DataR2dbcTest
@Import({TestDatabaseConfig.class, WikiAccessRepository.class})
class WikiAccessServiceTest extends PostgresTestSupport {

    @Autowired WikiAccessRepository repo;

    private WikiAccessService service;

    /** Multi-brand registry: brandx + brandy issuers. */
    private static final DomainRegistry MULTI = new DomainRegistry(
            "https://id.brandx.example",
            "https://id.brandx.example=brandx,https://id.brandy.example=brandy");

    private static final AtomicInteger SEQ = new AtomicInteger();
    private String user(String tag) { return tag + "-" + SEQ.incrementAndGet(); }

    @BeforeEach
    void setUp() {
        service = new WikiAccessService(repo, new PrincipalDomain(MULTI));
    }

    /** A JWT principal for a brand + admin subject (the actor of an overlay write). */
    private static Authentication admin(String issuer, String subject, String... roles) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
                .subject(subject).claim("iss", issuer).issuer(issuer)
                .claim("roles", List.of(roles)).build();
        List<GrantedAuthority> auths = new ArrayList<>();
        auths.add(new SimpleGrantedAuthority("ROLE_USER"));
        for (String r : roles) auths.add(new SimpleGrantedAuthority("ROLE_" + r.toUpperCase()));
        return new UsernamePasswordAuthenticationToken(jwt, "n/a", auths);
    }

    // ── USER-SITUATIONAL ──────────────────────────────────────────────────────
    @Nested
    class UserSituational {

        @Test
        void grant_writesActiveReadRow_stampedWithActorBrandAndSubject() {
            String u = user("us-grant");
            Authentication adminX = admin("https://id.brandx.example", "admin-x", "admin");

            WikiAccess a = service.grant(u, "welcome", null, adminX).block();
            assertThat(a.accessType()).isEqualTo(WikiAccess.TYPE_READ);
            assertThat(a.isActive()).isTrue();
            assertThat(a.expiresAt()).isNull();
            assertThat(a.domain()).isEqualTo("brandx");        // from issuer
            assertThat(a.grantedBySubject()).isEqualTo("admin-x"); // from JWT subject
        }

        @Test
        void timeout_writesBannedRowWithFutureExpiry() {
            String u = user("us-timeout");
            Authentication adminX = admin("https://id.brandx.example", "admin-x", "admin");

            WikiAccess a = service.timeout(u, 3600, "cooldown", null, adminX).block();
            assertThat(a.accessType()).isEqualTo(WikiAccess.TYPE_BANNED);
            assertThat(a.expiresAt()).isNotNull();
            assertThat(a.expiresAt()).isAfter(Instant.now());
        }

        @Test
        void ban_writesPermanentBannedRow() {
            String u = user("us-ban");
            Authentication adminX = admin("https://id.brandx.example", "admin-x", "admin");

            WikiAccess a = service.ban(u, "abuse", null, adminX).block();
            assertThat(a.accessType()).isEqualTo(WikiAccess.TYPE_BANNED);
            assertThat(a.expiresAt()).isNull();   // permanent
        }

        @Test
        void revoke_liftsActiveOverride_auditRetained() {
            String u = user("us-revoke");
            Authentication adminX = admin("https://id.brandx.example", "admin-x", "admin");
            service.ban(u, "x", null, adminX).block();

            service.revoke(u, WikiAccess.TYPE_BANNED, adminX).block();
            assertThat(repo.findActive(u, WikiAccess.TYPE_BANNED, "brandx").blockOptional()).isEmpty();
            // The history retains the revoked row (audit).
            assertThat(service.historyFor(u, adminX).collectList().block())
                    .anySatisfy(a -> assertThat(a.state()).isEqualTo(WikiAccess.STATE_REVOKED));
        }
    }

    // ── MULTIUSER / idempotency (service guard + the real PG partial-unique) ────
    @Nested
    class Idempotency {

        @Test
        void reGrant_whileActive_isNoOp_sameRow() {
            String u = user("idem-grant");
            Authentication adminX = admin("https://id.brandx.example", "admin-x", "admin");

            WikiAccess first = service.grant(u, "a", null, adminX).block();
            WikiAccess second = service.grant(u, "b", null, adminX).block();
            assertThat(second.id()).isEqualTo(first.id());   // returned existing, not a 2nd row
            assertThat(repo.activeFor(u, "brandx").collectList().block()).hasSize(1);
        }

        @Test
        void reBan_whileActive_isNoOp_sameRow() {
            String u = user("idem-ban");
            Authentication adminX = admin("https://id.brandx.example", "admin-x", "admin");

            WikiAccess first = service.ban(u, "a", null, adminX).block();
            WikiAccess second = service.ban(u, "b", null, adminX).block();
            assertThat(second.id()).isEqualTo(first.id());
            long activeBanned = repo.activeFor(u, "brandx").collectList().block().stream()
                    .filter(a -> WikiAccess.TYPE_BANNED.equals(a.accessType())).count();
            assertThat(activeBanned).isEqualTo(1L);
        }
    }

    // ── CROSS-USER (DENY isolation) ───────────────────────────────────────────
    @Nested
    class CrossUser {

        /** An admin of brand X cannot revoke brand Y's override (invisible to its scope);
         *  the brand-Y override stays active. SUPERADMIN can. */
        @Test
        void adminCannotRevokeAnotherBrandsOverride() {
            String u = user("cu-revoke");
            Authentication adminX = admin("https://id.brandx.example", "admin-x", "admin");
            Authentication adminY = admin("https://id.brandy.example", "admin-y", "admin");
            service.ban(u, "y-ban", null, adminY).block();   // a ban in brand Y

            // Admin X tries to lift it — no-op (brand Y's row is invisible to brand X).
            service.revoke(u, WikiAccess.TYPE_BANNED, adminX).block();
            assertThat(repo.findActive(u, WikiAccess.TYPE_BANNED, "brandy").block()).isNotNull();

            // Admin Y (own brand) lifts it.
            service.revoke(u, WikiAccess.TYPE_BANNED, adminY).block();
            assertThat(repo.findActive(u, WikiAccess.TYPE_BANNED, "brandy").blockOptional()).isEmpty();
        }

        @Test
        void sameUser_grantInX_doesNotAffectY() {
            String u = user("cu-iso");
            Authentication adminX = admin("https://id.brandx.example", "admin-x", "admin");
            service.grant(u, "x-grant", null, adminX).block();

            Authentication adminY = admin("https://id.brandy.example", "admin-y", "admin");
            // brand Y sees no override for this user.
            assertThat(service.listActive(adminY).collectList().block())
                    .noneSatisfy(a -> assertThat(a.userSubject()).isEqualTo(u));
        }
    }
}
