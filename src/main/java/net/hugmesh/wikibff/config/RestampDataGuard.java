package net.hugmesh.wikibff.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Properties;

import net.hugmesh.commons.security.DomainRegistry;

/**
 * The DATA half of the 007 arming rule (issue #22).
 *
 * <p>{@link WikiLaunchDomain} decides from CONFIGURATION alone, and the hazard it
 * guards against is a property of the DATA: on a cluster that ever ran two or more brands,
 * SUPERADMIN writes (null scope) are coerced to {@code domain='default'}, so those rows
 * are not legacy rows and must not be swept into a brand. Narrowing
 * {@code app.tenancy.issuers} from two-or-more pairs to one leaves 007 PENDING and the
 * single surviving pair looks exactly like a first-ever cut-over.
 *
 * <p>What the data CAN tell apart: a cluster that ran multi-brand holds rows stamped with
 * a brand other than the target. A genuine first single-brand cut-over holds only
 * {@code 'default'} rows. So arming is refused when any domain-bearing table holds a row
 * in a domain that is neither {@code 'default'} nor the target.
 *
 * <p>Two deliberate exceptions to "refuse":
 * <ul>
 *   <li>007 already recorded in {@code databasechangelog}: the derived target must keep
 *       being published, because Liquibase checksums the substituted SQL and a missing
 *       parameter crashloops startup. The re-run is a no-op, so nothing can be swept.</li>
 *   <li>the tables do not exist yet (first boot): nothing to sweep.</li>
 * </ul>
 * If the database cannot be CONNECTED to, the guard allows: Liquibase dials the very same
 * URL moments later and refuses to start on its own, so there is nothing to sweep, and
 * failing closed here would also break every {@code @DynamicPropertySource} boot test,
 * whose JDBC URL is attached only after environment post-processors have run. Once
 * connected, any failure to inspect FAILS CLOSED (refuses to arm).
 *
 * <p>Known residual, stated not hidden: a cluster that ran multi-brand but whose every
 * authored row is a SUPERADMIN {@code 'default'} row shows no foreign domain and is
 * indistinguishable from a legacy single-tenant one. The operator pre-flight in 007's
 * header remains the gate for that case. This guard lives here, not in 007's body, because
 * editing the changeSet would move a recorded checksum on every deployed database.
 */
final class RestampDataGuard {

    /** The changeSet id recorded by Liquibase for 007. */
    static final String RESTAMP_CHANGESET_ID = "007-restamp-default-domain-to-brand";

    /** table name to its full, constant probe — no concatenation anywhere near the driver. */
    private static final Map<String, String> PROBES = Map.of(
            "wiki_page", "SELECT DISTINCT domain FROM wiki_page WHERE domain <> ? AND domain <> ?",
            "wiki_attachment",
            "SELECT DISTINCT domain FROM wiki_attachment WHERE domain <> ? AND domain <> ?",
            "wiki_audit_log",
            "SELECT DISTINCT domain FROM wiki_audit_log WHERE domain <> ? AND domain <> ?",
            "wiki_access", "SELECT DISTINCT domain FROM wiki_access WHERE domain <> ? AND domain <> ?");

    /**
     * @param refuse whether arming must be refused
     * @param reason operator-facing explanation when {@code refuse}; otherwise {@code null}
     */
    record Verdict(boolean refuse, String reason) {
        static Verdict allow() {
            return new Verdict(false, null);
        }

        static Verdict refuse(final String reason) {
            return new Verdict(true, reason);
        }
    }

    private RestampDataGuard() {
        // static-only
    }

    /**
     * Inspects the database.
     *
     * @param url      JDBC URL; {@code null}/blank means no datasource is configured and
     *                 the guard cannot run (Liquibase cannot either), so it allows
     * @param user     JDBC user; may be {@code null}
     * @param password JDBC password; may be {@code null}
     * @param target   the brand the derivation wants to arm
     * @return the verdict; never {@code null}
     */
    static Verdict check(final String url, final String user, final String password,
            final String target) {
        if (url == null || url.isBlank()) {
            return Verdict.allow();
        }
        Properties props = new Properties();
        if (user != null) {
            props.setProperty("user", user);
        }
        if (password != null) {
            props.setProperty("password", password);
        }
        props.setProperty("loginTimeout", "10");
        Connection conn;
        try {
            conn = DriverManager.getConnection(url, props);
        } catch (SQLException e) {
            return Verdict.allow();
        }
        try (Connection c = conn) {
            if (alreadyExecuted(c)) {
                return Verdict.allow();
            }
            List<String> foreign = new ArrayList<>();
            for (Map.Entry<String, String> probe : new TreeMap<>(PROBES).entrySet()) {
                String table = probe.getKey();
                if (!exists(c, table)) {
                    continue;
                }
                try (PreparedStatement ps = c.prepareStatement(probe.getValue())) {
                    ps.setString(1, DomainRegistry.DEFAULT_DOMAIN);
                    ps.setString(2, target);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            foreign.add(table + ":" + rs.getString(1));
                        }
                    }
                }
            }
            if (foreign.isEmpty()) {
                return Verdict.allow();
            }
            return Verdict.refuse("the database already holds rows in a domain other than '"
                    + DomainRegistry.DEFAULT_DOMAIN + "' and '" + target + "' " + foreign
                    + ", so this is not a first single-brand cut-over (most likely"
                    + " app.tenancy.issuers was narrowed from several pairs to one); its"
                    + " domain='" + DomainRegistry.DEFAULT_DOMAIN + "' rows may be"
                    + " SUPERADMIN-authored and sweeping them is a decision, not a migration."
                    + " Run the pre-flight in 007's header and supply"
                    + " wiki.restamp.target.domain explicitly if the cut-over is intended");
        } catch (SQLException e) {
            return Verdict.refuse("the database could not be inspected to confirm the re-stamp"
                    + " is safe (" + e.getMessage() + "), so it fails closed");
        }
    }

    private static boolean exists(final Connection c, final String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT to_regclass(?) IS NOT NULL");
                ResultSet rs = bind(ps, table)) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    private static boolean alreadyExecuted(final Connection c) throws SQLException {
        if (!exists(c, "databasechangelog")) {
            return false;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT count(*) FROM databasechangelog WHERE id = ?")) {
            ps.setString(1, RESTAMP_CHANGESET_ID);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private static ResultSet bind(final PreparedStatement ps, final String value) throws SQLException {
        ps.setString(1, value);
        return ps.executeQuery();
    }
}
