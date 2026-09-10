package net.hugmesh.wikibff.db;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import net.hugmesh.commons.testsupport.PostgresTestSupport;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Migration test for changelog {@code 007-restamp-default-domain.xml} — the multi-brand
 * cut-over re-stamp (issue #19).
 *
 * <p>This is a DATA migration, so the usual repository-slice harness cannot prove it: a
 * {@code @DataR2dbcTest} applies the whole changelog to an EMPTY database, which is exactly
 * the one state in which a re-stamp has nothing to re-stamp. So this test drives Liquibase
 * itself over the {@link PostgresTestSupport} container:
 *
 * <ol>
 *   <li>{@code update("pre-default-domain-restamp")} — run 001..006 plus the tag changeset,
 *       stopping immediately BEFORE the re-stamp;</li>
 *   <li>seed rows over JDBC — some at {@code 'default'} (the single-issuer population being
 *       cut over), some already at a real brand (which must be left byte-identical);</li>
 *   <li>{@code update()} — let the re-stamp run, or not, depending on the changelog
 *       parameter;</li>
 *   <li>read the four {@code domain} columns back and compare.</li>
 * </ol>
 *
 * <p>What it proves, in the terms issue #19 asked for:
 * <ul>
 *   <li><b>The selection is right.</b> Armed, every {@code domain='default'} row in
 *       {@code wiki_page} / {@code wiki_attachment} / {@code wiki_audit_log} /
 *       {@code wiki_access} moves to the named brand and NOTHING else moves — a row already
 *       stamped {@code brandb} is untouched, and no other column changes.</li>
 *   <li><b>It is inert until armed.</b> With no parameter, with {@code 'default'}, and with a
 *       value outside {@code DomainRegistry.sanitize()}'s alphabet, the data is untouched AND
 *       the changeset stays PENDING (absent from {@code DATABASECHANGELOG}) so a later
 *       cut-over can still run it. That "still pending" assertion is the one that
 *       distinguishes {@code onFail=CONTINUE} from a {@code MARK_RAN} that would burn the
 *       migration on the first un-armed boot.</li>
 *   <li><b>The rollback returns the rows.</b> {@code rollback-to-tag} puts every re-stamped
 *       row back to {@code 'default'} and still leaves the other brand alone.</li>
 *   <li><b>The attachment invariant survives.</b> {@code wiki_attachment.domain} equals its
 *       owning page's domain before and after — the attachment read path scopes on the
 *       denormalised copy, not on the page's column.</li>
 *   <li><b>The rollback tag is laid down at the CUT-OVER run, not at first boot.</b> An
 *       un-armed deployment tags nothing; the armed run tags immediately before the
 *       re-stamp and the tag STAYS there across later deployments. That last assertion is
 *       the guard against re-introducing {@code runAlways="true"} on the tagging changeSet,
 *       which would slide the tag past the re-stamp and silently turn
 *       {@code rollback-to-tag} into a no-op for the one changeset it exists to undo.</li>
 *   <li><b>The real operator sequence works.</b> Boot un-armed on a database, then arm on
 *       THAT SAME database: the pending changeset runs. This is the whole reason
 *       {@code onFail=CONTINUE} was chosen over {@code MARK_RAN}, and every other un-armed
 *       case above uses a fresh database and so cannot prove it.</li>
 *   <li><b>A forward collision fails loudly and atomically.</b> A slug present at both
 *       {@code 'default'} and the target brand makes the UPDATE violate
 *       {@code uq_wiki_page_domain_slug}; the transaction rolls back, every row keeps its
 *       old stamp and the changeset stays PENDING, so the run can be retried once the
 *       collision is resolved. At cut-over this surfaces as a failed application START.</li>
 *   <li><b>The cost the migration itself incurs is pinned down.</b> A SUPERADMIN's write is
 *       coerced to {@code WHERE domain = 'default'}, so after the re-stamp it matches
 *       nothing — while the brand's own scoped principals do reach every migrated row.
 *       Disclosed in the changelog header and the README; asserted here so it cannot drift
 *       into an undocumented surprise.</li>
 *   <li><b>The arming parameter fails CLOSED.</b> It is spliced into the very regex check
 *       meant to sanitise it (Liquibase parameters are textual and {@code sqlCheck} has no
 *       bind form), so an injection-shaped value is fed through the whole chain and the
 *       data is asserted untouched.</li>
 * </ul>
 */
class DefaultDomainRestampChangesetTest extends PostgresTestSupport {

    /** The production changelog — the migration is tested exactly as it ships. */
    private static final String MASTER = "db/changelog/db.changelog-master.xml";

    /** The tag laid down immediately before the re-stamp changeset. */
    private static final String TAG = "pre-default-domain-restamp";

    /** The changeset id the arming precondition gates. */
    private static final String RESTAMP_ID = "007-restamp-default-domain-to-brand";

    /** The Liquibase changelog parameter that arms the migration. */
    private static final String PARAM = "wiki.restamp.target.domain";

    /** The brand the cut-over names in the armed cases. */
    private static final String BRAND = "brand-a";

    /** A brand that already exists and must survive the migration untouched. */
    private static final String OTHER = "brandb";

    /** Monotonic suffix so every test method gets its own database. */
    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    // ── harness ───────────────────────────────────────────────────────────────

    /** A fresh, empty database on the shared container; returns its JDBC URL. */
    private static String freshDatabase() throws Exception {
        String name = "restamp_" + DB_SEQ.incrementAndGet();
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

    /**
     * Run the production changelog over {@code url}. {@code target} is the value of the
     * arming parameter ({@code null} = the parameter is not supplied at all); {@code toTag}
     * stops the run at the pre-re-stamp tag.
     */
    private static void runChangelog(String url, String target, boolean toTag) throws Exception {
        try (Connection c = open(url)) {
            Database db = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(c));
            try (Liquibase lb = new Liquibase(MASTER, new ClassLoaderResourceAccessor(), db)) {
                if (target != null) {
                    lb.setChangeLogParameter(PARAM, target);
                }
                if (toTag) {
                    lb.update(TAG, new Contexts(), new LabelExpression());
                } else {
                    lb.update(new Contexts(), new LabelExpression());
                }
            }
        }
    }

    /** Roll the schema back to the pre-re-stamp tag, with the parameter the rollback needs. */
    private static void rollbackToTag(String url, String target) throws Exception {
        try (Connection c = open(url)) {
            Database db = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(c));
            try (Liquibase lb = new Liquibase(MASTER, new ClassLoaderResourceAccessor(), db)) {
                lb.setChangeLogParameter(PARAM, target);
                lb.rollback(TAG, new Contexts(), new LabelExpression());
            }
        }
    }

    /**
     * Seed the four domain-bearing tables: two pages (one with an attachment) plus an audit
     * row and an access row in {@code domain}, keyed by {@code tag} so the two brands' rows
     * are distinguishable by slug/subject.
     */
    private static void seedBrand(String url, String domain, String tag) throws Exception {
        try (Connection c = open(url); Statement s = c.createStatement()) {
            for (String slug : new String[] {tag + "-one", tag + "-two"}) {
                s.executeUpdate("INSERT INTO wiki_page"
                        + " (domain, slug, title, content_markdown, content_html, author_subject)"
                        + " VALUES ('" + domain + "', '" + slug + "', 'T', '# t', '<h1>t</h1>', 'sub-" + tag + "')");
            }
            String pageId;
            try (ResultSet rs = s.executeQuery(
                    "SELECT id FROM wiki_page WHERE slug = '" + tag + "-one'")) {
                rs.next();
                pageId = rs.getString(1);
            }
            s.executeUpdate("INSERT INTO wiki_attachment (page_id, domain, filename, mime_type, data)"
                    + " VALUES ('" + pageId + "', '" + domain + "', 'a.png', 'image/png', '\\x00'::bytea)");
            s.executeUpdate("INSERT INTO wiki_audit_log (domain, actor_subject, action, target, detail)"
                    + " VALUES ('" + domain + "', 'sub-" + tag + "', 'wiki.page.create', '" + tag + "-one', 'v1')");
            s.executeUpdate("INSERT INTO wiki_access"
                    + " (domain, user_subject, access_type, state, granted_by_subject, reason)"
                    + " VALUES ('" + domain + "', 'victim-" + tag + "', 'banned', 'active', 'admin-" + tag + "', 'spam')");
        }
    }

    /** {@code domain -> row count} across all four tables, for one table at a time. */
    private static Map<String, Integer> domains(String url, String table) throws Exception {
        Map<String, Integer> out = new LinkedHashMap<>();
        try (Connection c = open(url); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT domain, count(*) FROM " + table + " GROUP BY domain ORDER BY domain")) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getInt(2));
            }
        }
        return out;
    }

    /** The four domain-bearing tables, in changelog order. */
    private static final List<String> TABLES =
            List.of("wiki_page", "wiki_attachment", "wiki_audit_log", "wiki_access");

    /** Insert exactly one page at {@code domain}/{@code slug} — for collision setups. */
    private static void seedPageOnly(String url, String domain, String slug) throws Exception {
        try (Connection c = open(url); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO wiki_page"
                    + " (domain, slug, title, content_markdown, content_html, author_subject)"
                    + " VALUES ('" + domain + "', '" + slug + "', 'T', '# t', '<h1>t</h1>', 'sub')");
        }
    }

    /** Has the pre-re-stamp tag been recorded in DATABASECHANGELOG? */
    private static boolean tagRan(String url) throws Exception {
        try (Connection c = open(url); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT count(*) FROM databasechangelog WHERE tag = '" + TAG + "'")) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }

    /**
     * {@code ORDEREXECUTED} of one changeset id, or {@code -1} when it has not run. This is
     * the column {@code rollback-to-tag} walks backwards from, so it — not DATEEXECUTED — is
     * what decides the tag's blast radius.
     */
    private static int orderExecuted(String url, String changesetId) throws Exception {
        try (Connection c = open(url); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT orderexecuted FROM databasechangelog"
                     + " WHERE id = '" + changesetId + "'")) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    /** How many rows carry {@code title}? Used to prove an injection attempt changed nothing. */
    private static int pagesTitled(String url, String title) throws Exception {
        try (Connection c = open(url); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT count(*) FROM wiki_page WHERE title = '" + title + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** Has the re-stamp changeset been recorded as executed? */
    private static boolean restampRan(String url) throws Exception {
        try (Connection c = open(url); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT count(*) FROM databasechangelog WHERE id = '" + RESTAMP_ID + "'")) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }

    /** Every attachment's denormalised domain, paired with its owning page's domain. */
    private static List<String> attachmentVsPageDomain(String url) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = open(url); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT a.domain, p.domain FROM wiki_attachment a"
                             + " JOIN wiki_page p ON a.page_id = p.id ORDER BY a.filename, p.slug")) {
            while (rs.next()) {
                out.add(rs.getString(1) + "|" + rs.getString(2));
            }
        }
        return out;
    }

    /**
     * A database seeded with the single-issuer population (everything at {@code 'default'})
     * plus one already-branded set, stopped at the tag so the re-stamp has not yet run.
     */
    private static String seededAtTag(String target) throws Exception {
        String url = freshDatabase();
        runChangelog(url, target, true);
        seedBrand(url, "default", "legacy");
        seedBrand(url, OTHER, "other");
        return url;
    }

    // ── INERT UNLESS ARMED ────────────────────────────────────────────────────

    @Nested
    class InertUnlessArmed {

        /** No parameter at all: nothing moves, and the migration is still available later. */
        @Test
        void noParameter_leavesEveryRowAtDefault_andTheChangesetStaysPending() throws Exception {
            String url = seededAtTag(null);

            runChangelog(url, null, false);

            for (String t : TABLES) {
                assertThat(domains(url, t))
                        .as("%s must be untouched when the migration is not armed", t)
                        .containsOnlyKeys("default", OTHER);
            }
            assertThat(restampRan(url))
                    .as("un-armed the changeset must stay PENDING (onFail=CONTINUE, not MARK_RAN),"
                            + " otherwise a later cut-over can never run it")
                    .isFalse();
        }

        /** {@code 'default'} is the back-compat sentinel, not a brand: treated as un-armed. */
        @Test
        void parameterDefault_isTreatedAsUnarmed() throws Exception {
            String url = seededAtTag("default");

            runChangelog(url, "default", false);

            for (String t : TABLES) {
                assertThat(domains(url, t)).containsOnlyKeys("default", OTHER);
            }
            assertThat(restampRan(url)).isFalse();
        }

        /**
         * A brand DomainRegistry.sanitize() would rewrite (upper case) is rejected: re-stamping
         * to it would strand every row in a domain no JWT issuer can ever resolve to.
         */
        @Test
        void parameterOutsideTheSanitizeAlphabet_isRejected() throws Exception {
            String url = seededAtTag("Brand A");

            runChangelog(url, "Brand A", false);

            for (String t : TABLES) {
                assertThat(domains(url, t)).containsOnlyKeys("default", OTHER);
            }
            assertThat(restampRan(url)).isFalse();
        }
    }

    // ── ARMED: THE CUT-OVER ───────────────────────────────────────────────────

    @Nested
    class ArmedCutOver {

        /** Every 'default' row moves; every other brand's row is left exactly as it was. */
        @Test
        void movesEveryDefaultRow_inAllFourTables_andLeavesOtherBrandsAlone() throws Exception {
            String url = seededAtTag(BRAND);

            runChangelog(url, BRAND, false);

            assertThat(restampRan(url)).isTrue();
            assertThat(domains(url, "wiki_page")).containsOnly(
                    Map.entry(BRAND, 2), Map.entry(OTHER, 2));
            assertThat(domains(url, "wiki_attachment")).containsOnly(
                    Map.entry(BRAND, 1), Map.entry(OTHER, 1));
            assertThat(domains(url, "wiki_audit_log")).containsOnly(
                    Map.entry(BRAND, 1), Map.entry(OTHER, 1));
            assertThat(domains(url, "wiki_access")).containsOnly(
                    Map.entry(BRAND, 1), Map.entry(OTHER, 1));
            for (String t : TABLES) {
                assertThat(domains(url, t))
                        .as("no row may be left behind at the back-compat 'default' stamp in %s", t)
                        .doesNotContainKey("default");
            }
        }

        /**
         * The security-critical row: an ACTIVE ban must arrive in the new brand. A ban stranded
         * at 'default' is a silently un-banned principal the moment APP_TENANCY_ISSUERS is set.
         */
        @Test
        void carriesTheActiveBanIntoTheNewBrand() throws Exception {
            String url = seededAtTag(BRAND);

            runChangelog(url, BRAND, false);

            try (Connection c = open(url); Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT domain, state FROM wiki_access WHERE user_subject = 'victim-legacy'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo(BRAND);
                assertThat(rs.getString(2)).isEqualTo("active");
            }
        }

        /** The attachment's denormalised domain still equals its owning page's domain. */
        @Test
        void keepsTheAttachmentDomainEqualToItsPageDomain() throws Exception {
            String url = seededAtTag(BRAND);
            assertThat(attachmentVsPageDomain(url))
                    .as("invariant must already hold before the migration")
                    .containsExactlyInAnyOrder("default|default", OTHER + "|" + OTHER);

            runChangelog(url, BRAND, false);

            assertThat(attachmentVsPageDomain(url))
                    .containsExactlyInAnyOrder(BRAND + "|" + BRAND, OTHER + "|" + OTHER);
        }

        /** Only the domain column moves — slug, title, version and content are untouched. */
        @Test
        void changesNothingButTheDomainColumn() throws Exception {
            String url = seededAtTag(BRAND);

            runChangelog(url, BRAND, false);

            try (Connection c = open(url); Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT slug, title, content_markdown, content_html, author_subject,"
                                 + " version, is_published FROM wiki_page"
                                 + " WHERE domain = '" + BRAND + "' ORDER BY slug")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("slug")).isEqualTo("legacy-one");
                assertThat(rs.getString("title")).isEqualTo("T");
                assertThat(rs.getString("content_markdown")).isEqualTo("# t");
                assertThat(rs.getString("content_html")).isEqualTo("<h1>t</h1>");
                assertThat(rs.getString("author_subject")).isEqualTo("sub-legacy");
                assertThat(rs.getLong("version")).isEqualTo(1L);
                assertThat(rs.getBoolean("is_published")).isFalse();
            }
        }
    }

    // ── ROLLBACK ──────────────────────────────────────────────────────────────

    @Nested
    class Rollback {

        /** rollback-to-tag returns every re-stamped row to 'default', other brands intact. */
        @Test
        void rollbackToTag_returnsEveryRowToDefault() throws Exception {
            String url = seededAtTag(BRAND);
            runChangelog(url, BRAND, false);
            assertThat(restampRan(url)).isTrue();

            rollbackToTag(url, BRAND);

            for (String t : TABLES) {
                assertThat(domains(url, t))
                        .as("%s must be back to its pre-migration stamps", t)
                        .containsOnly(Map.entry("default", "wiki_page".equals(t) ? 2 : 1),
                                Map.entry(OTHER, "wiki_page".equals(t) ? 2 : 1));
            }
            assertThat(restampRan(url))
                    .as("the rolled-back changeset is removed from DATABASECHANGELOG and can run again")
                    .isFalse();
        }
    }

    // ── WHEN THE ROLLBACK TAG IS LAID DOWN ────────────────────────────────────

    /**
     * {@code rollback-to-tag} reverses everything recorded AFTER the tag, so WHERE the tag
     * sits in {@code ORDEREXECUTED} is the whole safety story. The tagging changeSet carries
     * the same arming precondition as the re-stamp so it is written during the cut-over run
     * itself — not at the first deployment of the changelog, months earlier, with every
     * changeset 008+ landing after it and inside the blast radius.
     */
    @Nested
    class TagIsLaidDownAtTheCutOverRun {

        /** Un-armed: no tag at all. A tag naming a migration that has not run is a trap. */
        @Test
        void unarmedDeployment_laysDownNoTag() throws Exception {
            String url = freshDatabase();

            runChangelog(url, null, false);

            assertThat(tagRan(url))
                    .as("an un-armed deployment must NOT lay down the pre-re-stamp tag:"
                            + " a frozen tag from first boot puts every later changeset inside"
                            + " rollback-to-tag's blast radius")
                    .isFalse();
            assertThat(restampRan(url)).isFalse();
        }

        /**
         * Armed: the tag lands immediately before the re-stamp AND STAYS THERE across a later
         * deployment. The second half is the mutation guard — adding {@code runAlways="true"}
         * to the tagging changeSet (Liquibase's usual advice for {@code tagDatabase}) re-writes
         * its ORDEREXECUTED on every run, sliding the tag PAST the re-stamp and silently
         * turning {@code rollback-to-tag} into a no-op for the one changeset it exists to undo.
         */
        @Test
        void armedRun_tagsImmediatelyBeforeTheRestamp_andDoesNotDriftPastIt() throws Exception {
            String url = seededAtTag(BRAND);

            runChangelog(url, BRAND, false);

            int tagOrder = orderExecuted(url, "007-tag-before-default-domain-restamp");
            int restampOrder = orderExecuted(url, RESTAMP_ID);
            assertThat(tagOrder).as("the tag must have run in the armed cut-over run").isPositive();
            assertThat(restampOrder).as("the re-stamp must have run").isPositive();
            assertThat(tagOrder)
                    .as("the tag must PRECEDE the re-stamp, or rollback-to-tag cannot undo it")
                    .isLessThan(restampOrder);

            // A further deployment of the same (still armed) changelog — a restart, a config
            // change, anything. Nothing may move.
            runChangelog(url, BRAND, false);

            assertThat(orderExecuted(url, "007-tag-before-default-domain-restamp"))
                    .as("the tag must not drift on a later deployment; if this fails, someone has"
                            + " added runAlways to the tagging changeSet and rollback-to-tag is now"
                            + " a silent no-op for the re-stamp")
                    .isEqualTo(tagOrder);
            assertThat(orderExecuted(url, RESTAMP_ID)).isEqualTo(restampOrder);
        }
    }

    // ── THE ACTUAL OPERATOR SEQUENCE ──────────────────────────────────────────

    /**
     * Every other un-armed case uses a FRESH database and stops there, which cannot show
     * that a changeset left PENDING is still runnable later. This is the sequence a real
     * cut-over follows: the service has been booting un-armed for months, then one armed run
     * happens on that same database.
     */
    @Nested
    class UnarmedThenArmedOnTheSameDatabase {

        @Test
        void aChangesetLeftPendingByAnUnarmedBoot_stillRunsWhenTheOperatorArmsIt() throws Exception {
            String url = freshDatabase();
            runChangelog(url, null, false);          // months of un-armed deployments
            seedBrand(url, "default", "legacy");     // the single-issuer population
            assertThat(restampRan(url)).isFalse();
            assertThat(tagRan(url)).isFalse();

            runChangelog(url, BRAND, false);         // the cut-over

            assertThat(restampRan(url))
                    .as("onFail=CONTINUE (not MARK_RAN) exists precisely so this works")
                    .isTrue();
            assertThat(tagRan(url))
                    .as("the rollback tag is written by the same armed run")
                    .isTrue();
            for (String t : TABLES) {
                assertThat(domains(url, t))
                        .as("%s must have moved wholesale to the named brand", t)
                        .containsOnly(Map.entry(BRAND, "wiki_page".equals(t) ? 2 : 1));
            }
        }
    }

    // ── FORWARD COLLISION ─────────────────────────────────────────────────────

    /**
     * The header documents a rollback that can violate a unique constraint. The FORWARD run
     * has the mirror-image hazard and it is the more surprising one, because Liquibase runs
     * at application startup: the operator sees a service that will not boot.
     */
    @Nested
    class ForwardCollisionFailsLoudlyAndAtomically {

        @Test
        void aSlugPresentAtBothStamps_abortsTheRunAndLeavesEveryRowUntouched() throws Exception {
            String url = freshDatabase();
            runChangelog(url, null, true);
            seedPageOnly(url, "default", "clash");
            seedPageOnly(url, BRAND, "clash");

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> runChangelog(url, BRAND, false));

            assertThat(thrown)
                    .as("the collision must be LOUD — at cut-over this is a failed app start")
                    .isNotNull();
            assertThat(thrown.getMessage() + " | " + String.valueOf(thrown.getCause()))
                    .contains("uq_wiki_page_domain_slug");
            assertThat(domains(url, "wiki_page"))
                    .as("nothing may be half-migrated: the whole re-stamp is one transaction")
                    .containsOnly(Map.entry("default", 1), Map.entry(BRAND, 1));
            assertThat(restampRan(url))
                    .as("the changeset must stay PENDING so the run can be retried once the"
                            + " collision is resolved")
                    .isFalse();
        }
    }

    // ── THE COST THIS MIGRATION ITSELF INCURS ─────────────────────────────────

    /**
     * Disclosed in the changelog header and the README, and pinned here so it cannot quietly
     * stop being true either way. {@code PrincipalDomain.forScope} returns {@code null} for a
     * SUPERADMIN and every WRITE path coerces that null to {@code DomainRegistry
     * .DEFAULT_DOMAIN}, so a SUPERADMIN write is literally {@code WHERE domain = 'default'}.
     * After the re-stamp there are no such rows left. Issue #15 is the fix; until it lands a
     * SUPERADMIN cannot administer a post-cut-over deployment.
     *
     * <p>This is the SAME cost the rejected sentinel would have carried — it is not what
     * makes the brand the better target. What makes the brand better is the other half
     * asserted below: the brand's own scoped principals DO reach every migrated row.
     */
    @Nested
    class SuperadminCostOfMigrating {

        @Test
        void aSuperadminsCoercedWrite_stopsMatchingMigratedRows_whileTheBrandsOwnAdminsReachThem()
                throws Exception {
            String url = seededAtTag(BRAND);
            try (Connection c = open(url); Statement s = c.createStatement()) {
                assertThat(s.executeUpdate("UPDATE wiki_page SET title = 'before'"
                        + " WHERE slug = 'legacy-one' AND domain = 'default'"))
                        .as("before the migration a SUPERADMIN's coerced write does match")
                        .isEqualTo(1);
            }

            runChangelog(url, BRAND, false);

            try (Connection c = open(url); Statement s = c.createStatement()) {
                assertThat(s.executeUpdate("UPDATE wiki_page SET title = 'after'"
                        + " WHERE slug = 'legacy-one' AND domain = 'default'"))
                        .as("after the migration the SUPERADMIN coercion to DEFAULT_DOMAIN matches"
                                + " nothing — update 409s, publish 404s, delete 204s having deleted"
                                + " nothing (issue #15)")
                        .isZero();
                assertThat(s.executeUpdate("DELETE FROM wiki_page"
                        + " WHERE slug = 'legacy-one' AND domain = 'default'"))
                        .isZero();
                assertThat(s.executeUpdate("UPDATE wiki_page SET title = 'brand-admin-can'"
                        + " WHERE slug = 'legacy-one' AND domain = '" + BRAND + "'"))
                        .as("the brand's own scoped ADMIN/EDITOR principals DO reach the migrated"
                                + " row — this is the whole reason the brand beats a sentinel")
                        .isEqualTo(1);
            }
        }
    }

    // ── THE ARMING PARAMETER IS SPLICED IN BEFORE IT IS VALIDATED ─────────────

    /**
     * Liquibase changelog parameters are TEXTUAL substitution and {@code sqlCheck} has no
     * bind-parameter form, so the operator's raw value is spliced into the very regex check
     * meant to sanitise it. The ordering is backwards; the chain still fails CLOSED, and this
     * is where that is measured rather than asserted in prose. (The operator supplying the
     * parameter already owns the database, so this is an ordering wart, not a privilege
     * boundary — but nobody should mistake the regex for a sanitiser applied before
     * substitution.)
     */
    @Nested
    class ArmingParameterFailsClosed {

        @Test
        void anInjectionShapedValue_changesNothingAndLeavesTheChangesetPending() throws Exception {
            String url = seededAtTag(null);
            String hostile = "x'; UPDATE wiki_page SET title='pwned'; --";

            runChangelog(url, hostile, false);

            assertThat(pagesTitled(url, "pwned"))
                    .as("the spliced statement must never reach the database")
                    .isZero();
            for (String t : TABLES) {
                assertThat(domains(url, t)).containsOnlyKeys("default", OTHER);
            }
            assertThat(restampRan(url)).isFalse();
            assertThat(tagRan(url)).isFalse();
        }

        /**
         * Postgres {@code ~} anchors {@code $} at end-of-STRING for a plain pattern, so a
         * trailing newline cannot smuggle a second line past the alphabet check.
         */
        @Test
        void aValueWithATrailingNewline_isRejected() throws Exception {
            String url = seededAtTag(null);

            runChangelog(url, BRAND + "\nx", false);

            for (String t : TABLES) {
                assertThat(domains(url, t)).containsOnlyKeys("default", OTHER);
            }
            assertThat(restampRan(url)).isFalse();
        }
    }
}
