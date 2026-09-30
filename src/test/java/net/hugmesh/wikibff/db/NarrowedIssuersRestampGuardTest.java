package net.hugmesh.wikibff.db;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import net.hugmesh.commons.testsupport.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.DeferredLogs;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import net.hugmesh.wikibff.config.RestampTargetEnvironmentPostProcessor;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static net.hugmesh.wikibff.config.RestampTargetEnvironmentPostProcessor.TARGET_PROPERTY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #22: narrowing {@code APP_TENANCY_ISSUERS} from two-or-more pairs to exactly one
 * must not arm the 007 re-stamp over a database that already holds other brands' rows.
 *
 * <p>The config-shape rule in {@code WikiLaunchDomain} cannot see this transition: the
 * one surviving pair looks exactly like a first-ever single-brand configuration, and 007
 * is still PENDING because it never ran under the refused 2+ shape. The DATA can tell the
 * two apart — a cluster that ran multi-brand holds rows stamped with a brand other than
 * the target. These tests drive the real {@link RestampTargetEnvironmentPostProcessor}
 * against a real database, because the guard lives in the derivation, not in 007's body
 * (editing 007 would move a recorded checksum on every deployed database).
 */
class NarrowedIssuersRestampGuardTest extends PostgresTestSupport {

    private static final String MASTER = "db/changelog/db.changelog-master.xml";
    private static final String ISSUER = "https://id.acme.test";
    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    private static String freshDatabase() throws Exception {
        String name = "narrow_" + DB_SEQ.incrementAndGet();
        String admin = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
                + "/" + POSTGRES.getDatabaseName();
        try (Connection c = DriverManager.getConnection(admin, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.executeUpdate("CREATE DATABASE \"" + name + "\"");
        }
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + name;
    }

    private static void boot(String url, String target) throws Exception {
        try (Connection c = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword())) {
            Database db = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(c));
            try (Liquibase lb = new Liquibase(MASTER, new ClassLoaderResourceAccessor(), db)) {
                if (target != null) {
                    lb.setChangeLogParameter("wiki.restamp.target.domain", target);
                }
                lb.update(new Contexts(), new LabelExpression());
            }
        }
    }

    private static void seedPage(String url, String domain, String slug) throws Exception {
        try (Connection c = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO wiki_page"
                    + " (domain, slug, title, content_markdown, content_html, author_subject)"
                    + " VALUES ('" + domain + "', '" + slug + "', 'T', '# t', '<h1>t</h1>', 'sub')");
        }
    }

    /** What the boot-time post-processor publishes for one brand, pointed at {@code url}. */
    private static String publishedTarget(String url, String issuersCsv) {
        Map<String, Object> props = new HashMap<>();
        props.put("spring.security.oauth2.resourceserver.jwt.issuer-uri", ISSUER);
        props.put("app.tenancy.issuers", issuersCsv);
        props.put("spring.liquibase.url", url);
        props.put("spring.liquibase.user", POSTGRES.getUsername());
        props.put("spring.liquibase.password", POSTGRES.getPassword());
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addLast(new MapPropertySource("config", props));
        new RestampTargetEnvironmentPostProcessor(new DeferredLogs()).postProcessEnvironment(env, null);
        return env.getProperty(TARGET_PROPERTY);
    }

    @Test
    void narrowingFromTwoBrandsToOneMustNotArmOverAnotherBrandsRows() throws Exception {
        String url = freshDatabase();
        boot(url, null);                        // schema exists, 007 PENDING
        seedPage(url, "default", "superadmin-authored");
        seedPage(url, "globex", "written-under-the-second-brand");

        assertThat(publishedTarget(url, ISSUER + "=acme"))
                .as("=acme,=globex narrowed to =acme: the database already holds 'globex' rows,"
                        + " so this is not a first single-brand cut-over and the 'default' rows"
                        + " may be SUPERADMIN-authored; arming would sweep them into 'acme'")
                .isNull();
    }

    @Test
    void aFirstEverSingleBrandCutOverStillArms() throws Exception {
        String url = freshDatabase();
        boot(url, null);
        seedPage(url, "default", "legacy-page");

        assertThat(publishedTarget(url, ISSUER + "=acme"))
                .as("control: only legacy 'default' rows, so the guard must not block the"
                        + " cut-over it exists to protect")
                .isEqualTo("acme");
    }

    @Test
    void aFreshEmptyDatabaseStillArms() throws Exception {
        assertThat(publishedTarget(freshDatabase(), ISSUER + "=acme"))
                .as("control: before Liquibase has ever run there are no tables to inspect")
                .isEqualTo("acme");
    }

    @Test
    void afterTheCutOverHasRunTheGuardDoesNotBreakStartup() throws Exception {
        String url = freshDatabase();
        boot(url, "acme");                      // 007 EXECUTED, checksum embeds 'acme'
        seedPage(url, "default", "superadmin-after");
        seedPage(url, "globex", "a-stray-other-brand-row");

        assertThat(publishedTarget(url, ISSUER + "=acme"))
                .as("once 007 is recorded the target must keep being published, otherwise the"
                        + " checksum moves and the pod crashloops on a changeset error")
                .isEqualTo("acme");
    }

    @Test
    void anUnconnectableDatabaseDoesNotBlockTheDerivation() {
        assertThat(publishedTarget("jdbc:postgresql://127.0.0.1:1/nope", ISSUER + "=acme"))
                .as("no connection means Liquibase cannot run against that URL either, so"
                        + " there is nothing to sweep; the datasource a @DynamicPropertySource"
                        + " boot test supplies is not yet visible here and must still arm")
                .isEqualTo("acme");
    }
}
