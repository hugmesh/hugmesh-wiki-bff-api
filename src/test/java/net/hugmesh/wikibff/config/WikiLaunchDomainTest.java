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

    @Nested
    class DerivesTheLaunchDomain {

        @Test
        void singleConfiguredBrandIsTheLaunchDomainAndArms() {
            Resolution r = WikiLaunchDomain.resolve(ISSUER, "https://id.acme.test=acme");

            assertThat(r.domain()).isEqualTo("acme");
            assertThat(r.source()).isEqualTo(Source.ISSUER_MAPPING);
            assertThat(r.armsRestamp())
                    .as("one brand named => that IS the domain the wiki is launched on")
                    .isTrue();
        }

        @Test
        void derivedValueIsExactlyWhatTheRuntimeRegistryResolves() {
            // The whole point of deriving through DomainRegistry rather than re-parsing:
            // sanitize() lower-cases and replaces [^a-z0-9-], and the trailing slash is
            // trimmed. A hand-rolled parser drifts here and strands every row.
            String csv = "https://id.acme.test/=Brand A";
            Resolution r = WikiLaunchDomain.resolve(ISSUER, csv);

            String runtime = new DomainRegistry(ISSUER, csv).domainOf("https://id.acme.test");

            assertThat(r.domain())
                    .as("the re-stamp target must equal the string the repositories will query on")
                    .isEqualTo(runtime)
                    .isEqualTo("brand-a");
            assertThat(r.armsRestamp()).isTrue();
        }

        @Test
        void hugmeshArmsLikeAnyOtherBrandOnceTheOperatorNamesIt() {
            // "hugmesh is teh default": naming it in the mapping makes it the live launch
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
            Resolution r = WikiLaunchDomain.resolve(ISSUER, "https://id.acme.test");

            assertThat(r.source()).isEqualTo(Source.FALLBACK);
            assertThat(r.armsRestamp()).isFalse();
        }

        @Test
        void aBrandTooLongForTheDomainColumnIsRefused() {
            // sanitize() guarantees the alphabet but NOT the 64-char ceiling, so this is
            // the one shape that reaches the changelog regex and fails it. Refuse here
            // instead of publishing a target 007's own precondition will reject.
            String tooLong = "a".repeat(65);
            Resolution r = WikiLaunchDomain.resolve(ISSUER, "https://id.acme.test=" + tooLong);

            assertThat(new DomainRegistry(ISSUER, "https://id.acme.test=" + tooLong)
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
