package net.hugmesh.wikibff.config;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.DeferredLogs;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

import static net.hugmesh.wikibff.config.RestampTargetEnvironmentPostProcessor.ISSUERS_PROPERTY;
import static net.hugmesh.wikibff.config.RestampTargetEnvironmentPostProcessor.ISSUER_URI_PROPERTY;
import static net.hugmesh.wikibff.config.RestampTargetEnvironmentPostProcessor.SOURCE_NAME;
import static net.hugmesh.wikibff.config.RestampTargetEnvironmentPostProcessor.TARGET_PROPERTY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The post-processor's contract with the Environment, tested directly rather than through
 * a booted context — because a {@code @SpringBootTest} CANNOT see the difference.
 *
 * <p>That is a measured statement, not a preference. {@code @TestPropertySource} attaches
 * its inlined properties with {@code addFirst} AFTER environment post-processors have run,
 * so inlined properties outrank anything this class contributes no matter which end of the
 * list it is added to. A mutation flipping {@code addLast} to {@code addFirst} therefore
 * SURVIVES every boot test in this repo — it was run, and it did survive — while being a
 * real production regression, since in production an operator's explicit value arrives
 * from {@code application.yml} or the {@code SPRING_LIQUIBASE_PARAMETERS_*} environment
 * variable rather than from a test annotation. These two tests are what close that gap.
 */
class RestampTargetEnvironmentPostProcessorTest {

    private static final String ISSUER = "https://id.hugmesh.net";

    private static RestampTargetEnvironmentPostProcessor processor() {
        return new RestampTargetEnvironmentPostProcessor(new DeferredLogs());
    }

    /** An environment carrying the given properties in one ordinary, lower-precedence source. */
    private static StandardEnvironment envWith(Map<String, Object> properties) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addLast(new MapPropertySource("config", properties));
        return env;
    }

    @Test
    void aDerivedTargetIsContributedAtTheVeryBOTTOMOfThePropertySources() {
        StandardEnvironment env = envWith(Map.of(
                ISSUER_URI_PROPERTY, ISSUER,
                ISSUERS_PROPERTY, "https://id.acme.test=acme"));

        processor().postProcessEnvironment(env, null);

        assertThat(env.getProperty(TARGET_PROPERTY)).isEqualTo("acme");

        List<PropertySource<?>> sources = env.getPropertySources().stream().toList();
        assertThat(sources.get(sources.size() - 1).getName())
                .as("the derived target must be the LOWEST-precedence source in the environment"
                        + " (addLast, never addFirst), so that any source carrying a deliberate"
                        + " operator value outranks it")
                .isEqualTo(SOURCE_NAME);
    }

    @Test
    void anExplicitTargetAlreadyInTheEnvironmentIsNeverOverwritten() {
        // The production shape of "the operator set it": application.yml, or the
        // SPRING_LIQUIBASE_PARAMETERS_WIKI_RESTAMP_TARGET_DOMAIN environment variable. Both
        // are readable when post-processors run, so this is the path that actually protects
        // the override — the addLast placement above is the second line of defence, for
        // sources attached later still.
        StandardEnvironment env = envWith(Map.of(
                ISSUER_URI_PROPERTY, ISSUER,
                ISSUERS_PROPERTY, "https://id.acme.test=acme",
                TARGET_PROPERTY, "typed-by-hand"));

        processor().postProcessEnvironment(env, null);

        assertThat(env.getPropertySources().contains(SOURCE_NAME))
                .as("with an explicit value present the processor must contribute NOTHING at"
                        + " all, rather than rely on ordering to lose the race")
                .isFalse();
        assertThat(env.getProperty(TARGET_PROPERTY)).isEqualTo("typed-by-hand");
    }

    @Test
    void aBlankExplicitValueIsNotTreatedAsAnOverride() {
        StandardEnvironment env = envWith(Map.of(
                ISSUER_URI_PROPERTY, ISSUER,
                ISSUERS_PROPERTY, "https://id.acme.test=acme",
                TARGET_PROPERTY, "   "));

        processor().postProcessEnvironment(env, null);

        assertThat(env.getProperty(TARGET_PROPERTY))
                .as("a blank value arms nothing (007's regex rejects it), so it must not"
                        + " suppress the derivation and leave the cut-over silently inert."
                        + " An empty SPRING_LIQUIBASE_PARAMETERS_* env var is the reachable"
                        + " shape — the platform manifest already ships APP_TENANCY_ISSUERS"
                        + " exactly that way. This assertion FAILED on the first run: the"
                        + " blank outranked the addLast contribution and won.")
                .isEqualTo("acme");
    }

    @Test
    void anUnarmableConfigurationContributesNothing() {
        StandardEnvironment env = envWith(Map.of(ISSUER_URI_PROPERTY, ISSUER));

        processor().postProcessEnvironment(env, null);

        assertThat(env.getPropertySources().contains(SOURCE_NAME)).isFalse();
        assertThat(env.getProperty(TARGET_PROPERTY))
                .as("publishing the 'hugmesh' fallback here would arm a re-stamp into a domain"
                        + " the runtime does not resolve")
                .isNull();
    }
}
