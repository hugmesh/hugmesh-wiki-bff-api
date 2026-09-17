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
 * Now it moves with {@code app.tenancy.issuers}, so three previously-theoretical sequences
 * become things an operator can trigger by editing a Deployment: booting again unchanged,
 * booting again after RENAMING the brand, and booting again with the derivation NO LONGER
 * ARMING (the variable emptied, or grown to a second pair). All three are measured here
 * rather than reasoned about, because the failure mode of guessing wrong is either silent
 * data loss or a crashlooping pod whose error names a checksum instead of the config
 * change that caused it.
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

    /**
     * The value the post-processor would derive for a one-brand deployment.
     *
     * <p>The issuer-uri and the pair's issuer are the SAME string on purpose: with exactly
     * one pair the single-issuer decoder is in force, so a pair naming any other issuer
     * resolves no brand and the derivation refuses to arm on it.
     */
    private static String derived(String brand) {
        String issuer = "https://id." + brand + ".test";
        WikiLaunchDomain.Resolution r = WikiLaunchDomain.resolve(issuer, issuer + "=" + brand);
        assertThat(r.armsRestamp()).isTrue();
        return r.domain();
    }

    /**
     * What the post-processor publishes for a configuration that does NOT arm — nothing.
     *
     * <p>Measured rather than assumed for the two shapes this test drives: emptying
     * {@code APP_TENANCY_ISSUERS}, and growing it to a second brand.
     */
    private static String notArmed(String issuerUri, String issuersCsv) {
        WikiLaunchDomain.Resolution r = WikiLaunchDomain.resolve(issuerUri, issuersCsv);
        assertThat(r.armsRestamp())
                .as("premise: this configuration must NOT arm, so the post-processor"
                        + " contributes no parameter at all")
                .isFalse();
        return null;   // exactly what RestampTargetEnvironmentPostProcessor leaves defined
    }

    // ── the three sequences ───────────────────────────────────────────────────

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

    @Test
    void theDERIVEDTargetIsWhatActuallyLandsInTheRows() throws Exception {
        // Closes a composition gap a skeptic pass named: the property "rows end up at the
        // brand the DERIVATION produced" was assembled from three separate tests
        // (WikiLaunchDomainTest: derived value == runtime value; DerivedRestampTargetBootTest:
        // the value reaches the Liquibase parameter; DefaultDomainRestampChangesetTest: the
        // parameter moves rows) and never measured end to end. It is now.
        String url = freshDatabase();

        // Boot 1: pre-cut-over. No parameter, so 007 stays PENDING and the schema exists.
        boot(url, notArmed("https://id.acme.test", ""));
        assertThat(timesRun(url)).isEqualTo(0);
        seedDefaultRow(url, "a-legacy-page");

        // Boot 2: the operator sets APP_TENANCY_ISSUERS and redeploys. Nothing is typed.
        boot(url, derived("acme"));

        assertThat(timesRun(url)).isEqualTo(1);
        assertThat(pageDomains(url))
                .as("the row the wiki held before the cut-over now carries the DERIVED brand,"
                        + " which is also the string DomainRegistry.domainOf() hands the"
                        + " repositories for this configuration")
                .containsExactly(Map.entry("acme", 1));
    }

    @Test
    void unArmingAfterTheCutOverREFUSESStartupUnlessTheBrandIsPinned() throws Exception {
        String url = freshDatabase();
        String acme = derived("acme");

        boot(url, acme);
        assertThat(timesRun(url)).isEqualTo(1);
        seedDefaultRow(url, "written-after-the-cutover");

        // THE SEQUENCE THE BRANCH ORIGINALLY DOCUMENTED AS INERT AND IS NOT. Both of these
        // configurations make WikiLaunchDomain refuse to arm, so the post-processor
        // contributes NOTHING and Liquibase sees no wiki.restamp.target.domain at all:
        //   * APP_TENANCY_ISSUERS emptied      — "turn multi-brand back off", a reverted
        //                                        manifest, or ArgoCD selfHeal undoing a
        //                                        manual `kubectl set env`;
        //   * a SECOND brand added             — the documented multi-brand growth path.
        // The armed run's recorded checksum embedded 'acme' (Liquibase substitutes changelog
        // parameters into the <sql> BEFORE checksumming), so a boot without it computes a
        // different checksum and validation refuses the startup. Same mechanism as the
        // rename above; opposite operator intent, and the one the documents got wrong.
        String issuer = "https://id.acme.test";
        Throwable unset = catchThrowable(() -> boot(url, notArmed(issuer, "")));
        Throwable grown = catchThrowable(() -> boot(url,
                notArmed(issuer, issuer + "=acme,https://id.globex.test=globex")));

        for (Throwable t : new Throwable[] {unset, grown}) {
            assertThat(t)
                    .as("un-arming after the cut-over is NOT inert: it refuses startup")
                    .isNotNull();
            assertThat(t.toString() + String.valueOf(t.getCause()))
                    .as("and it fails for the stored-checksum reason, naming the changeset —"
                            + " which is why the error does not mention APP_TENANCY_ISSUERS at"
                            + " all and reads like a changelog edit to whoever sees the pod"
                            + " crashloop")
                    .containsIgnoringCase("check sum")
                    .contains("007-restamp-default-domain-to-brand");
        }

        // Nothing is migrated by the refused runs: the row seeded after the cut-over is
        // still at 'default', and the changeset is still recorded exactly once.
        assertThat(timesRun(url)).isEqualTo(1);
        assertThat(pageDomains(url)).containsExactly(Map.entry("default", 1));

        // THE REMEDY, measured rather than asserted in prose: pin the brand explicitly and
        // the same un-armed configuration boots again. This is what
        // SPRING_LIQUIBASE_PARAMETERS_WIKI_RESTAMP_TARGET_DOMAIN=<brand> does in a
        // Deployment, and it is why the README tells the operator to set it at cut-over
        // time and leave it set.
        assertThatCode(() -> boot(url, acme))
                .as("pinning the brand the forward run used restores startup")
                .doesNotThrowAnyException();
        assertThat(timesRun(url)).isEqualTo(1);
        assertThat(pageDomains(url)).containsExactly(Map.entry("default", 1));
    }
}
