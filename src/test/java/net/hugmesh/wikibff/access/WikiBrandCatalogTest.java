package net.hugmesh.wikibff.access;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import net.hugmesh.commons.security.DomainRegistry;
import net.hugmesh.wikibff.security.PrincipalDomain;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WikiBrandCatalog} — the catalogue behind the console's target-brand picker.
 *
 * <p>The property under test is ONE sentence: <b>every brand this serves is a brand the write
 * path accepts, and it says plainly when the write path accepts none of them.</b> So the
 * assertions are not written against a hand-copied expectation of the registry's rules —
 * {@link #everyServedBrandIsAcceptedByTheWritePathsOwnPredicate()} re-checks each served value
 * through {@link PrincipalDomain#resolveKnownDomain(String)}, which is literally what
 * {@code WikiAccessService.writeDomain} calls. If the registry's acceptance rule ever changes,
 * that test fails rather than silently starting to serve rejects.
 */
class WikiBrandCatalogTest {

    private static WikiAccessDto.Brands snapshot(String issuerUri, String issuersCsv) {
        return new WikiBrandCatalog(new DomainRegistry(issuerUri, issuersCsv), issuerUri).snapshot();
    }

    @Nested
    @DisplayName("multi-brand (2+ configured issuer pairs) — the picker is live")
    class MultiBrand {

        private static final String CSV =
                "https://id.globex.example=globex,https://id.acme.example=acme";

        @Test
        void servesEveryConfiguredBrand_sorted() {
            assertThat(snapshot("", CSV).brands()).containsExactly("acme", "globex");
        }

        @Test
        void sortsIndependentlyOfTheConfiguredOrder() {
            // The registry preserves app.tenancy.issuers order (LinkedHashSet); the catalogue
            // must not, or the rendered option order would drift with an env-var edit.
            assertThat(snapshot("", CSV).brands())
                    .isEqualTo(snapshot("", "https://id.acme.example=acme,https://id.globex.example=globex").brands());
        }

        @Test
        void reportsThatTargetDomainIsHonoured() {
            assertThat(snapshot("", CSV).targetDomainHonoured()).isTrue();
        }

        @Test
        void reportsTheLaunchDomainFallback_whichIsNotOneOfTheBrands() {
            // With 2+ brands there is no single "domain the wiki is launched on", so the shared
            // derivation reports its fallback. The console MUST therefore check membership
            // before preselecting it — asserted here so the un-preselectable case is pinned.
            WikiAccessDto.Brands b = snapshot("", CSV);
            assertThat(b.launchDomain()).isEqualTo("hugmesh");
            assertThat(b.brands()).doesNotContain(b.launchDomain());
        }

        @Test
        void neverServesTheDefaultSentinel_evenWhenAnIssuerIsMappedToIt() {
            // 'default' is a legal value in app.tenancy.issuers but is rejected by
            // isKnownDomain before the map is consulted, so a targetDomain of 'default' is a
            // guaranteed 400. It must not reach the picker.
            List<String> brands = snapshot("",
                    "https://id.a.example=default,https://id.b.example=acme").brands();
            assertThat(brands).containsExactly("acme");
        }

        @Test
        void everyServedBrandIsAcceptedByTheWritePathsOwnPredicate() {
            DomainRegistry registry = new DomainRegistry("", CSV);
            PrincipalDomain principal = new PrincipalDomain(registry);
            List<String> brands = new WikiBrandCatalog(registry, "").snapshot().brands();
            assertThat(brands).isNotEmpty();
            for (String brand : brands) {
                // resolveKnownDomain is exactly what WikiAccessService.writeDomain calls; a
                // null here is the 400 "must target a known brand via targetDomain".
                assertThat(principal.resolveKnownDomain(brand))
                        .as("writeDomain must accept the served brand '%s'", brand)
                        .isEqualTo(brand);
            }
        }
    }

    @Nested
    @DisplayName("single-issuer back-compat — nothing to pick, and nothing is broken")
    class SingleIssuerBackCompat {

        @Test
        void servesNoBrands_becauseTheOnlyKnownDomainIsTheDefaultSentinel() {
            // issuer-uri only: the registry seeds {issuer -> "default"}, and isKnownDomain
            // rejects "default", so there is genuinely nothing offerable.
            assertThat(snapshot("https://id.hugmesh.example", "").brands()).isEmpty();
        }

        @Test
        void reportsThatTargetDomainIsNotHonoured() {
            assertThat(snapshot("https://id.hugmesh.example", "").targetDomainHonoured()).isFalse();
        }

        @Test
        void unconfigured_servesNoBrandsAndTheFallbackLaunchDomain() {
            WikiAccessDto.Brands b = snapshot("", "");
            assertThat(b.brands()).isEmpty();
            assertThat(b.targetDomainHonoured()).isFalse();
            assertThat(b.launchDomain()).isEqualTo("hugmesh");
        }
    }

    @Nested
    @DisplayName("exactly ONE configured pair — the shape where the field is inert but the "
            + "brand is real (the hazard the catalogue exists to expose)")
    class SingleConfiguredPair {

        private static final String ISSUER = "https://id.hugmesh.example";

        private static final String CSV = ISSUER + "=hugmesh";

        @Test
        void servesTheBrand_becauseTheWritePathWouldAcceptIt() {
            assertThat(snapshot(ISSUER, CSV).brands()).containsExactly("hugmesh");
        }

        @Test
        void reportsThatTargetDomainIsNOTHonoured() {
            // DomainRegistry.isMultiIssuer() is size() > 1, so writeDomain never reads the body
            // field here: a SUPERADMIN write is stamped 'default' whatever the picker chose.
            // This is the single-brand launch shape issue #19's derivation makes first-class,
            // so the console must be able to see it and warn instead of offering a live control.
            assertThat(snapshot(ISSUER, CSV).targetDomainHonoured()).isFalse();
        }

        @Test
        void theBrandsListAndTheHonouredFlagDisagree_whichIsTheDetectableSignal() {
            WikiAccessDto.Brands b = snapshot(ISSUER, CSV);
            assertThat(b.brands()).isNotEmpty();
            assertThat(b.targetDomainHonoured()).isFalse();
        }

        @Test
        void theLaunchDomainIsTheConfiguredBrand_soAPickerCouldPreselectIt() {
            WikiAccessDto.Brands b = snapshot(ISSUER, CSV);
            assertThat(b.launchDomain()).isEqualTo("hugmesh");
            assertThat(b.brands()).contains(b.launchDomain());
        }

        @Test
        void aPairOnAnUNTRUSTEDIssuerIsServedAsABrandButIsNOTTheLaunchDOMAIN() {
            // The hazard skeptic pass 1 measured: with one pair, isMultiIssuer() is false, so
            // the single-issuer decoder admits ONLY tokens whose iss equals issuer-uri. Point
            // the pair somewhere else and no principal resolves 'acme' at all. The brand is
            // still a legal targetDomain by the registry's own predicate, so it is still
            // served — but the launch domain must NOT claim to be it, or the console would
            // preselect (and the 007 derivation would re-stamp to) a domain nobody reaches.
            WikiAccessDto.Brands b = snapshot("https://id.hugmesh.example",
                    "https://casdoor.internal:8000=acme");

            assertThat(new DomainRegistry("https://id.hugmesh.example",
                    "https://casdoor.internal:8000=acme").domainOf("https://id.hugmesh.example"))
                    .as("premise: the trusted issuer resolves NO brand in this configuration")
                    .isNull();
            assertThat(b.brands()).containsExactly("acme");
            assertThat(b.launchDomain())
                    .as("falls back rather than naming a brand no admitted principal resolves")
                    .isEqualTo("hugmesh");
            assertThat(b.brands()).doesNotContain(b.launchDomain());
        }
    }

    @Nested
    @DisplayName("a brand the domain column cannot hold is never offered")
    class UnstorableBrands {

        @Test
        void aBrandLongerThanTheDomainColumnIsFilteredOut() {
            // DomainRegistry.isKnownDomain has no length cap; every `domain` column is
            // varchar(64). Offering a 65-character brand would hand the operator an option
            // whose INSERT fails. WikiLaunchDomain refuses to arm on one for the same reason,
            // so both consumers of the registry now agree about what is storable.
            String tooLong = "a".repeat(65);
            String csv = "https://id.a.example=" + tooLong + ",https://id.b.example=acme";

            assertThat(new DomainRegistry("", csv).isKnownDomain(tooLong))
                    .as("premise: the write path's own predicate accepts it")
                    .isTrue();
            assertThat(snapshot("", csv).brands()).containsExactly("acme");
        }
    }

    @Nested
    @DisplayName("the response record is immutable")
    class Immutability {

        @Test
        void doesNotStoreTheCallersList() {
            List<String> mutable = new java.util.ArrayList<>(List.of("acme"));
            WikiAccessDto.Brands b = new WikiAccessDto.Brands(mutable, "hugmesh", true);
            mutable.add("smuggled");
            assertThat(b.brands()).containsExactly("acme");
        }
    }
}
