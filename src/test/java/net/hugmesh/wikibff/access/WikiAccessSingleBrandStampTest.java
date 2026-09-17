package net.hugmesh.wikibff.access;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import net.hugmesh.commons.security.DomainRegistry;
import net.hugmesh.commons.web.ApiException;
import net.hugmesh.wikibff.security.PrincipalDomain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * REGRESSION for issue #21 / operator decision 26 ("Fix: stamp the configured brand"):
 * with EXACTLY ONE configured issuer pair, a SUPERADMIN grant / timeout / ban was stamped
 * {@code default} while every user of that deployment resolved the configured brand, so
 * {@link WikiAccessGuard} — which reads back {@code activeFor(subject, brand)} — never
 * matched the row and the ban silently did nothing.
 *
 * <p>Deliberately a PURE unit slice: the REAL {@link WikiAccessService} and the REAL
 * {@link PrincipalDomain} over REAL {@link DomainRegistry} instances, with only the
 * repository mocked. Everything under test is the {@code writeDomain} decision, which is a
 * function of configuration alone — a Postgres container would add minutes and prove nothing
 * extra. The end-to-end write-then-guard round trip for 2+ brands is already covered by
 * {@link WikiAccessSuperadminCrossBrandBanTest}.
 *
 * <p>Delineated per the test standard:
 * <ul>
 *   <li><b>USER-SITUATIONAL</b> — one configured pair: grant, timeout and ban all stamp the
 *       configured brand, whether {@code targetDomain} is omitted or names that same brand;
 *       and the stamp is asserted EQUAL to what a plain user of that issuer resolves at read
 *       time, which is the invariant the defect broke.</li>
 *   <li><b>CROSS-USER (DENY)</b> — a {@code targetDomain} naming a brand this deployment does
 *       not configure is a {@code 400} and NOTHING is written, rather than a row that lands
 *       somewhere the operator did not ask for.</li>
 *   <li><b>BACK-COMPAT (UNCHANGED)</b> — {@code app.tenancy.issuers} empty (and the
 *       nothing-configured and {@code =default} spellings) keep stamping {@code default}, and
 *       {@code targetDomain} stays INERT there: no new {@code 400} reaches a deployment that
 *       never had one.</li>
 *   <li><b>2+ BRANDS (UNCHANGED)</b> — PR #39's fail-closed contract still holds: name a
 *       known victim brand or take a {@code 400}.</li>
 * </ul>
 */
class WikiAccessSingleBrandStampTest {

    // ── the five configurations that matter, spelled exactly as an operator sets them ──

    /** EXACTLY ONE configured pair: the shape issue #21 is about. */
    private static final DomainRegistry ONE_PAIR =
            new DomainRegistry("https://id.acme.example", "https://id.acme.example=acme");

    /** BACK-COMPAT: app.tenancy.issuers EMPTY, one issuer-uri -> the {iss -> "default"} seed. */
    private static final DomainRegistry BACK_COMPAT =
            new DomainRegistry("https://id.acme.example", "");

    /** Nothing configured at all: the registry map is empty and domainOf() is always "default". */
    private static final DomainRegistry NOTHING = new DomainRegistry("", "");

    /** One pair whose brand is spelled literally "default" — still not a real brand. */
    private static final DomainRegistry PAIR_NAMED_DEFAULT =
            new DomainRegistry("https://id.acme.example", "https://id.acme.example=default");

    /**
     * One pair whose brand is 65 characters — {@code DomainRegistry.sanitize()} guarantees the
     * alphabet but NOT the {@code varchar(64)} ceiling every {@code domain} column carries.
     */
    private static final String OVERLONG = "a".repeat(65);

    private static final DomainRegistry PAIR_TOO_LONG_TO_STORE = new DomainRegistry(
            "https://id.acme.example", "https://id.acme.example=" + OVERLONG);

    /** TWO configured pairs: PR #39's already-correct fail-closed shape. */
    private static final DomainRegistry TWO_PAIRS = new DomainRegistry(
            "https://id.acme.example",
            "https://id.acme.example=acme,https://id.globex.example=globex");

    private static final String ISS_ACME = "https://id.acme.example";

    // ── fixtures ──────────────────────────────────────────────────────────────

    /** A JWT-principal Authentication for an issuer + subject + Casdoor roles. */
    private static Authentication principal(String issuer, String subject, String... roles) {
        Jwt jwt = jwt(issuer, subject, roles);
        List<GrantedAuthority> auths = new ArrayList<>();
        auths.add(new SimpleGrantedAuthority("ROLE_USER"));
        for (String r : roles) {
            auths.add(new SimpleGrantedAuthority("ROLE_" + r.toUpperCase()));
        }
        return new UsernamePasswordAuthenticationToken(jwt, "n/a", auths);
    }

    private static Jwt jwt(String issuer, String subject, String... roles) {
        return Jwt.withTokenValue("t").header("alg", "none")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
                .subject(subject).claim("iss", issuer).issuer(issuer)
                .claim("roles", List.of(roles)).build();
    }

    /** A repo that finds nothing active and echoes back whatever domain it was handed. */
    private static WikiAccessRepository echoingRepo() {
        WikiAccessRepository repo = mock(WikiAccessRepository.class);
        when(repo.findActive(any(), any(), any())).thenReturn(Mono.empty());
        when(repo.insertActive(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> Mono.just(new WikiAccess(
                        "id-1", inv.getArgument(5), inv.getArgument(0), inv.getArgument(1),
                        WikiAccess.STATE_ACTIVE, inv.getArgument(4), inv.getArgument(3),
                        Instant.now(), inv.getArgument(2), null)));
        return repo;
    }

    /**
     * A repo with NO stubbing at all. Used by every DENY case: the refusal must happen
     * before the repository is consulted, so nothing needs teaching — and
     * {@code verifyNoInteractions} on an unstubbed mock is an unambiguous claim.
     */
    private static WikiAccessRepository bareRepo() {
        return mock(WikiAccessRepository.class);
    }

    /** The domain argument {@code insertActive} was actually called with. */
    private static String stampedDomain(WikiAccessRepository repo) {
        ArgumentCaptor<String> domain = ArgumentCaptor.forClass(String.class);
        verify(repo).insertActive(any(), any(), any(), any(), any(), domain.capture());
        return domain.getValue();
    }

    private static WikiAccessService serviceOn(DomainRegistry registry, WikiAccessRepository repo) {
        return new WikiAccessService(repo, new PrincipalDomain(registry));
    }

    // ── USER-SITUATIONAL: one configured pair IS a real brand ─────────────────

    @Nested
    class OneConfiguredPair {

        /**
         * THE finding. A SUPERADMIN bans with no {@code targetDomain} at all — exactly what an
         * existing single-brand caller sends today — and the row is now stamped {@code acme}
         * instead of the inert {@code default}.
         */
        @Test
        void superadminBan_withNoTargetDomain_stampsTheConfiguredBrand() {
            WikiAccessRepository repo = echoingRepo();
            WikiAccess row = serviceOn(ONE_PAIR, repo)
                    .ban("victim", "abuse", null, principal(ISS_ACME, "root", "superadmin"))
                    .block();

            assertThat(stampedDomain(repo))
                    .as("the row must be STAMPED with the configured brand, not 'default'")
                    .isEqualTo("acme");
            assertThat(row.domain()).isEqualTo("acme");
        }

        /**
         * The invariant the defect actually broke, asserted as an invariant rather than as a
         * literal: the brand a SUPERADMIN write is stamped with must be the SAME string a
         * plain user of this deployment resolves at read time, because that is the value
         * {@link WikiAccessGuard} passes to {@code activeFor}. Before the fix these were
         * {@code "default"} and {@code "acme"}.
         */
        @Test
        void superadminStamp_equalsWhatTheReadGuardWillLookFor() {
            WikiAccessRepository repo = echoingRepo();
            serviceOn(ONE_PAIR, repo)
                    .ban("victim", "abuse", null, principal(ISS_ACME, "root", "superadmin"))
                    .block();

            String readScope = new PrincipalDomain(ONE_PAIR).forScope(jwt(ISS_ACME, "victim", "user"));
            assertThat(stampedDomain(repo))
                    .as("write stamp and read scope must be the same brand or the ban is inert")
                    .isEqualTo(readScope);
        }

        /** Naming the configured brand explicitly is accepted and stamps the same value. */
        @Test
        void superadminBan_namingTheConfiguredBrand_isAccepted() {
            WikiAccessRepository repo = echoingRepo();
            serviceOn(ONE_PAIR, repo)
                    .ban("victim", "abuse", "acme", principal(ISS_ACME, "root", "superadmin"))
                    .block();
            assertThat(stampedDomain(repo)).isEqualTo("acme");
        }

        /** A blank targetDomain is treated as "unset", not as an unknown brand. */
        @Test
        void superadminBan_withBlankTargetDomain_stampsTheConfiguredBrand() {
            WikiAccessRepository repo = echoingRepo();
            serviceOn(ONE_PAIR, repo)
                    .ban("victim", "abuse", "   ", principal(ISS_ACME, "root", "superadmin"))
                    .block();
            assertThat(stampedDomain(repo)).isEqualTo("acme");
        }

        /** The fix is on the shared write path, so GRANT gets it too — not ban only. */
        @Test
        void superadminGrant_stampsTheConfiguredBrand() {
            WikiAccessRepository repo = echoingRepo();
            serviceOn(ONE_PAIR, repo)
                    .grant("friend", "welcome", null, principal(ISS_ACME, "root", "superadmin"))
                    .block();
            assertThat(stampedDomain(repo)).isEqualTo("acme");
        }

        /** ...and so does TIMEOUT. */
        @Test
        void superadminTimeout_stampsTheConfiguredBrand() {
            WikiAccessRepository repo = echoingRepo();
            serviceOn(ONE_PAIR, repo)
                    .timeout("noisy", 3600, "cooldown", null, principal(ISS_ACME, "root", "superadmin"))
                    .block();
            assertThat(stampedDomain(repo)).isEqualTo("acme");
        }

        /**
         * A scoped ADMIN is untouched: their brand is issuer-derived and {@code targetDomain}
         * remains inert, so an ADMIN still cannot redirect a write out of their own brand.
         */
        @Test
        void scopedAdmin_isUnchanged_targetDomainStillInert() {
            WikiAccessRepository repo = echoingRepo();
            serviceOn(ONE_PAIR, repo)
                    .ban("victim", "abuse", "globex", principal(ISS_ACME, "admin-a", "admin"))
                    .block();
            assertThat(stampedDomain(repo))
                    .as("an ADMIN's write is issuer-derived; a forged targetDomain is ignored")
                    .isEqualTo("acme");
        }
    }

    // ── CROSS-USER (DENY): a brand this deployment does not have is a 400 ──────

    @Nested
    class MismatchedTargetDomain {

        /**
         * The second half of decision 26: a {@code targetDomain} that is not this deployment's
         * brand FAILS LOUDLY (400) instead of being silently discarded. Nothing is written —
         * not into the named brand, and not into the configured one either.
         */
        @Test
        void superadminBan_namingAnotherBrand_is400_andWritesNothing() {
            WikiAccessRepository repo = bareRepo();
            StepVerifier.create(serviceOn(ONE_PAIR, repo)
                            .ban("victim", "abuse", "globex", principal(ISS_ACME, "root", "superadmin")))
                    .expectErrorSatisfies(e -> {
                        assertThat(e).isInstanceOf(ApiException.class);
                        assertThat(((ApiException) e).status().value()).isEqualTo(400);
                        assertThat(e.getMessage())
                                .as("the message must name the brand this deployment DOES configure")
                                .contains("acme");
                    })
                    .verify();
            verifyNoInteractions(repo);
        }

        /** The same refusal on grant... */
        @Test
        void superadminGrant_namingAnotherBrand_is400() {
            WikiAccessRepository repo = bareRepo();
            StepVerifier.create(serviceOn(ONE_PAIR, repo)
                            .grant("friend", "welcome", "globex", principal(ISS_ACME, "root", "superadmin")))
                    .expectErrorSatisfies(e ->
                            assertThat(((ApiException) e).status().value()).isEqualTo(400))
                    .verify();
            verifyNoInteractions(repo);
        }

        /** ...and on timeout. */
        @Test
        void superadminTimeout_namingAnotherBrand_is400() {
            WikiAccessRepository repo = bareRepo();
            StepVerifier.create(serviceOn(ONE_PAIR, repo)
                            .timeout("noisy", 60, "cooldown", "globex", principal(ISS_ACME, "root", "superadmin")))
                    .expectErrorSatisfies(e ->
                            assertThat(((ApiException) e).status().value()).isEqualTo(400))
                    .verify();
            verifyNoInteractions(repo);
        }

        /** "default" is not a brand a SUPERADMIN may name on a branded deployment either. */
        @Test
        void superadminBan_namingTheLiteralDefault_is400() {
            WikiAccessRepository repo = bareRepo();
            StepVerifier.create(serviceOn(ONE_PAIR, repo)
                            .ban("victim", "abuse", "default", principal(ISS_ACME, "root", "superadmin")))
                    .expectErrorSatisfies(e ->
                            assertThat(((ApiException) e).status().value()).isEqualTo(400))
                    .verify();
            verifyNoInteractions(repo);
        }
    }

    // ── BACK-COMPAT: nothing about a default-only deployment may change ────────

    @Nested
    class BackCompatUnchanged {

        /** app.tenancy.issuers EMPTY: the registry seed is "default" and that is CORRECT. */
        @Test
        void emptyIssuers_superadminBan_stillStampsDefault() {
            WikiAccessRepository repo = echoingRepo();
            serviceOn(BACK_COMPAT, repo)
                    .ban("victim", "abuse", null, principal(ISS_ACME, "root", "superadmin"))
                    .block();
            assertThat(stampedDomain(repo)).isEqualTo(DomainRegistry.DEFAULT_DOMAIN);
        }

        /**
         * And {@code targetDomain} stays INERT there — a deployment that never produced a
         * {@code 400} on this field must not start producing one. This is the back-compat
         * assertion the operator asked to be pinned in both directions.
         */
        @Test
        void emptyIssuers_targetDomainIsInert_noErrorAndStillDefault() {
            WikiAccessRepository repo = echoingRepo();
            WikiAccess row = serviceOn(BACK_COMPAT, repo)
                    .ban("victim", "abuse", "acme", principal(ISS_ACME, "root", "superadmin"))
                    .block();
            assertThat(row).as("no 400: back-compat must behave exactly as it did").isNotNull();
            assertThat(stampedDomain(repo)).isEqualTo(DomainRegistry.DEFAULT_DOMAIN);
        }

        /** No issuer-uri and no issuers at all: the legacy single-tenant mode. */
        @Test
        void nothingConfigured_superadminBan_stillStampsDefault() {
            WikiAccessRepository repo = echoingRepo();
            serviceOn(NOTHING, repo)
                    .ban("victim", "abuse", null, principal(ISS_ACME, "root", "superadmin"))
                    .block();
            assertThat(stampedDomain(repo)).isEqualTo(DomainRegistry.DEFAULT_DOMAIN);
        }

        /**
         * A brand the {@code domain} column could not hold (65 characters against a
         * {@code varchar(64)}) is treated as NO brand, so the write keeps the legacy
         * {@code default} stamp. Stamping it would turn a write that used to be an inert
         * {@code 200} into a DB constraint failure — a regression dressed up as a fix. This is
         * the same narrowing {@code WikiLaunchDomain} and {@code WikiBrandCatalog} already
         * apply, called rather than copied.
         */
        @Test
        void aBrandTooLongForTheDomainColumn_isNotTreatedAsTheBrand() {
            assertThat(OVERLONG).hasSize(65);
            assertThat(PAIR_TOO_LONG_TO_STORE.knownDomains())
                    .as("premise: the registry really did accept it")
                    .containsExactly(OVERLONG);
            assertThat(PAIR_TOO_LONG_TO_STORE.isKnownDomain(OVERLONG))
                    .as("premise: isKnownDomain alone has no length cap, so it would pass")
                    .isTrue();

            WikiAccessRepository repo = echoingRepo();
            serviceOn(PAIR_TOO_LONG_TO_STORE, repo)
                    .ban("victim", "abuse", null, principal(ISS_ACME, "root", "superadmin"))
                    .block();
            assertThat(stampedDomain(repo)).isEqualTo(DomainRegistry.DEFAULT_DOMAIN);
        }

        /**
         * One configured pair whose brand is spelled literally {@code default} is NOT a real
         * brand — every principal there still resolves {@code default}, so the write must keep
         * stamping it and the field must stay inert.
         */
        @Test
        void pairNamedDefault_stampsDefault_andTargetDomainIsInert() {
            WikiAccessRepository repo = echoingRepo();
            serviceOn(PAIR_NAMED_DEFAULT, repo)
                    .ban("victim", "abuse", "acme", principal(ISS_ACME, "root", "superadmin"))
                    .block();
            assertThat(stampedDomain(repo)).isEqualTo(DomainRegistry.DEFAULT_DOMAIN);
        }
    }

    // ── 2+ BRANDS: PR #39's fail-closed contract is untouched ──────────────────

    @Nested
    class TwoOrMoreBrandsUnchanged {

        @Test
        void superadminBan_withoutTargetDomain_stillFailsClosed() {
            WikiAccessRepository repo = bareRepo();
            StepVerifier.create(serviceOn(TWO_PAIRS, repo)
                            .ban("victim", "abuse", null, principal(ISS_ACME, "root", "superadmin")))
                    .expectErrorSatisfies(e ->
                            assertThat(((ApiException) e).status().value()).isEqualTo(400))
                    .verify();
            verifyNoInteractions(repo);
        }

        @Test
        void superadminBan_namingAKnownVictimBrand_stampsThatBrand() {
            WikiAccessRepository repo = echoingRepo();
            serviceOn(TWO_PAIRS, repo)
                    .ban("victim", "abuse", "globex", principal(ISS_ACME, "root", "superadmin"))
                    .block();
            assertThat(stampedDomain(repo)).isEqualTo("globex");
        }

        @Test
        void superadminBan_namingAnUnknownBrand_stillFailsClosed() {
            WikiAccessRepository repo = bareRepo();
            StepVerifier.create(serviceOn(TWO_PAIRS, repo)
                            .ban("victim", "abuse", "roguebrand", principal(ISS_ACME, "root", "superadmin")))
                    .expectErrorSatisfies(e ->
                            assertThat(((ApiException) e).status().value()).isEqualTo(400))
                    .verify();
            verifyNoInteractions(repo);
        }
    }

    // ── the predicate itself, in isolation ────────────────────────────────────

    @Nested
    class SoleConfiguredBrandPredicate {

        @Test
        void oneConfiguredPair_isTheBrand() {
            assertThat(new PrincipalDomain(ONE_PAIR).soleConfiguredBrand()).isEqualTo("acme");
        }

        @Test
        void backCompatSeed_isNotABrand() {
            assertThat(new PrincipalDomain(BACK_COMPAT).soleConfiguredBrand()).isNull();
        }

        @Test
        void nothingConfigured_isNotABrand() {
            assertThat(new PrincipalDomain(NOTHING).soleConfiguredBrand()).isNull();
        }

        @Test
        void pairSpelledDefault_isNotABrand() {
            assertThat(new PrincipalDomain(PAIR_NAMED_DEFAULT).soleConfiguredBrand()).isNull();
        }

        @Test
        void twoConfiguredPairs_haveNoSoleBrand() {
            assertThat(new PrincipalDomain(TWO_PAIRS).soleConfiguredBrand()).isNull();
        }

        @Test
        void aBrandTooLongToStore_isNotASoleBrand() {
            assertThat(new PrincipalDomain(PAIR_TOO_LONG_TO_STORE).soleConfiguredBrand()).isNull();
        }

        /** The static overload WikiBrandCatalog calls must agree with the instance one. */
        @Test
        void theStaticOverload_agreesWithTheInstanceMethod() {
            for (DomainRegistry r : List.of(ONE_PAIR, BACK_COMPAT, NOTHING,
                    PAIR_NAMED_DEFAULT, PAIR_TOO_LONG_TO_STORE, TWO_PAIRS)) {
                assertThat(PrincipalDomain.soleConfiguredBrand(r))
                        .isEqualTo(new PrincipalDomain(r).soleConfiguredBrand());
            }
        }

        /**
         * The brand offered here must be one the write path's OWN predicate accepts — the
         * rule {@code WikiBrandCatalog} exists to keep ("never offer a brand the write path
         * will not honour"). Asserted against {@code resolveKnownDomain}, not re-derived.
         */
        @Test
        void theOfferedBrand_isOneTheWritePathAccepts() {
            PrincipalDomain pd = new PrincipalDomain(ONE_PAIR);
            assertThat(pd.resolveKnownDomain(pd.soleConfiguredBrand())).isEqualTo("acme");
        }
    }
}
