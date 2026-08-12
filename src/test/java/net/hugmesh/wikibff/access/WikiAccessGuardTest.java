package net.hugmesh.wikibff.access;

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
import reactor.test.StepVerifier;
import net.hugmesh.commons.security.DomainRegistry;
import net.hugmesh.wikibff.security.PrincipalDomain;
import net.hugmesh.commons.testsupport.PostgresTestSupport;
import net.hugmesh.wikibff.support.TestDatabaseConfig;
import net.hugmesh.commons.web.ApiException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE LOAD-BEARING test: {@link WikiAccessGuard} is the request-time enforcement that
 * makes the {@code wiki_access} overlay NOT inert. It runs over the REAL repo + a
 * multi-brand {@link PrincipalDomain} so the issuer-derived scoping is exercised end to
 * end. Delineated per the test standard — the headline cases the design calls out:
 *
 * <ul>
 *   <li><b>USER-SITUATIONAL — grant ALLOW</b>: a granted USER (no qualifying role) gains
 *       read; revoking the grant removes read (DENY).</li>
 *   <li><b>CROSS-USER ALLOW+DENY — the BAN precedence (load-bearing)</b>: an active ban
 *       DENIES a user who would otherwise qualify by ROLE; and a ban beats a grant.</li>
 *   <li><b>TIMEOUT</b>: denies until {@code expires_at}, then auto-allows (simulated past
 *       vs future expiry) WITHOUT a write.</li>
 *   <li><b>SUPERADMIN</b> is never banned out (infra, exempt — no DB read).</li>
 *   <li><b>DOMAIN ISOLATION</b>: a ban in brand X does not deny the same subject in brand Y.</li>
 *   <li><b>CONCURRENT-CONFLICTING</b>: with BOTH an active grant and an active ban, the ban
 *       wins (precedence is deterministic regardless of row order).</li>
 * </ul>
 */
@DataR2dbcTest
@Import({TestDatabaseConfig.class, WikiAccessRepository.class})
class WikiAccessGuardTest extends PostgresTestSupport {

    @Autowired WikiAccessRepository repo;

    private WikiAccessGuard guard;
    private WikiAccessService service;

    /** Multi-brand registry: brandx + brandy issuers (so issuer -> domain scoping fires). */
    private static final DomainRegistry MULTI = new DomainRegistry(
            "https://id.brandx.example",
            "https://id.brandx.example=brandx,https://id.brandy.example=brandy");
    private static final PrincipalDomain PD = new PrincipalDomain(MULTI);

    private static final String ISS_X = "https://id.brandx.example";
    private static final String ISS_Y = "https://id.brandy.example";

    private static final AtomicInteger SEQ = new AtomicInteger();
    private String user(String tag) { return tag + "-" + SEQ.incrementAndGet(); }

    @BeforeEach
    void setUp() {
        guard = new WikiAccessGuard(repo, PD);
        service = new WikiAccessService(repo, PD);
    }

    /** A JWT-principal Authentication for a brand (issuer) + subject + Casdoor roles. The
     *  guard reads the brand from the issuer ({@link PrincipalDomain}), the subject from
     *  {@code sub}, and the role composite from the converted authorities. */
    private static Authentication principal(String issuer, String subject, String... roles) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
                .subject(subject).claim("iss", issuer).issuer(issuer)
                .claim("roles", List.of(roles)).build();
        List<GrantedAuthority> auths = new ArrayList<>();
        auths.add(new SimpleGrantedAuthority("ROLE_USER"));
        for (String r : roles) auths.add(new SimpleGrantedAuthority("ROLE_" + r.toUpperCase()));
        return new UsernamePasswordAuthenticationToken(jwt, "n/a", auths);
    }

    private static Authentication plainUser(String issuer, String subject) {
        return principal(issuer, subject);  // ROLE_USER only — no qualifying wiki role
    }

    private static void assertAllowed(WikiAccessGuard g, Authentication a) {
        StepVerifier.create(g.assertCanRead(a)).verifyComplete();
    }

    private static void assertDenied(WikiAccessGuard g, Authentication a) {
        StepVerifier.create(g.assertCanRead(a))
                .expectErrorSatisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(403))
                .verify();
    }

    // ── USER-SITUATIONAL — grant ALLOW / revoke DENY ──────────────────────────
    @Nested
    class GrantAllow {

        /** A plain USER is denied by default (no role, no grant)... */
        @Test
        void plainUser_withoutGrant_isDenied() {
            assertDenied(guard, plainUser(ISS_X, user("ga-nouser")));
        }

        /** ...a GRANT lets that same plain USER in (ALLOW — the load-bearing grant path). */
        @Test
        void grantedUser_gainsRead() {
            String u = user("ga-granted");
            Authentication user = plainUser(ISS_X, u);
            Authentication adminX = principal(ISS_X, "admin-x", "admin");

            assertDenied(guard, user);                 // before grant
            service.grant(u, "welcome", null, adminX).block();
            assertAllowed(guard, user);                // after grant — ALLOW
        }

        /** Revoking the grant removes read (DENY). */
        @Test
        void revokingGrant_removesRead() {
            String u = user("ga-revoke");
            Authentication user = plainUser(ISS_X, u);
            Authentication adminX = principal(ISS_X, "admin-x", "admin");
            service.grant(u, null, null, adminX).block();
            assertAllowed(guard, user);

            service.revoke(u, WikiAccess.TYPE_READ, adminX).block();
            assertDenied(guard, user);                 // grant lifted — DENY
        }

        /** A role-holder (MOD+WIKI) reads without any grant. */
        @Test
        void modWithWiki_readsByRole() {
            assertAllowed(guard, principal(ISS_X, user("ga-modwiki"), "mod", "wiki"));
        }

        /** A MOD WITHOUT ROLE_WIKI is denied (the AND). */
        @Test
        void modWithoutWiki_isDenied() {
            assertDenied(guard, principal(ISS_X, user("ga-modnowiki"), "mod"));
        }
    }

    // ── CROSS-USER ALLOW+DENY — the BAN precedence (LOAD-BEARING) ──────────────
    @Nested
    class BanPrecedence {

        /** THE load-bearing case: an active BAN denies a user who otherwise qualifies by
         *  ROLE (here an ADMIN). The ban wins over the role. */
        @Test
        void ban_deniesEvenARoleHolder() {
            String u = user("bp-roleban");
            // The target principal is itself an ADMIN of brand X (would read by role).
            Authentication adminTarget = principal(ISS_X, u, "admin");
            assertAllowed(guard, adminTarget);         // qualifies by role pre-ban

            // A (super)admin bans this subject in brand X.
            Authentication adminX = principal(ISS_X, "admin-x", "admin");
            service.ban(u, "abuse", null, adminX).block();

            assertDenied(guard, adminTarget);          // BANNED despite the ADMIN role
        }

        /** A ban beats a grant: an active grant + an active ban => denied. */
        @Test
        void ban_beatsGrant() {
            String u = user("bp-bangrant");
            Authentication user = plainUser(ISS_X, u);
            Authentication adminX = principal(ISS_X, "admin-x", "admin");
            service.grant(u, null, null, adminX).block();
            assertAllowed(guard, user);                // grant ALLOW

            service.ban(u, "abuse", null, adminX).block();   // now also banned
            assertDenied(guard, user);                 // ban wins (precedence)
        }
    }

    // ── TIMEOUT (denies until expiry, then auto-allows) ───────────────────────
    @Nested
    class Timeout {

        /** An ACTIVE timeout (future expiry) denies a role-holder. */
        @Test
        void activeTimeout_denies() {
            String u = user("to-active");
            Authentication modWiki = principal(ISS_X, u, "mod", "wiki");
            Authentication adminX = principal(ISS_X, "admin-x", "admin");
            assertAllowed(guard, modWiki);             // reads by role pre-timeout

            service.timeout(u, 3600, "cooldown", null, adminX).block();  // future expiry
            assertDenied(guard, modWiki);              // timed out
        }

        /** An EXPIRED timeout auto-restores (the guard ignores a past expires_at) WITHOUT a
         *  write — the row is still state=active in the DB but no longer effective. */
        @Test
        void expiredTimeout_autoRestores_withoutWrite() {
            String u = user("to-expired");
            Authentication modWiki = principal(ISS_X, u, "mod", "wiki");

            // Write a 'banned' row whose expires_at is already in the PAST (a lapsed timeout).
            Instant past = Instant.now().minusSeconds(60);
            repo.insertActive(u, WikiAccess.TYPE_BANNED, past, "old", "admin-x", "brandx").block();

            // The row is still ACTIVE in the table (no expiry write happened)...
            assertThat(repo.findActive(u, WikiAccess.TYPE_BANNED, "brandx").block()).isNotNull();
            // ...but the guard treats the lapsed timeout as inactive => read auto-restored.
            assertAllowed(guard, modWiki);
        }
    }

    // ── SUPERADMIN (never banned out) ─────────────────────────────────────────
    @Nested
    class Superadmin {

        /** SUPERADMIN is exempt from the overlay — even an active ban on its subject does
         *  not deny it (infra control plane). */
        @Test
        void superadmin_isNeverBannedOut() {
            String u = user("su-ban");
            Authentication su = principal(ISS_X, u, "superadmin");
            Authentication adminX = principal(ISS_X, "admin-x", "admin");
            service.ban(u, "ignored-for-superadmin", null, adminX).block();

            assertAllowed(guard, su);                  // banned row present, but SUPERADMIN reads
        }
    }

    // ── DOMAIN ISOLATION (a ban in X does not affect Y) ───────────────────────
    @Nested
    class DomainIsolation {

        /** A ban on a subject in brand X does NOT deny the SAME subject string acting in
         *  brand Y (the override is brand-scoped). */
        @Test
        void banInBrandX_doesNotDenyBrandY() {
            String u = user("di-ban");
            Authentication adminX = principal(ISS_X, "admin-x", "admin");
            service.ban(u, "x-only", null, adminX).block();   // ban in brand X

            // The same subject as a MOD+WIKI in brand Y still reads (brand Y has no ban row).
            Authentication modWikiY = principal(ISS_Y, u, "mod", "wiki");
            assertAllowed(guard, modWikiY);
            // ...and is denied in brand X.
            Authentication modWikiX = principal(ISS_X, u, "mod", "wiki");
            assertDenied(guard, modWikiX);
        }

        /** A grant in brand X does NOT let the same subject read in brand Y. */
        @Test
        void grantInBrandX_doesNotGrantBrandY() {
            String u = user("di-grant");
            Authentication adminX = principal(ISS_X, "admin-x", "admin");
            service.grant(u, null, null, adminX).block();

            assertAllowed(guard, plainUser(ISS_X, u));  // granted in X
            assertDenied(guard, plainUser(ISS_Y, u));   // NOT granted in Y
        }
    }

    // ── CONCURRENT-CONFLICTING (grant + ban -> ban wins, order-independent) ────
    @Nested
    class ConcurrentConflicting {

        /** With BOTH an active grant AND an active ban present for the same principal, the
         *  guard's precedence is deterministic — the ban always wins regardless of which
         *  row the DB returns first. */
        @Test
        void concurrentGrantAndBan_banWins() {
            String u = user("cc-both");
            Authentication adminX = principal(ISS_X, "admin-x", "admin");
            // Both overrides active at once (different access_type, so both coexist).
            service.grant(u, null, null, adminX).block();
            service.ban(u, "abuse", null, adminX).block();

            assertThat(repo.activeFor(u, "brandx").collectList().block()).hasSize(2);
            assertDenied(guard, plainUser(ISS_X, u));   // ban wins over grant
        }
    }
}
