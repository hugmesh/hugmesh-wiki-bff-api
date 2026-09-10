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
 * <p>This matters because the override is the documented escape hatch for the two shapes
 * the derivation deliberately refuses to arm (nothing configured, and two-or-more brands),
 * and because it is what keeps PR #20's arming contract working. The claim rests entirely
 * on {@code RestampTargetEnvironmentPostProcessor} contributing its property source with
 * {@code addLast} rather than {@code addFirst}; flipping that one call silently inverts the
 * precedence and would make a derived brand quietly overrule an operator who typed
 * something else. Nothing else in the suite exercises both values at the same time, so
 * without this test that mutation survives.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
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
