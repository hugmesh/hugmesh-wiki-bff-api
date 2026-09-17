package net.hugmesh.wikibff.access;

import java.util.Comparator;
import java.util.List;

import net.hugmesh.commons.security.DomainRegistry;
import net.hugmesh.wikibff.config.WikiLaunchDomain;
import net.hugmesh.wikibff.security.PrincipalDomain;

/**
 * The BRAND CATALOGUE served to the admin console's target-brand picker
 * (hugmesh-wiki-bff-api#12, hugmesh-wiki-ui#3).
 *
 * <h2>Why the bff has to answer this</h2>
 *
 * <p>The authoritative brand set lives in {@link DomainRegistry} (issuer -> brand, seeded
 * from {@code app.tenancy.issuers}) and was never served. The console could therefore only
 * OBSERVE brands — union the server-stamped {@code domain} of the rows it happened to read —
 * which is a strict subset of the registry, so its field had to stay free text and a brand
 * with no rows yet was untypeable-until-typed. That was the honest maximum from the UI; it is
 * not the honest maximum from here.
 *
 * <h2>The one rule this class exists to keep</h2>
 *
 * <p><b>Never offer a brand the write path will not honour.</b> A picker whose options the
 * server discards is worse than no picker: the operator believes they targeted brand X and the
 * row lands somewhere else, silently. So {@link #snapshot()} does not re-implement the
 * acceptance rule — it runs the candidate values through
 * {@link DomainRegistry#isKnownDomain(String)}, the SAME predicate
 * {@code PrincipalDomain.resolveKnownDomain} calls on the write path, and reports separately
 * whether {@code targetDomain} is consulted at all.
 *
 * <h2>Measured: when the field is consulted at all</h2>
 *
 * <p>{@code WikiAccessService.writeDomain} used to consult {@code targetDomain} only when
 * {@link DomainRegistry#isMultiIssuer()} — which is {@code issuerToDomain.size() > 1}, i.e.
 * a count of configured PAIRS, not of distinct brands. Issue #21 measured what that cost at
 * exactly one pair, and the operator answered it on 2026-09-16 (<em>"Fix: stamp the
 * configured brand"</em>, decision 26 on hugmesh-generator#347). The current contract:
 *
 * <pre>
 *  app.tenancy.issuers        | a USER resolves | a SUPERADMIN write is stamped | picker
 *  ───────────────────────────┼─────────────────┼───────────────────────────────┼────────
 *  unset                      | "default"       | "default"                     | inert, and correct
 *  ONE pair "https://i=acme"  | "acme"          | "acme" (or 400 on a mismatch) | live
 *  two or more PAIRS          | per-issuer      | the named targetDomain        | live
 * </pre>
 *
 * <p>The middle row used to stamp {@code default} while every user of that deployment
 * resolved {@code acme}, so {@link WikiAccessGuard} — which reads back
 * {@code activeFor(subject, "acme")} — never matched it and a SUPERADMIN's ban silently did
 * nothing. That was the silently-ineffective ban PR #39 closed for 2+ brands and left open at
 * exactly 1; it is now closed at 1 too, which matters because deriving the 007 re-stamp target
 * from the launch domain (issue #19) makes the single-pair mapping the FIRST-CLASS single-brand
 * launch shape. The picker is therefore LIVE on that shape and the console should render a
 * control, not a warning.
 *
 * <p>{@code targetDomainHonoured} is still false — with {@code brands} empty — on a
 * back-compat single-issuer deployment, where every principal genuinely is {@code default}
 * and the field really is inert. That combination (no brands, not honoured) is the correct
 * "nothing to pick" signal; the old hazard combination (brands non-empty while not honoured)
 * can no longer occur.
 *
 * <p>ONE REPO AWAY, and MEASURED rather than assumed (a skeptic pass corrected an earlier
 * claim here that said the opposite): hugmesh-wiki-ui derives five {@code BrandMode}s from
 * these three fields, but it derives them AT RUNTIME — {@code applyCatalog} branches on
 * {@code targetDomainHonoured} FIRST and reads {@code brands.length} only to tell the two
 * INERT shapes apart. So the moment this bff reports {@code honoured=true} the shipped console
 * renders its live {@code picker} with NO code change and stops warning on its own; there is
 * no console edit paired to this class. The only artefact that does go stale is the copy of
 * this repo's {@code openapi.json} that repo regenerates its typed client from, and the
 * decision-26 delta there is description text only. That hand-off is recorded on #21 and on
 * hugmesh-wiki-ui#3.
 *
 * <h2>Deliberately NOT a {@code @Component}</h2>
 *
 * <p>This is a pure function of an immutable config object — no lifecycle, no state, nothing
 * to stub. {@link WikiAccessController} constructs it from the {@link DomainRegistry} bean
 * that {@code SecurityConfig} already {@code @Import}s, so every {@code @WebFluxTest} slice
 * that boots the real security chain gets the endpoint working with no extra scaffolding. As
 * a scanned bean it would be invisible to those slices (a {@code @WebFluxTest} scans
 * controllers, not components), and the controller would fail to construct in three existing
 * test contexts — a failure mode with no production meaning at all.
 */
public class WikiBrandCatalog {

    private final DomainRegistry registry;

    private final String issuerUri;

    /**
     * Constructs the catalogue over the application's registry bean.
     *
     * @param registry  the issuer-to-brand registry the write path validates against
     * @param issuerUri {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} — the
     *                  single issuer this resource server trusts outside multi-issuer
     *                  mode. Passed through to {@link WikiLaunchDomain} so the catalogue's
     *                  {@code launchDomain} is the SAME answer the boot-time re-stamp
     *                  derivation computed, including its refusal to call a brand the
     *                  launch domain when the trusted issuer does not resolve it.
     */
    public WikiBrandCatalog(DomainRegistry registry, String issuerUri) {
        this.registry = registry;
        this.issuerUri = issuerUri;
    }

    /**
     * The catalogue as it stands for this configuration.
     *
     * <p>Stateless and cheap: {@link DomainRegistry} is immutable after construction, so the
     * answer cannot change between requests and nothing is cached.
     *
     * @return the brands accepted as a {@code targetDomain}, the launch domain, and whether
     *         {@code targetDomain} is consulted at all; never {@code null}
     */
    public WikiAccessDto.Brands snapshot() {
        // FILTER THROUGH THE WRITE PATH'S OWN PREDICATE, never a re-implementation of it:
        // isKnownDomain is what resolveKnownDomain calls, so every surviving value is one the
        // write path accepts by construction, and the values it always rejects ("default", the
        // blank string) are dropped without this class having to know they are special.
        List<String> brands = registry.knownDomains().stream()
                .filter(registry::isKnownDomain)
                // ONE narrowing on top of the write path's predicate, never a widening:
                // isKnownDomain has no length cap, but every `domain` column is
                // varchar(64), so a brand longer than that is one the INSERT would reject
                // after the operator picked it. Same test WikiLaunchDomain applies before
                // arming the re-stamp, so the two consumers of this registry cannot
                // disagree about which brands are storable.
                .filter(WikiLaunchDomain::isSpellableAsDomainColumn)
                .sorted(Comparator.naturalOrder())
                .toList();
        return new WikiAccessDto.Brands(
                brands,
                WikiLaunchDomain.resolveFrom(registry, issuerUri).domain(),
                // NOT a re-implementation of the write path's rule either: writeDomain reads
                // targetDomain in exactly two states — 2+ configured pairs, or a single
                // configured brand — and the second of those is PrincipalDomain's own
                // predicate, called here rather than copied.
                registry.isMultiIssuer() || PrincipalDomain.soleConfiguredBrand(registry) != null);
    }
}
