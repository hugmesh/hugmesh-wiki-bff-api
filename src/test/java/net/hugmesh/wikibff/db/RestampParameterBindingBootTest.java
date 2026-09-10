package net.hugmesh.wikibff.db;

import net.hugmesh.commons.testsupport.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
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
 * Proves the multi-brand cut-over re-stamp (changelog 007) can actually be ARMED the way an
 * operator arms it — through Spring Boot's {@code spring.liquibase.parameters.*} binding, at
 * application boot, with {@code LiquibaseAutoConfiguration} in charge.
 *
 * <p>Why this is separate from {@link DefaultDomainRestampChangesetTest}: that test drives
 * Liquibase directly ({@code Liquibase#setChangeLogParameter}) so it can seed rows between
 * the tag and the migration, which is the only way to assert what the migration DOES. But
 * driving the API proves nothing about the wiring an operator will use. If
 * {@code spring.liquibase.parameters.wiki.restamp.target.domain} did not reach the changelog,
 * the arming precondition would fail on every real deployment and the migration would be
 * silently inert forever — with no error, because {@code onFail="CONTINUE"} is exactly the
 * behaviour of an un-armed run.
 *
 * <p>So this test asserts the one thing the other cannot: booted with the property set, the
 * changeset is RECORDED AS EXECUTED in {@code DATABASECHANGELOG} rather than skipped. (The
 * schema Liquibase migrates at boot is empty, so there is nothing to re-stamp — the
 * executed/skipped distinction is the whole assertion.)
 *
 * @see DefaultDomainRestampChangesetTest for what the migration does to real rows
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties =
        "spring.liquibase.parameters.wiki.restamp.target.domain=brand-a")
class RestampParameterBindingBootTest extends PostgresTestSupport {

    /** No live Casdoor issuer in CI — mock the decoder so the resource server wires up. */
    @MockitoBean ReactiveJwtDecoder reactiveJwtDecoder;

    @Value("${spring.liquibase.url}") String url;
    @Value("${spring.liquibase.user}") String user;
    @Value("${spring.liquibase.password}") String password;

    @Test
    void armingViaSpringLiquibaseParameters_actuallyRunsTheRestampChangeset() throws Exception {
        try (Connection c = DriverManager.getConnection(url, user, password);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM databasechangelog"
                     + " WHERE id = '007-restamp-default-domain-to-brand'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1))
                    .as("spring.liquibase.parameters.wiki.restamp.target.domain must reach the"
                            + " changelog: if it does not, the arming precondition fails and the"
                            + " migration is silently inert on every real deployment")
                    .isEqualTo(1);
        }
    }
}
