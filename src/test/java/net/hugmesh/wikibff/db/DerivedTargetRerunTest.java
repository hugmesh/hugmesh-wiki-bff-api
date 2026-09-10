package net.hugmesh.wikibff.db;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import net.hugmesh.commons.testsupport.PostgresTestSupport;
import net.hugmesh.wikibff.config.WikiLaunchDomain;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * What happens on the SECOND boot — the question deriving the re-stamp target from
 * configuration newly raises (issue #19).
 *
 * <p>While the target was typed by hand it could only change when a human retyped it.
 * Now it moves with {@code app.tenancy.issuers}, so two previously-theoretical sequences
 * become things an operator can trigger by editing a Deployment: booting again unchanged,
 * and booting again after RENAMING the brand. Both are measured here rather than reasoned
 * about, because the failure mode of guessing wrong is silent data loss.
 *
 * <p>These drive Liquibase directly (as {@link DefaultDomainRestampChangesetTest} does)
 * because a second boot against the same database cannot be expressed as a second
 * {@code @SpringBootTest}.
 */
class DerivedTargetRerunTest extends PostgresTestSupport {

    private static final String MASTER = "db/changelog/db.changelog-master.xml";
    private static final String PARAM = "wiki.restamp.target.domain";
    private static final String RESTAMP_ID = "007-restamp-default-domain-to-brand";
    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    // ── harness ───────────────────────────────────────────────────────────────

    private static String freshDatabase() throws Exception {
        String name = "rerun_" + DB_SEQ.incrementAndGet();
        String admin = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
                + "/" + POSTGRES.getDatabaseName();
        try (Connection c = DriverManager.getConnection(admin, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.executeUpdate("CREATE DATABASE \"" + name + "\"");
        }
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + name;
    }

    private static Connection open(String url) throws Exception {
        return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** One boot of the application's Liquibase run, with {@code target} as the derived parameter. */
    private static void boot(String url, String target) throws Exception {
        try (Connection c = open(url)) {
            Database db = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(c));
            try (Liquibase lb = new Liquibase(MASTER, new ClassLoaderResourceAccessor(), db)) {
                if (target != null) {
                    lb.setChangeLogParameter(PARAM, target);
                }
                lb.update(new Contexts(), new LabelExpression());
            }
        }
    }

    private static void seedDefaultRow(String url, String slug) throws Exception {
        try (Connection c = open(url); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO wiki_page"
                    + " (domain, slug, title, content_markdown, content_html, author_subject)"
                    + " VALUES ('default', '" + slug + "', 'T', '# t', '<h1>t</h1>', 'sub')");
        }
    }

    private static Map<String, Integer> pageDomains(String url) throws Exception {
        Map<String, Integer> out = new LinkedHashMap<>();
        try (Connection c = open(url); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT domain, count(*) FROM wiki_page GROUP BY domain ORDER BY domain")) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getInt(2));
            }
        }
        return out;
    }

    private static int timesRun(String url) throws Exception {
        try (Connection c = open(url); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM databasechangelog"
                     + " WHERE id = '" + RESTAMP_ID + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** The value the post-processor would derive for a one-brand deployment. */
    private static String derived(String brand) {
        WikiLaunchDomain.Resolution r =
                WikiLaunchDomain.resolve("https://id.hugmesh.net", "https://id." + brand + ".test=" + brand);
        assertThat(r.armsRestamp()).isTrue();
        return r.domain();
    }

    // ── the two sequences ─────────────────────────────────────────────────────

    @Test
    void bootingAgainUnchangedIsASafeNoOp() throws Exception {
        String url = freshDatabase();
        String acme = derived("acme");

        boot(url, acme);
        seedDefaultRow(url, "written-after-the-cutover-by-a-superadmin");

        // Second boot, same configuration: the changeset is already recorded, so it does
        // not re-run. It must not throw, and it must not touch the row seeded since.
        assertThatCode(() -> boot(url, acme))
                .as("an unchanged redeploy must not fail and must not re-migrate")
                .doesNotThrowAnyException();

        assertThat(timesRun(url)).isEqualTo(1);
        assertThat(pageDomains(url))
                .as("the re-stamp is one-shot: a 'default' row written AFTER it stays put")
                .containsExactly(Map.entry("default", 1));
    }

    @Test
    void renamingTheBrandOnASecondBootFailsLOUDLYRatherThanSilently() throws Exception {
        String url = freshDatabase();

        boot(url, derived("acme"));
        assertThat(timesRun(url)).isEqualTo(1);

        // The operator edits APP_TENANCY_ISSUERS from =acme to =globex and redeploys. The
        // derived parameter changes, so the <sql> Liquibase already recorded no longer
        // matches its stored checksum.
        Throwable t = catchThrowable(() -> boot(url, derived("globex")));

        assertThat(t)
                .as("MEASURED, not assumed: a changed derived target must not be silently"
                        + " ignored. Rows sit at 'acme' while every principal now resolves"
                        + " 'globex'; a silent skip would hide that until users report an"
                        + " empty wiki. Liquibase's checksum validation turns it into a"
                        + " refused startup instead — loud, and before the app serves.")
                .isNotNull();
        // MEASURED spelling: Liquibase writes "check sum" as TWO words in
        // ValidationFailedException. Asserting "checksum" here passes nothing and fails a
        // correct implementation, which is exactly what it did on the first run of this test.
        assertThat(t.toString() + String.valueOf(t.getCause()))
                .as("and it must fail for the stored-checksum reason, not some incidental error")
                .containsIgnoringCase("check sum")
                .contains("007-restamp-default-domain-to-brand");

        assertThat(pageDomains(url))
                .as("nothing is half-migrated by the refused run")
                .isEmpty();
    }
}
