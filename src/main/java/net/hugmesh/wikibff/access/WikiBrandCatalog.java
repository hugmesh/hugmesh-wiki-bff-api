package net.hugmesh.wikibff.access;

import java.util.Comparator;
import java.util.List;

import net.hugmesh.commons.security.DomainRegistry;
import net.hugmesh.wikibff.config.WikiLaunchDomain;

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
 * <h2>Measured: the field is inert below two configured issuers</h2>
 *
 * <p>{@code WikiAccessService.writeDomain} consults {@code targetDomain} only when
 * {@link DomainRegistry#isMultiIssuer()} — which is {@code issuerToDomain.size() > 1}. So:
 *
 * <pre>
 *  app.tenancy.issuers        | a USER resolves | a SUPERADMIN write is stamped | picker
 *  ───────────────────────────┼─────────────────┼───────────────────────────────┼────────
 *  unset                      | "default"       | "default"                     | inert, and correct
 *  ONE pair "https://i=acme"  | "acme"          | "default"  (!)                | inert, and WRONG
 *  two or more pairs          | per-issuer      | the named targetDomain        | live
 * </pre>
 *
 * <p>The middle row is a real hazard, not a curiosity: a SUPERADMIN's grant or BAN is stamped
 * {@code default} while every user of that deployment resolves {@code acme}, so
 * {@link WikiAccessGuard} — which reads back {@code activeFor(subject, "acme")} — never matches
 * it. That is exactly the silently-ineffective ban PR #39 closed for 2+ brands and left open at
 * exactly 1. It matters more now than it did: deriving the 007 re-stamp target from the launch
 * domain (issue #19) makes the single-pair mapping the FIRST-CLASS single-brand launch shape.
 *
 * <p>Changing {@code writeDomain} to fail closed there would turn today's silent no-op into a
 * {@code 400} for existing deployments — a behaviour change nobody asked for — so this class
 * does NOT change it. It REPORTS it: {@code targetDomainHonoured} is false there while
 * {@code brands} is non-empty, which is a combination the console can detect and warn on
 * instead of rendering a control that does nothing.
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

    /**
     * Constructs the catalogue over the application's registry bean.
     *
     * @param registry the issuer-to-brand registry the write path validates against
     */
    public WikiBrandCatalog(DomainRegistry registry) {
        this.registry = registry;
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
                .sorted(Comparator.naturalOrder())
                .toList();
        return new WikiAccessDto.Brands(
                brands,
                WikiLaunchDomain.resolve(registry).domain(),
                registry.isMultiIssuer());
    }
}
