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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The operator does not type a brand any more (issue #19). Proves the whole chain, at
 * boot, with {@code LiquibaseAutoConfiguration} in charge and NO
 * {@code wiki.restamp.target.domain} supplied by anyone:
 *
 * <pre>
 *   APP_TENANCY_ISSUERS=https://id.acme.test=acme
 *      -> DomainRegistry parses one brand, "acme"
 *      -> WikiLaunchDomain.resolve says that IS the launch domain, and arms
 *      -> RestampTargetEnvironmentPostProcessor publishes it as
 *         spring.liquibase.parameters.wiki.restamp.target.domain
 *      -> 007's arming precondition passes
 *      -> the rollback tag is laid down and the re-stamp EXECUTES
 * </pre>
 *
 * <p>Every link is asserted separately, because a break in any one of them is silent:
 * {@code onFail="CONTINUE"} makes a failed arming precondition indistinguishable from a
 * deliberately un-armed run, so "the application booted" proves nothing at all here.
 *
 * <p>What this test does NOT cover, stated rather than implied: it does not assert what
 * the migration does to rows. Liquibase runs during context refresh, before a test can
 * seed anything, so the schema it migrates is empty and the executed-vs-pending
 * distinction is the whole assertion. Row-level behaviour of the very same changelog —
 * all four tables moved, other brands untouched, rollback exact, collisions atomic — is
 * {@link DefaultDomainRestampChangesetTest}'s job.
 *
 * @see UnarmedRestampBootTest for the safety half: unconfigured must NOT arm
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "app.tenancy.issuers=https://id.acme.test=acme")
class DerivedRestampTargetBootTest extends PostgresTestSupport {

    /** No live Casdoor issuer in CI — mock the decoder so the resource server wires up. */
    @MockitoBean ReactiveJwtDecoder reactiveJwtDecoder;

    @Autowired Environment environment;

    @Value("${spring.liquibase.url}") String url;
    @Value("${spring.liquibase.user}") String user;
    @Value("${spring.liquibase.password}") String password;

    @Test
    void theTargetIsDerivedFromTheLaunchDomainWithNobodySupplyingIt() {
        assertThat(environment.getProperty(RestampTargetEnvironmentPostProcessor.TARGET_PROPERTY))
                .as("the re-stamp target must come from app.tenancy.issuers alone — this test"
                        + " sets no wiki.restamp.target.domain anywhere")
                .isEqualTo("acme");
    }

    @Test
    void theDerivedTargetActuallyArmsTheChangesetAndItsRollbackTag() throws Exception {
        assertThat(executed("007-restamp-default-domain-to-brand"))
                .as("a derived target must arm the re-stamp exactly as a typed one did")
                .isEqualTo(1);
        assertThat(executed("007-tag-before-default-domain-restamp"))
                .as("the rollback tag is gated on the SAME precondition, so it must arm too —"
                        + " an armed re-stamp with no tag would leave the cut-over un-undoable")
                .isEqualTo(1);
        assertThat(tagLaidDown())
                .as("the tag must exist by name; rollback-to-tag is the documented undo")
                .isEqualTo(1);
    }

    private int executed(String changesetId) throws Exception {
        return count("SELECT count(*) FROM databasechangelog WHERE id = '" + changesetId + "'");
    }

    private int tagLaidDown() throws Exception {
        return count("SELECT count(*) FROM databasechangelog"
                + " WHERE tag = 'pre-default-domain-restamp'");
    }

    private int count(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(url, user, password);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }
}
