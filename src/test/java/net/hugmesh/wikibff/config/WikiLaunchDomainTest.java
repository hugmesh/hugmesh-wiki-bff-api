package net.hugmesh.wikibff.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import net.hugmesh.commons.security.DomainRegistry;
import net.hugmesh.wikibff.config.WikiLaunchDomain.Resolution;
import net.hugmesh.wikibff.config.WikiLaunchDomain.Source;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The derivation that replaced the operator typing a brand into
 * {@code wiki.restamp.target.domain} (issue #19).
 *
 * <p>Two properties are under test and they are NOT the same property:
 * <ul>
 *   <li><b>the VALUE</b> — the domain this wiki is launched on, which must be
 *       byte-identical to what {@code DomainRegistry.domainOf()} hands the repositories
 *       for the same configuration, because the re-stamp writes it into
 *       {@code WHERE domain = ?} rows;</li>
 *   <li><b>the ARMING</b> — whether it is safe to re-stamp TO that value, which is true
 *       only when the runtime will resolve it. The unconfigured case has a value
 *       ({@code hugmesh}) and must NOT arm; the two-brand case is ambiguous and must not
 *       arm either.</li>
 * </ul>
 */
class WikiLaunchDomainTest {

    private static final String ISSUER = "https://id.hugmesh.net";

    private static final String ACME_ISSUER = "https://id.acme.test";

    @Nested
    class DerivesTheLaunchDomain {

        @Test
        void singleConfiguredBrandOnTheTRUSTEDIssuerIsTheLaunchDomainAndArms() {
            Resolution r = WikiLaunchDomain.resolve(ACME_ISSUER, ACME_ISSUER + "=acme");

            assertThat(r.domain()).isEqualTo("acme");
            assertThat(r.source()).isEqualTo(Source.ISSUER_MAPPING);
            assertThat(r.armsRestamp())
                    .as("one pair, on the issuer the resource server admits => that IS the"
                            + " domain the wiki is launched on")
                    .isTrue();
        }

        @Test
        void derivedValueIsExactlyWhatTheRuntimeRegistryResolves() {
            // The whole point of deriving through DomainRegistry rather than re-parsing:
            // sanitize() lower-cases and replaces [^a-z0-9-], and the trailing slash is
            // trimmed. A hand-rolled parser drifts here and strands every row.
            String csv = ACME_ISSUER + "/=Brand A";
            Resolution r = WikiLaunchDomain.resolve(ACME_ISSUER, csv);

            String runtime = new DomainRegistry(ACME_ISSUER, csv).domainOf(ACME_ISSUER);

            assertThat(r.domain())
                    .as("the re-stamp target must equal the string the repositories will query on")
                    .isEqualTo(runtime)
                    .isEqualTo("brand-a");
            assertThat(r.armsRestamp()).isTrue();
        }

        @Test
        void hugmeshArmsLikeAnyOtherBrandOnceTheOperatorNamesIt() {
            // "hugmesh is the default": naming it in the mapping makes it the live launch
            // domain, and the re-stamp then arms itself with no parameter typed.
            Resolution r = WikiLaunchDomain.resolve(ISSUER, ISSUER + "=hugmesh");

            assertThat(r.domain()).isEqualTo(WikiLaunchDomain.FALLBACK_DOMAIN);
            assertThat(r.source()).isEqualTo(Source.ISSUER_MAPPING);
            assertThat(r.armsRestamp()).isTrue();
        }
    }

    @Nested
    class FallsBackToHugmeshWithoutArming {

        @Test
        void nothingConfiguredAtAll() {
            Resolution r = WikiLaunchDomain.resolve(null, null);

            assertThat(r.domain()).isEqualTo("hugmesh");
            assertThat(r.source()).isEqualTo(Source.FALLBACK);
            assertThat(r.armsRestamp())
                    .as("runtime still resolves 'default'; re-stamping to hugmesh would strand"
                            + " every page, attachment, audit row and access BAN")
                    .isFalse();
        }

        @Test
        void singleIssuerBackCompatModeResolvesDefaultSoThereIsNothingToRestamp() {
            Resolution r = WikiLaunchDomain.resolve(ISSUER, "");

            // Proves the premise rather than assuming it: this configuration really does
            // make every principal resolve the literal the rows already carry.
            assertThat(new DomainRegistry(ISSUER, "").domainOf(ISSUER))
                    .isEqualTo(DomainRegistry.DEFAULT_DOMAIN);

            assertThat(r.domain()).isEqualTo("hugmesh");
            assertThat(r.source()).isEqualTo(Source.FALLBACK);
            assertThat(r.armsRestamp()).isFalse();
        }

        @Test
        void aSinglePairOnAnIssuerTheResourceServerDoesNotTrustMustNotArm() {
            // MEASURED hazard (skeptic pass, 2026-09-16): one pair leaves isMultiIssuer()
            // false, so SecurityConfig keeps the single-issuer o.jwt(issuer-uri) decoder and
            // the ONLY tokens that validate carry iss = ISSUER. The pair below names a
            // DIFFERENT issuer — the compose stack's published-vs-internal Casdoor URL is
            // exactly this confusion — so no principal resolves 'acme' and arming would
            // re-stamp every page, attachment, audit row and BAN behind a predicate nobody
            // matches. The brand string still parses cleanly, which is why this needs its
            // own check rather than falling out of the parse.
            String csv = "https://casdoor.internal:8000=acme";
            Resolution r = WikiLaunchDomain.resolve(ISSUER, csv);

            assertThat(new DomainRegistry(ISSUER, csv).domainOf(ISSUER))
                    .as("premise: the trusted issuer resolves NO brand here")
                    .isNull();
            assertThat(new DomainRegistry(ISSUER, csv).isMultiIssuer())
                    .as("premise: one pair, so the single-issuer decoder is the one in force")
                    .isFalse();

            assertThat(r.source()).isEqualTo(Source.FALLBACK);
            assertThat(r.armsRestamp()).isFalse();
            assertThat(r.reason()).contains("not the one this resource server trusts");
        }

        @Test
        void twoPairsNamingONEBrandMustNotArm_becauseTheClusterIsAlreadyMultiIssuer() {
            // The rule keys on configured PAIRS, not on distinct brands, and the shipped
            // documents say so. Both principals here really do resolve 'acme', so the VALUE
            // is unambiguous — but isMultiIssuer() is already true, which is the
            // already-multi-brand shape 007's header refuses to auto-arm on: a
            // domain='default' row there can be a SUPERADMIN write rather than a legacy row.
            String csv = "https://id.a.test=acme,https://id.b.test=acme";
            Resolution r = WikiLaunchDomain.resolve("https://id.a.test", csv);

            assertThat(new DomainRegistry("https://id.a.test", csv).knownDomains())
                    .as("premise: two pairs, ONE brand")
                    .containsExactly("acme");
            assertThat(new DomainRegistry("https://id.a.test", csv).isMultiIssuer()).isTrue();

            assertThat(r.source()).isEqualTo(Source.FALLBACK);
            assertThat(r.armsRestamp()).isFalse();
            assertThat(r.reason()).contains("2 issuer pairs");
        }

        @Test
        void twoOrMoreBrandsAreAmbiguous() {
            Resolution r = WikiLaunchDomain.resolve(
                    ISSUER, "https://id.acme.test=acme,https://id.globex.test=globex");

            assertThat(r.source()).isEqualTo(Source.FALLBACK);
            assertThat(r.armsRestamp())
                    .as("no single launch domain, and 'default' rows on a multi-brand cluster"
                            + " can be SUPERADMIN-authored")
                    .isFalse();
            assertThat(r.reason()).contains("2 brands");
        }

        @Test
        void malformedPairsAreNotABrand() {
            // No '=' => DomainRegistry skips the pair => back-compat seed => "default".
            Resolution r = WikiLaunchDomain.resolve(ISSUER, ACME_ISSUER);

            assertThat(r.source()).isEqualTo(Source.FALLBACK);
            assertThat(r.armsRestamp()).isFalse();
        }

        @Test
        void aBrandTooLongForTheDomainColumnIsRefused() {
            // sanitize() guarantees the alphabet but NOT the 64-char ceiling, so this is
            // the one shape that reaches the changelog regex and fails it. Refuse here
            // instead of publishing a target 007's own precondition will reject.
            String tooLong = "a".repeat(65);
            Resolution r = WikiLaunchDomain.resolve(ACME_ISSUER, ACME_ISSUER + "=" + tooLong);

            assertThat(new DomainRegistry(ACME_ISSUER, ACME_ISSUER + "=" + tooLong)
                    .knownDomains()).containsExactly(tooLong);
            assertThat(r.armsRestamp()).isFalse();
            assertThat(r.reason()).contains("spellable");
        }
    }

    @Nested
    class TheResolutionIsAlwaysUsable {

        @Test
        void neverReturnsNullOrBlankWhateverTheInput() {
            for (String csv : new String[] {null, "", "   ", ",", "=", "=brand", "https://x="}) {
                Resolution r = WikiLaunchDomain.resolve(null, csv);
                assertThat(r.domain()).as("csv=%s", csv).isNotNull().isNotBlank();
                assertThat(r.reason()).as("csv=%s", csv).isNotNull().isNotBlank();
            }
        }
    }
}
