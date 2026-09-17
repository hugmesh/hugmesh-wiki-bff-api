package net.hugmesh.wikibff.db;

import net.hugmesh.commons.testsupport.PostgresTestSupport;
import net.hugmesh.wikibff.config.RestampTargetEnvironmentPostProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
 * {@code @TestPropertySource} attaches its inlined properties with {@code addFirst} AFTER
 * environment post-processors have run, so no {@code @SpringBootTest} in this repo can
 * tell the two orderings apart. {@code RestampTargetEnvironmentPostProcessorTest} is the
 * test that pins the ordering, on a bare {@code StandardEnvironment}. Nothing else in the
 * suite boots with both values present at once, so without this test the early-return
 * mutation survives.
 */
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
}
