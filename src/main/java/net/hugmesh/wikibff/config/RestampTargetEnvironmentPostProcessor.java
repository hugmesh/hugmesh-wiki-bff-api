package net.hugmesh.wikibff.config;

import java.util.Map;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Publishes the 007 cut-over re-stamp target at boot, DERIVED from the domain this wiki
 * is launched on, so the operator never types a brand (issue #19; operator answer on
 * hugmesh-generator#347: <em>"hugmesh is the default but should always take what domain
 * the wiki is launched on"</em>).
 *
 * <h2>What it does</h2>
 *
 * <p>Runs {@link WikiLaunchDomain#resolve} over the same two properties the runtime
 * {@code DomainRegistry} reads, and — only when that resolution
 * {@linkplain WikiLaunchDomain.Resolution#armsRestamp() arms the re-stamp} — contributes
 * {@code spring.liquibase.parameters.wiki.restamp.target.domain}. Liquibase's own
 * auto-configuration binds it, so {@code 007-restamp-default-domain.xml}'s existing
 * arming precondition sees a defined parameter and the migration runs, in the SAME boot
 * that first configures the brand and therefore before the application serves a single
 * request under it.
 *
 * <h2>Precedence: an explicit operator value always wins</h2>
 *
 * <p>The override is protected by an explicit CHECK, not by ordering: when a non-blank
 * value is already readable here — an {@code application.yml} entry, a
 * {@code SPRING_LIQUIBASE_PARAMETERS_WIKI_RESTAMP_TARGET_DOMAIN} environment variable, a
 * {@code -D} system property — this contributes nothing at all. That keeps PR #20's manual
 * arming contract intact as the escape hatch for every shape the derivation deliberately
 * refuses (nothing configured; two or more configured pairs; a single pair whose issuer is
 * not the one the resource server trusts) — AND as the way to keep a brand pinned after a
 * cut-over, which the next section explains is not optional.
 *
 * <p>{@link org.springframework.core.env.MutablePropertySources#addLast addLast} is the
 * second line of defence, covering sources attached AFTER post-processors run (a test's
 * inlined {@code properties = {...}}, an {@code ApplicationContextInitializer}). The one
 * exception is a BLANK explicit value: blank is not an override, but it does outrank a
 * lowest-precedence contribution, so it would silently suppress the derivation and leave
 * the cut-over inert. That case — and only that case — is contributed with
 * {@code addFirst}.
 *
 * <h2>Why an EnvironmentPostProcessor and not a bean</h2>
 *
 * <p>Liquibase runs during context refresh from a {@code SpringLiquibase} whose
 * parameters are bound from {@code LiquibaseProperties}, so the value has to be in the
 * Environment BEFORE that binding — earlier than any bean of ours could mutate it. This
 * runs at {@link Ordered#LOWEST_PRECEDENCE} among post-processors, which places it after
 * Spring Boot's own config-data processor, so {@code application.yml} (and the
 * {@code ${APP_TENANCY_ISSUERS:}} placeholder in it) is already resolvable.
 *
 * <h2>Not arming is silent-safe ONLY BEFORE THE CUT-OVER</h2>
 *
 * <p>When the resolution does not arm, this contributes NOTHING. On a database where the
 * re-stamp has never run that is exactly inert: the parameter stays undefined, 007's
 * precondition fails, {@code onFail="CONTINUE"} leaves the changeset PENDING, and no row
 * is touched.
 *
 * <p>ON A DATABASE WHERE THE CUT-OVER HAS ALREADY RUN IT IS NOT INERT — IT REFUSES
 * STARTUP. Liquibase substitutes the parameter into the {@code <sql>} BEFORE it checksums
 * the changeset, so the checksum recorded by the armed run embeds the brand. A later boot
 * that publishes no parameter (or a different one) computes a different checksum and dies
 * in {@code ValidationFailedException: ... 007-restamp-default-domain-to-brand ... was:
 * 9:&lt;a&gt; but is now: 9:&lt;b&gt;}. That is the same guard that makes a brand RENAME
 * fail loudly, and it is deliberate — but it means that once the cut-over has run,
 * {@code APP_TENANCY_ISSUERS} is load-bearing for STARTUP, not just for scoping: emptying
 * it, or extending it to a second pair, crashloops the pod with an error that names a
 * checksum rather than the configuration change. The remedy is to pin the brand
 * explicitly and permanently after the cut-over
 * ({@code SPRING_LIQUIBASE_PARAMETERS_WIKI_RESTAMP_TARGET_DOMAIN=<brand>}), which the
 * override path above honours on every subsequent boot regardless of what the derivation
 * says. Measured by {@code DerivedTargetRerunTest}; spelled out in the README and in
 * 007's own header, and named in the not-armed log line below.
 *
 * <p>The reason is logged at INFO either way, so a boot log always states which domain the
 * wiki considers itself launched on and whether the re-stamp armed.
 */
public class RestampTargetEnvironmentPostProcessor
        implements EnvironmentPostProcessor, Ordered {

    /**
     * The Liquibase changelog parameter 007's arming precondition tests, in its Spring
     * Boot {@code spring.liquibase.parameters.*} binding form.
     */
    public static final String TARGET_PROPERTY =
            "spring.liquibase.parameters.wiki.restamp.target.domain";

    /** The issuer-to-brand mapping the launch domain is derived from. */
    static final String ISSUERS_PROPERTY = "app.tenancy.issuers";

    /** The back-compat single-issuer URI, read so the registry parse matches the runtime's. */
    static final String ISSUER_URI_PROPERTY =
            "spring.security.oauth2.resourceserver.jwt.issuer-uri";

    /** Name of the property source this contributes, so it is identifiable in an env dump. */
    static final String SOURCE_NAME = "wikiDerivedRestampTarget";

    private final Log log;

    /**
     * Constructs the post-processor with Spring Boot's deferred log factory — logging
     * from an {@code EnvironmentPostProcessor} predates the logging system's
     * initialisation, so the output has to be replayed rather than written directly.
     *
     * @param logFactory the deferred log factory Spring Boot supplies to post-processors
     */
    public RestampTargetEnvironmentPostProcessor(final DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(RestampTargetEnvironmentPostProcessor.class);
    }

    @Override
    public int getOrder() {
        // After ConfigDataEnvironmentPostProcessor, so application.yml is readable here.
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(
            final ConfigurableEnvironment environment, final SpringApplication application) {

        WikiLaunchDomain.Resolution resolution = WikiLaunchDomain.resolve(
                environment.getProperty(ISSUER_URI_PROPERTY),
                environment.getProperty(ISSUERS_PROPERTY));

        String explicit = environment.getProperty(TARGET_PROPERTY);
        if (explicit != null && !explicit.isBlank()) {
            // Contribute NOTHING rather than rely on ordering to lose the race: in
            // production a deliberate value arrives from application.yml or the
            // SPRING_LIQUIBASE_PARAMETERS_* environment variable, both readable here.
            log.info("wiki-bff 007: re-stamp target explicitly set to '" + explicit
                    + "'; the derived launch domain is '" + resolution.domain()
                    + "' (" + resolution.source() + "). The explicit value wins.");
            return;
        }

        if (!resolution.armsRestamp()) {
            log.info("wiki-bff 007: launch domain resolves to '" + resolution.domain()
                    + "' (" + resolution.source() + ") and the cut-over re-stamp is NOT armed — "
                    + resolution.reason() + ". On a database where the cut-over has NOT run this"
                    + " is inert: the changeset stays PENDING and no row is touched. On one where"
                    + " it HAS run, Liquibase validation will REFUSE this startup, because the"
                    + " recorded checksum embeds the brand that was substituted into the SQL — set"
                    + " SPRING_LIQUIBASE_PARAMETERS_WIKI_RESTAMP_TARGET_DOMAIN to that brand to"
                    + " boot, and keep it set.");
            return;
        }

        // A BLANK value is not an override, and it must not be allowed to act as one. An
        // empty SPRING_LIQUIBASE_PARAMETERS_WIKI_RESTAMP_TARGET_DOMAIN — the shape a
        // templated Deployment env entry produces when its source value is unset —
        // outranks a lowest-precedence contribution, so publishing at addLast would
        // leave the blank in force,
        // 007's regex would reject it, and the cut-over would be SILENTLY inert with the
        // brand correctly configured. So the blank case, and only it, is contributed at the
        // top instead. Measured by RestampTargetEnvironmentPostProcessorTest.
        boolean blankOverrideInForce = explicit != null;
        MapPropertySource derived = new MapPropertySource(
                SOURCE_NAME, Map.of(TARGET_PROPERTY, resolution.domain()));
        if (blankOverrideInForce) {
            environment.getPropertySources().addFirst(derived);
        } else {
            environment.getPropertySources().addLast(derived);
        }
        log.info("wiki-bff 007: launch domain derived as '" + resolution.domain()
                + "' (" + resolution.source() + ") — " + resolution.reason()
                + ". Arming the cut-over re-stamp: any domain='"
                + net.hugmesh.commons.security.DomainRegistry.DEFAULT_DOMAIN
                + "' row in wiki_page, wiki_attachment, wiki_audit_log and wiki_access will be"
                + " moved to '" + resolution.domain() + "' before this application serves.");
    }
}
