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
 * hugmesh-generator#347 was: <em>"hugmesh is the default but should always take what
 * domain the wiki is launched on"</em> — i.e. stop asking for a brand, derive it. This
 * class is that derivation, and {@link RestampTargetEnvironmentPostProcessor} publishes
 * its result as the Liquibase parameter at boot.
 *
 * <h2>The derivation is not a guess — it is the same parse the runtime uses</h2>
 *
 * <p>The launch domain is read out of {@link DomainRegistry#knownDomains()} by
 * CONSTRUCTING A REGISTRY, not by re-parsing {@code app.tenancy.issuers} here. That is
 * deliberate: the registry is what {@code PrincipalDomain.forScope} consults on every
 * request, so a value obtained this way is the string the running application will put in
 * {@code WHERE domain = ?} FOR A TOKEN FROM THAT ISSUER. A hand-rolled second parser could
 * drift from it (trailing-slash trimming, {@code sanitize()}'s lower-casing and
 * {@code [^a-z0-9-] -> '-'} replacement, malformed-pair skipping), and a drifted target
 * would re-stamp every row into a domain no principal can ever resolve — the exact
 * silent-data-loss failure 007 exists to prevent.
 *
 * <p>Deriving the same STRING is necessary but NOT sufficient: the token also has to be
 * one the resource server accepts. See the arming rule below.
 *
 * <h2>The safety rule: only arm when the runtime will agree</h2>
 *
 * <p>Deriving a VALUE is not the same as it being SAFE to re-stamp to. The re-stamp is
 * correct only when the domain it writes is the domain the running application resolves
 * FOR EVERY PRINCIPAL IT WILL ADMIT. So {@link Resolution#armsRestamp()} is true in
 * exactly one shape — {@code app.tenancy.issuers} holding exactly ONE pair, whose issuer
 * is the issuer this resource server trusts, naming a brand that is not the legacy
 * {@code "default"} sentinel:
 *
 * <pre>
 *  app.tenancy.issuers                  | runtime domainOf() | launch domain | arms
 *  ─────────────────────────────────────┼────────────────────┼───────────────┼──────
 *  unset / no valid pair                | "default"          | {@value #FALLBACK_DOMAIN}       | NO
 *  ONE pair, issuer == issuer-uri       | "acme"             | "acme"        | YES
 *  ONE pair, issuer != issuer-uri       | null (fail-closed) | {@value #FALLBACK_DOMAIN}       | NO
 *  TWO OR MORE pairs (any brand count)  | per-issuer brand   | {@value #FALLBACK_DOMAIN}       | NO
 * </pre>
 *
 * <p>NOTE the last two rows key on the number of configured PAIRS, not on the number of
 * distinct brands. Two pairs naming the same brand are two pairs, and they do not arm —
 * see below.
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
 * <h2>Why a single pair must name the issuer the resource server actually trusts</h2>
 *
 * <p>MEASURED, and the reason this check exists at all. With exactly one pair
 * {@link DomainRegistry#isMultiIssuer()} is {@code false}, so {@code SecurityConfig}
 * keeps the single-issuer {@code o.jwt(issuer-uri)} decoder and the ONLY tokens that
 * validate are those carrying
 * {@code iss = spring.security.oauth2.resourceserver.jwt.issuer-uri}. If the configured
 * pair names a DIFFERENT issuer — the compose stack's published
 * {@code http://localhost:8000} versus the internal {@code http://casdoor:8000} is
 * exactly this confusion, see {@code application.yml} — then {@code domainOf(issuer-uri)}
 * is {@code null} and every principal resolves the {@code __deny__} sentinel while the
 * brand string still parses cleanly out of the mapping. Arming there re-stamps the whole
 * wiki into a domain nobody can reach. So the derivation additionally requires
 * {@code brand.equals(registry.domainOf(issuerUri))}: in single-pair mode
 * {@code domainOf(issuerUri)} IS what every admitted principal resolves.
 *
 * <h2>Why two or more PAIRS does not arm</h2>
 *
 * <p>Two or more distinct brands: there is no single "the domain the wiki is launched on"
 * to take. Two or more pairs naming ONE brand: every principal does resolve that brand,
 * but {@link DomainRegistry#isMultiIssuer()} is already {@code true}, which is the
 * "already multi-brand cluster" shape 007's header refuses to auto-arm on — {@code
 * 'default'} rows there can be SUPERADMIN-authored (their scope is null and the
 * repositories coerce it to {@link DomainRegistry#DEFAULT_DOMAIN}) rather than legacy
 * pre-cut-over rows, and sweeping those is a decision, not a migration. Refusing both
 * keeps the rule the operator-facing documents state — <em>exactly one configured
 * pair</em> — literally true, and the explicit {@code wiki.restamp.target.domain}
 * override remains the escape hatch for a deliberate multi-issuer cut-over.
 *
 * <h2>What this guard does NOT cover (issue #22)</h2>
 *
 * <p>The SUPERADMIN-authored-{@code 'default'}-row hazard above is a property of the
 * DATA; every rule here is a property of the CONFIGURATION, and the data outlives the
 * shape. Narrowing {@code app.tenancy.issuers} from two-or-more pairs down to one
 * (decommissioning a brand) leaves 007 still PENDING — it never ran under the refused
 * shape — so the next boot arms and sweeps whatever {@code 'default'} rows that cluster
 * accumulated. Nothing in this class can see them. The pre-flight in the 007 header
 * ({@code SELECT domain, count(*) FROM wiki_page GROUP BY domain}) is the gate for that
 * transition, and issue #22 carries the options for making the precondition data-aware.
 */
public final class WikiLaunchDomain {

    /**
     * The platform's default brand name — the domain the wiki is launched on when the
     * issuer mapping names none. Per the operator: "hugmesh is the default".
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

    /**
     * Whether {@code domain} can be stored in a {@code domain} column at all — the same
     * {@code ^[a-z0-9-]{1,64}$} test the arming rule applies, exposed so the brand
     * CATALOGUE can narrow its offered list by the identical rule instead of keeping a
     * second copy of the regex. {@code DomainRegistry.sanitize()} guarantees the alphabet
     * but not the 64-character ceiling, so a registry value CAN fail this.
     *
     * @param domain the candidate brand name; may be {@code null}
     * @return {@code true} iff {@code domain} matches {@code ^[a-z0-9-]{1,64}$}
     */
    public static boolean isSpellableAsDomainColumn(final String domain) {
        return domain != null && SPELLABLE.matcher(domain).matches();
    }

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
        return resolveFrom(new DomainRegistry(
                issuerUri == null ? "" : issuerUri,
                issuersCsv == null ? "" : issuersCsv),
                issuerUri);
    }

    /**
     * The same derivation, taken from an ALREADY-CONSTRUCTED registry.
     *
     * <p>{@link #resolve(String, String)} exists because an {@code EnvironmentPostProcessor}
     * runs long before any bean does, so it has only the raw property strings. Once the
     * context is up the registry is a bean, and re-parsing the properties a second time
     * would be a second chance to drift from it — so anything holding the bean (the
     * {@code /bff/wiki/superadmin/brands} catalogue) calls THIS overload and both answers
     * come from one registry instance.
     *
     * <p>It is named {@code resolveFrom} rather than being a second {@code resolve}
     * overload for a boring but real reason: {@code resolve(null, null)} would otherwise be
     * ambiguous between {@code (String, String)} and {@code (DomainRegistry, String)}, and
     * a test that passes both arguments null is exactly the "nothing configured" case this
     * class most needs to be able to express.
     *
     * <p>{@code issuerUri} is taken separately rather than read back off the registry
     * because {@link DomainRegistry} does not expose its back-compat single issuer. It is
     * REQUIRED, not optional: without it the single-pair shape cannot be told apart from
     * the mismatched-issuer shape that arms a re-stamp no principal can reach. There is
     * deliberately no overload that omits it — a caller that could not supply it would get
     * a different {@code armsRestamp} answer from the one the boot-time post-processor
     * computed, and two derivations that disagree is the failure this class exists to
     * avoid.
     *
     * @param registry  the registry to read {@link DomainRegistry#knownDomains()} from;
     *                  must not be {@code null}
     * @param issuerUri {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}, the
     *                  single issuer this resource server trusts when the registry is not
     *                  in multi-issuer mode; may be {@code null}
     * @return the resolution; never {@code null}
     */
    public static Resolution resolveFrom(final DomainRegistry registry, final String issuerUri) {
        Set<String> brands = registry.knownDomains();

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
        if (registry.isMultiIssuer()) {
            // ONE brand but MORE THAN ONE pair. Every principal does resolve that brand, so
            // the value is not ambiguous — but isMultiIssuer() is already true, which is the
            // already-multi-brand cluster 007 refuses to auto-arm on: its 'default' rows can
            // be SUPERADMIN-authored rather than legacy. Refuse, and keep the documented
            // "exactly one configured pair" rule literally true.
            return fallback("app.tenancy.issuers configures " + registry.trustedIssuers().size()
                    + " issuer pairs " + registry.trustedIssuers() + " that all name the single"
                    + " brand '" + brands.iterator().next() + "'. Every principal does resolve"
                    + " that brand, but this is already a multi-issuer cluster, where a"
                    + " domain='default' row can be a SUPERADMIN write rather than a legacy"
                    + " row; sweeping those is a decision, not a migration. Supply"
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
        String trustedBrand = registry.domainOf(issuerUri);
        if (!only.equals(trustedBrand)) {
            // MEASURED hazard, not a theoretical one. One pair leaves isMultiIssuer() false,
            // so SecurityConfig keeps the single-issuer o.jwt(issuer-uri) decoder: the only
            // tokens that validate carry iss = issuer-uri. If the pair names a different
            // issuer, domainOf(issuer-uri) is null, PrincipalDomain hands back the __deny__
            // sentinel for every principal, and arming would re-stamp the whole wiki into a
            // domain nobody can reach.
            return fallback("app.tenancy.issuers names exactly one brand ('" + only + "'), but"
                    + " its issuer is not the one this resource server trusts"
                    + " (spring.security.oauth2.resourceserver.jwt.issuer-uri="
                    + (issuerUri == null || issuerUri.isBlank() ? "<unset>" : issuerUri)
                    + " resolves to " + (trustedBrand == null ? "no brand" : "'" + trustedBrand + "'")
                    + "). With one pair the single-issuer decoder admits only that issuer's"
                    + " tokens, so re-stamping to '" + only + "' would move every row behind a"
                    + " predicate no principal resolves. Point the pair at the trusted issuer,"
                    + " or supply wiki.restamp.target.domain explicitly");
        }
        return new Resolution(only, Source.ISSUER_MAPPING, true,
                "app.tenancy.issuers names exactly one brand ('" + only + "') on the issuer this"
                        + " resource server trusts, which is the domain every principal of this"
                        + " deployment resolves");
    }

    private static Resolution fallback(final String reason) {
        return new Resolution(FALLBACK_DOMAIN, Source.FALLBACK, false, reason);
    }
}
