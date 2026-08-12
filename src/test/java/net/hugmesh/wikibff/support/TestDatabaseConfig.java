package net.hugmesh.wikibff.support;

import liquibase.integration.spring.SpringLiquibase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/**
 * Runs the PRODUCTION Liquibase changelog over the Testcontainers-Postgres database that
 * {@link net.hugmesh.commons.testsupport.PostgresTestSupport} provisions, for the
 * {@code @DataR2dbcTest} repository SLICES (the mod-bff {@code PostgresSliceTestConfig}
 * pattern, exemplar: merged mod-bff #36).
 *
 * <p><b>Moved off the H2 PostgreSQL-compat store</b> (this class used to wire a per-context
 * {@code jdbc:h2:mem} + {@code r2dbc:h2:mem} pair) because the DB-generated-UUID-PK migration
 * (changelog 006) needs {@code pgcrypto}/{@code gen_random_uuid()} and a reliable
 * {@code INSERT ... RETURNING id}, NEITHER of which H2 provides. Now every slice extends
 * {@code PostgresTestSupport} (postgres:16-alpine, a fresh container db per test class) and
 * imports this config, so the repositories run against the real Liquibase-applied schema —
 * including changelog 006's {@code (gen_random_uuid())::text} DEFAULTs and the partial-unique
 * {@code wiki_access} index that only exists on Postgres.
 *
 * <p>WHY a dedicated config (vs. the full-context boot tests): a {@code @DataR2dbcTest}
 * slice auto-configures ONLY the reactive R2DBC stack — it does NOT load Spring Boot's
 * Liquibase auto-configuration. This {@code @TestConfiguration} re-adds a {@link SpringLiquibase}
 * bean (Liquibase has no R2DBC runner, so it uses a JDBC datasource) pointed at the SAME
 * container db that {@code PostgresTestSupport.@DynamicPropertySource} published into
 * {@code spring.liquibase.*}. The reactive {@code ConnectionFactory} the slice wires from
 * {@code spring.r2dbc.*} resolves to the same db, so the repository runs against the real
 * Liquibase-applied schema.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestDatabaseConfig {

    /** JDBC datasource at the container db published by PostgresTestSupport's
     *  {@code spring.liquibase.*} dynamic properties (Liquibase runs over JDBC). */
    @Bean
    public DataSource liquibaseDataSource(
            @Value("${spring.liquibase.url}") String url,
            @Value("${spring.liquibase.user}") String user,
            @Value("${spring.liquibase.password}") String password) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(url);
        ds.setUsername(user);
        ds.setPassword(password);
        return ds;
    }

    /** Apply the production changelog over the container db BEFORE the reactive
     *  repository runs (the slice depends on this bean transitively). */
    @Bean
    public SpringLiquibase liquibase(DataSource liquibaseDataSource) {
        SpringLiquibase lb = new SpringLiquibase();
        lb.setDataSource(liquibaseDataSource);
        lb.setChangeLog("classpath:db/changelog/db.changelog-master.xml");
        return lb;
    }
}
