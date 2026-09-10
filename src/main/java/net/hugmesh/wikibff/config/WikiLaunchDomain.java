package net.hugmesh.wikibff.config;

import java.util.Set;
import java.util.regex.Pattern;

import net.hugmesh.commons.security.DomainRegistry;

/**
 * Resolves THE DOMAIN THIS WIKI IS LAUNCHED ON — the brand every principal of this
 * deployment will actually resolve to at runtime — and decides whether that answer is
 * safe to use as the target of the 007 multi-brand cut-over re-stamp.
 *
 * <h2>Why this exists (issue #19)</h2>
 *
 * <p>PR #20 landed {@code 007-restamp-default-domain.xml} gated on an operator-typed
 * Liquibase parameter, {@code wiki.restamp.target.domain}. The operator's answer on
 * hugmesh-generator#347 was: <em>"hugmesh is teh default but should always take what
 * domain the wiki is launched on"</em> — i.e. stop asking for a brand, derive it. This
 * class is that derivation, and {@link RestampTargetEnvironmentPostProcessor} publishes
 * its result as the Liquibase parameter at boot.
 *
 * <h2>The derivation is not a guess — it is the same parse the runtime uses</h2>
 *
 * <p>The launch domain is read out of {@link DomainRegistry#knownDomains()} by
 * CONSTRUCTING A REGISTRY, not by re-parsing {@code app.tenancy.issuers} here. That is
 * deliberate: the registry is what {@code PrincipalDomain.forScope} consults on every
 * request, so a value obtained this way is BY CONSTRUCTION the string the running
 * application will put in {@code WHERE domain = ?}. A hand-rolled second parser could
 * drift from it (trailing-slash trimming, {@code sanitize()}'s lower-casing and
 * {@code [^a-z0-9-] -> '-'} replacement, malformed-pair skipping), and a drifted target
 * would re-stamp every row into a domain no principal can ever resolve — the exact
 * silent-data-loss failure 007 exists to prevent.
 *
 * <h2>The safety rule: only arm when the runtime will agree</h2>
 *
 * <p>Deriving a VALUE is not the same as it being SAFE to re-stamp to. The re-stamp is
 * correct only when the domain it writes is the domain the running application resolves.
 * So {@link Resolution#armsRestamp()} is true in exactly one shape — {@code
 * app.tenancy.issuers} naming exactly ONE brand that is not the legacy {@code "default"}
 * sentinel:
 *
 * <pre>
 *  app.tenancy.issuers        | runtime domainOf() | launch domain | arms re-stamp
 *  ───────────────────────────┼────────────────────┼───────────────┼──────────────
 *  unset / no valid pair      | "default"          | {@value #FALLBACK_DOMAIN}    | NO  (see below)
 *  one pair "https://i=acme"  | "acme"             | "acme"        | YES — cut-over
 *  two or more pairs          | per-issuer brand   | ambiguous     | NO  (see below)
 * </pre>
 *
 * <h2>Why the unconfigured row does NOT arm, even though it has a target</h2>
 *
 * <p>With nothing configured every principal resolves the literal {@code "default"} —
 * which is exactly what every stored row already carries, so the stamp and the runtime
 * ALREADY AGREE and there is nothing to re-stamp. Writing {@value #FALLBACK_DOMAIN} onto
 * those rows while the runtime still resolves {@code "default"} would strand the entire
 * wiki — pages, attachments, audit trail, and the {@code wiki_access} grants and BANS —
 * behind a predicate nobody matches. {@value #FALLBACK_DOMAIN} therefore becomes the live
 * launch domain the moment the operator NAMES it
 * ({@code APP_TENANCY_ISSUERS=https://id.hugmesh.net=hugmesh}), at which point this class
 * derives it from the mapping like any other brand and the re-stamp arms itself with no
 * parameter typed. It is not made live by changing
 * {@link DomainRegistry#DEFAULT_DOMAIN}: that constant is shared by admin-bff,
 * federation-api, user-bff, owncast-bff and forum-bff, each with its own database full of
 * {@code domain='default'} rows and no re-stamp migration of its own, so changing it would
 * strand those five services' data to rename this one's. See issue #19 for the full
 * reasoning.
 *
 * <h2>Why two-or-more brands does NOT arm</h2>
 *
 * <p>There is no single "the domain the wiki is launched on" to take. Worse, 007's
 * predicate is {@code WHERE domain = 'default'}, and on an already-multi-brand cluster
 * {@code 'default'} rows can also be minted by a SUPERADMIN write (their scope is null and
 * the repositories coerce it to {@link DomainRegistry#DEFAULT_DOMAIN}), so an auto-armed
 * run would sweep SUPERADMIN-authored rows into whichever brand happened to sort first.
 * The operator keeps the explicit {@code wiki.restamp.target.domain} override for this
 * case; it still wins over anything derived here.
 */
public final class WikiLaunchDomain {

    /**
     * The platform's default brand name — the domain the wiki is launched on when the
     * issuer mapping names none. Per the operator: "hugmesh is teh default".
     *
     * <p>NOTE this is the FALLBACK ANSWER, not an armed re-stamp target; see the class
     * javadoc for why the unconfigured case reports it but does not act on it.
     */
    public static final String FALLBACK_DOMAIN = "hugmesh";

    /**
     * The alphabet a re-stamp target must be spellable in — character-for-character the
     * regex 007's arming precondition applies in SQL. Kept here as well so
     * {@link Resolution#armsRestamp()} never promises a value the changelog will reject:
     * {@code DomainRegistry.sanitize()} guarantees the alphabet but NOT the 64-character
     * ceiling, which is the width of every {@code domain} column.
     */
    private static final Pattern SPELLABLE = Pattern.compile("^[a-z0-9-]{1,64}$");

    private WikiLaunchDomain() {
        // Static-only holder for the derivation; never instantiated.
    }

    /** Where {@link Resolution#domain()} came from. */
    public enum Source {
        /** Read out of a single-brand {@code app.tenancy.issuers} mapping. */
        ISSUER_MAPPING,
        /** No single brand was configured, so {@link #FALLBACK_DOMAIN} was used. */
        FALLBACK
    }

    /**
     * The derived launch domain plus the arming decision and the reason for it.
     *
     * @param domain      the domain this wiki is launched on; never {@code null} or blank
     * @param source      whether {@code domain} was derived or fell back
     * @param armsRestamp whether it is safe to use {@code domain} as 007's re-stamp target
     * @param reason      operator-facing explanation, logged verbatim at boot
     */
    public record Resolution(String domain, Source source, boolean armsRestamp, String reason) {
    }

    /**
     * Resolves the launch domain from the two properties the runtime registry itself
     * reads.
     *
     * @param issuerUri  {@code spring.security.oauth2.resourceserver.jwt.issuer-uri};
     *                   may be {@code null}
     * @param issuersCsv {@code app.tenancy.issuers}; may be {@code null} or blank
     * @return the resolution; never {@code null}
     */
    public static Resolution resolve(final String issuerUri, final String issuersCsv) {
        // Construct the REAL registry so the derived string is byte-identical to what
        // PrincipalDomain.forScope will produce for this configuration.
        Set<String> brands = new DomainRegistry(
                issuerUri == null ? "" : issuerUri,
                issuersCsv == null ? "" : issuersCsv).knownDomains();

        if (brands.isEmpty()) {
            return fallback("neither app.tenancy.issuers nor an issuer-uri is configured, so"
                    + " DomainRegistry.domainOf() returns the literal '"
                    + DomainRegistry.DEFAULT_DOMAIN + "' for every principal — which is what"
                    + " every stored row already carries, so there is nothing to re-stamp");
        }
        if (brands.size() > 1) {
            return fallback("app.tenancy.issuers names " + brands.size() + " brands " + brands
                    + ", so there is no single domain this wiki is launched on; supply"
                    + " wiki.restamp.target.domain explicitly if a cut-over is intended");
        }

        String only = brands.iterator().next();
        if (DomainRegistry.DEFAULT_DOMAIN.equals(only)) {
            return fallback("app.tenancy.issuers is empty, so the registry is in single-issuer"
                    + " back-compat mode and every principal resolves '"
                    + DomainRegistry.DEFAULT_DOMAIN + "' — the stamp the rows already carry,"
                    + " so there is nothing to re-stamp");
        }
        if (!SPELLABLE.matcher(only).matches()) {
            // Unreachable via sanitize()'s alphabet, but reachable via its missing length
            // cap. Refuse rather than publish a value 007's own precondition will reject.
            return fallback("the brand derived from app.tenancy.issuers ('" + only + "') is not"
                    + " spellable as ^[a-z0-9-]{1,64}$, so it cannot be a domain-column value");
        }
        return new Resolution(only, Source.ISSUER_MAPPING, true,
                "app.tenancy.issuers names exactly one brand ('" + only + "'), which is the"
                        + " domain every principal of this deployment resolves");
    }

    private static Resolution fallback(final String reason) {
        return new Resolution(FALLBACK_DOMAIN, Source.FALLBACK, false, reason);
    }
}
