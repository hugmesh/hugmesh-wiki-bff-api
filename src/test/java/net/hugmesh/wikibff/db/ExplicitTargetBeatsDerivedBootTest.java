package net.hugmesh.wikibff.db;

import net.hugmesh.commons.testsupport.PostgresTestSupport;
import net.hugmesh.wikibff.config.RestampTargetEnvironmentPostProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The manual override still wins over the derivation — the ONE case where both a derived
 * and an explicit target exist at once.
 *
 * <p>This matters because the override is the documented escape hatch for every shape the
 * derivation deliberately refuses to arm (nothing configured; two or more configured
 * pairs; a single pair on an untrusted issuer), because it is what keeps PR #20's arming
 * contract working, and because after a cut-over it is the ONLY way to keep booting once
 * {@code APP_TENANCY_ISSUERS} changes (see {@code DerivedTargetRerunTest
 * .unArmingAfterTheCutOverREFUSESStartupUnlessTheBrandIsPinned}).
 *
 * <p>What this test actually pins, stated precisely because the earlier wording here was
 * wrong and a mutation proved it: it catches the loss of the EXPLICIT-OVERRIDE EARLY
 * RETURN in {@code RestampTargetEnvironmentPostProcessor}. It does NOT catch
 * {@code addLast -> addFirst} — measured: that mutation leaves this test green, because
 * the early return fires first. Spring Boot's test context loader adds the
 * {@code @TestPropertySource} inlined properties to the Environment BEFORE
 * {@code SpringApplication.run}, so the post-processor reads {@code typed-by-hand} as the
 * explicit target and contributes nothing, and neither placement branch is reached. (An
 * earlier wording here said those properties attach AFTER post-processors run; that was
 * wrong, and the log assertion below is the measurement that keeps it from coming back.)
 * {@code RestampTargetEnvironmentPostProcessorTest} is the test that pins the ordering, on
 * a bare {@code StandardEnvironment}. Nothing else in the suite boots with both values
 * present at once, so without this test the early-return mutation survives.
 *
 * <p>The log assertion relies on this class's {@code @TestPropertySource} set being
 * unique, so its context is never served from Spring's test-context cache (a cached
 * context would not boot again and would print nothing).
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://id.acme.test",
        "app.tenancy.issuers=https://id.acme.test=acme",
        "spring.liquibase.parameters.wiki.restamp.target.domain=typed-by-hand"})
class ExplicitTargetBeatsDerivedBootTest extends PostgresTestSupport {

    /** No live Casdoor issuer in CI — mock the decoder so the resource server wires up. */
    @MockitoBean ReactiveJwtDecoder reactiveJwtDecoder;

    @Autowired Environment environment;

    @Test
    void anExplicitlySetTargetOverridesTheDerivedLaunchDomain() {
        assertThat(environment.getProperty(RestampTargetEnvironmentPostProcessor.TARGET_PROPERTY))
                .as("the derivation would say 'acme' here; an operator who typed a value must"
                        + " still win, or the override escape hatch does not exist")
                .isEqualTo("typed-by-hand");
    }

    @Test
    void thePostProcessorSawTheInlinedValueAsTheExplicitTarget(CapturedOutput output) {
        // Value equality above would also hold if the post-processor never saw the value and
        // the inlined source merely outranked a bottom-of-list derived contribution. This
        // line is printed only from the explicit-value early return, so it proves the
        // @TestPropertySource value was already in the Environment when post-processors ran.
        assertThat(output.getAll())
                .as("@TestPropertySource values must be visible to environment post-processors;"
                        + " the post-processor has to log 'typed-by-hand' as the explicit target")
                .contains("re-stamp target explicitly set to 'typed-by-hand'");
    }
}
