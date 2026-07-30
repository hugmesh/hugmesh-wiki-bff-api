package systems.hugmesh.wikibff.access;

import org.junit.jupiter.api.BeforeEach;
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
import systems.hugmesh.commons.security.DomainRegistry;
import systems.hugmesh.commons.testsupport.PostgresTestSupport;
import systems.hugmesh.commons.web.ApiException;
import systems.hugmesh.wikibff.security.PrincipalDomain;
import systems.hugmesh.wikibff.support.TestDatabaseConfig;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REGRESSION for the finding "a SUPERADMIN cross-brand ban/timeout is stamped to {@code
 * default} but the read guard queries the victim's own brand, so the ban silently does not
 * take effect". The fix makes the SUPERADMIN ban target the VICTIM's brand (a resolved
 * {@code targetDomain}) rather than normalising the actor's null scope to {@code default}.
 *
 * <p>This drives the REAL {@link WikiAccessService} write + the REAL {@link WikiAccessGuard}
 * read over the REAL repo on a MULTI-BRAND {@link PrincipalDomain} (Testcontainers-Postgres),
 * so the round-trip the exploit broke is exercised end to end:
 *
 * <ul>
 *   <li><b>EXPLOIT CLOSED</b> — a SUPERADMIN bans a brand-X user with {@code
 *       targetDomain="brandx"}; the row lands in {@code brandx}; the guard's brand-X read
 *       (which queries {@code activeFor(subject,"brandx")}) NOW finds the ban and DENIES
 *       (403). Before the fix the row was {@code default} and the guard allowed.</li>
 *   <li><b>FAIL-CLOSED</b> — a SUPERADMIN ban with NO {@code targetDomain} in multi-brand
 *       mode is rejected ({@code 400}) and NO row is written, rather than silently stamping
 *       an inert {@code default} ban.</li>
 *   <li><b>LEGIT scoped-ADMIN unchanged</b> — a brand-X ADMIN's ban still lands in their own
 *       issuer-derived brand and the {@code targetDomain} field is inert (an ADMIN can never
 *       redirect a write into another brand).</li>
 * </ul>
 */
@DataR2dbcTest
@Import({TestDatabaseConfig.class, WikiAccessRepository.class})
class WikiAccessSuperadminCrossBrandBanTest extends PostgresTestSupport {

    @Autowired WikiAccessRepository repo;

    private WikiAccessGuard guard;
    private WikiAccessService service;

    /** Multi-brand registry: brandx + brandy issuers (so isMultiIssuer() is true and the
     *  SUPERADMIN cross-brand target is required). */
    private static final DomainRegistry MULTI = new DomainRegistry(
            "https://id.brandx.example",
            "https://id.brandx.example=brandx,https://id.brandy.example=brandy");
    private static final PrincipalDomain PD = new PrincipalDomain(MULTI);

    private static final String ISS_X = "https://id.brandx.example";

    private static final AtomicInteger SEQ = new AtomicInteger();
    private String user(String tag) { return tag + "-" + SEQ.incrementAndGet(); }

    @BeforeEach
    void setUp() {
        guard = new WikiAccessGuard(repo, PD);
        service = new WikiAccessService(repo, PD);
    }

    /** A JWT-principal Authentication for a brand (issuer) + subject + Casdoor roles. */
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

    // ── EXPLOIT CLOSED: SUPERADMIN ban of a brand-X user now DENIES the brand-X read ──

    /**
     * THE finding: a SUPERADMIN bans a brand-X abuser (who would otherwise read by role).
     * With {@code targetDomain="brandx"} the ban row lands in {@code brandx}, so the guard's
     * brand-X read finds it and DENIES — the ban actually takes effect.
     */
    @Test
    void superadminBan_targetsVictimBrand_brandXReadDenied() {
        String u = user("xbrand-ban");
        // The victim is a brand-X MOD+WIKI (reads by role pre-ban).
        Authentication victimX = principal(ISS_X, u, "mod", "wiki");
        StepVerifier.create(guard.assertCanRead(victimX)).verifyComplete();   // allowed pre-ban

        // A SUPERADMIN (null actor-scope) bans this brand-X subject, naming the victim brand.
        Authentication superadmin = principal(ISS_X, "root", "superadmin");
        WikiAccess row = service.ban(u, "abuse", "brandx", superadmin).block();

        // The row is STAMPED with the victim brand (not the inert 'default').
        assertThat(row.domain())
                .as("a SUPERADMIN cross-brand ban is stamped to the victim brand, not 'default'")
                .isEqualTo("brandx");
        assertThat(repo.activeFor(u, "brandx").collectList().block())
                .as("the brand-X read working set now contains the ban")
                .anySatisfy(a -> assertThat(a.accessType()).isEqualTo(WikiAccess.TYPE_BANNED));

        // The guard's brand-X read NOW sees the ban and DENIES (the exploit is closed).
        StepVerifier.create(guard.assertCanRead(victimX))
                .expectErrorSatisfies(e ->
                        assertThat(((ApiException) e).status().value()).isEqualTo(403))
                .verify();
    }

    // ── FAIL-CLOSED: SUPERADMIN ban with no target brand is rejected, nothing written ──

    @Test
    void superadminBan_withoutTargetDomain_failsClosed_noRowWritten() {
        String u = user("xbrand-notgt");
        Authentication superadmin = principal(ISS_X, "root", "superadmin");

        // No targetDomain in multi-brand mode => 400 (rather than an inert 'default' ban).
        StepVerifier.create(service.ban(u, "abuse", null, superadmin))
                .expectErrorSatisfies(e ->
                        assertThat(((ApiException) e).status().value()).isEqualTo(400))
                .verify();

        // Nothing was written in EITHER brand (no silent inert ban anywhere).
        assertThat(repo.activeFor(u, "default").collectList().block()).isEmpty();
        assertThat(repo.activeFor(u, "brandx").collectList().block()).isEmpty();
    }

    @Test
    void superadminBan_withUnknownTargetDomain_failsClosed() {
        String u = user("xbrand-bogus");
        Authentication superadmin = principal(ISS_X, "root", "superadmin");

        // An unknown (untrusted) brand cannot be targeted — fail closed rather than stamping
        // a brand no Casdoor backs (mirrors DomainRegistry.isKnownDomain).
        StepVerifier.create(service.ban(u, "abuse", "roguebrand", superadmin))
                .expectErrorSatisfies(e ->
                        assertThat(((ApiException) e).status().value()).isEqualTo(400))
                .verify();
        assertThat(repo.activeFor(u, "roguebrand").collectList().block()).isEmpty();
    }

    // ── LEGIT unchanged: scoped ADMIN bans in own brand; targetDomain is inert ──

    /**
     * A brand-X ADMIN's ban still lands in brandx (issuer-derived), and a {@code targetDomain}
     * pointing at another brand is IGNORED — an ADMIN can never redirect a write cross-brand.
     */
    @Test
    void scopedAdminBan_staysInOwnBrand_targetDomainIgnored() {
        String u = user("xbrand-admin");
        Authentication adminX = principal(ISS_X, "admin-x", "admin");

        // Even with a forged targetDomain="brandy", a scoped ADMIN's ban lands in brandx.
        WikiAccess row = service.ban(u, "abuse", "brandy", adminX).block();
        assertThat(row.domain())
                .as("a scoped ADMIN's write is issuer-derived (brandx); targetDomain is inert")
                .isEqualTo("brandx");
        assertThat(repo.activeFor(u, "brandy").collectList().block())
                .as("no row leaked into the forged target brand")
                .isEmpty();
    }
}
