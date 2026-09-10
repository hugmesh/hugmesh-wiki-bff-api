package net.hugmesh.wikibff.db;

import net.hugmesh.commons.testsupport.PostgresTestSupport;
import net.hugmesh.wikibff.config.RestampTargetEnvironmentPostProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE SAFETY HALF of the issue-#19 derivation, and the reason the {@code hugmesh}
 * fallback is a reported value rather than an armed target.
 *
 * <p>Booted with the shipped defaults — {@code app.tenancy.issuers} empty, exactly as
 * {@code application.yml} and the platform manifest leave it — every principal resolves
 * the literal {@code "default"}, which is the stamp every stored row already carries.
 * Re-stamping those rows to {@code hugmesh} in that state would move the entire wiki
 * (pages, attachments, the audit trail, and the {@code wiki_access} grants and BANS)
 * behind a predicate no principal matches, with no error and no 401 — a silently
 * un-banned user among the consequences.
 *
 * <p>So this asserts the migration stays PENDING on an unconfigured deployment. It is the
 * assertion that would fail if someone later "finished the job" by publishing the
 * fallback as an armed target, which is precisely the mistake worth pinning down.
 *
 * @see DerivedRestampTargetBootTest for the armed half
 */
@SpringBootTest
@ActiveProfiles("test")
class UnarmedRestampBootTest extends PostgresTestSupport {

    /** No live Casdoor issuer in CI — mock the decoder so the resource server wires up. */
    @MockitoBean ReactiveJwtDecoder reactiveJwtDecoder;

    @Autowired Environment environment;

    @Value("${spring.liquibase.url}") String url;
    @Value("${spring.liquibase.user}") String user;
    @Value("${spring.liquibase.password}") String password;

    @Test
    void withNoBrandConfiguredNoTargetIsPublishedAtAll() {
        assertThat(environment.getProperty(RestampTargetEnvironmentPostProcessor.TARGET_PROPERTY))
                .as("publishing 'hugmesh' here would arm a re-stamp into a domain the runtime"
                        + " does not resolve, stranding every row")
                .isNull();
    }

    @Test
    void theRestampAndItsTagBothStayPending() throws Exception {
        assertThat(executed("007-restamp-default-domain-to-brand"))
                .as("un-armed must stay PENDING (onFail=CONTINUE, never MARK_RAN) so a real"
                        + " cut-over months later can still run it")
                .isZero();
        assertThat(executed("007-tag-before-default-domain-restamp"))
                .as("no tag before the cut-over: a tag naming a migration that has not run is a"
                        + " trap, not a safety net")
                .isZero();
    }

    @Test
    void theSchemaItselfIsFullyMigrated() throws Exception {
        // Guards against the assertions above passing for the wrong reason (e.g. Liquibase
        // not running at all, or failing early): 001..006 must have executed normally.
        assertThat(executed("006-gen-uuid-wiki_access"))
                .as("the rest of the changelog must be unaffected by the un-armed 007")
                .isEqualTo(1);
    }

    private int executed(String changesetId) throws Exception {
        try (Connection c = DriverManager.getConnection(url, user, password);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM databasechangelog"
                     + " WHERE id = '" + changesetId + "'")) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }
}
