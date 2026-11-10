package net.hugmesh.wikibff.db;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import net.hugmesh.commons.testsupport.PostgresTestSupport;
import net.hugmesh.wikibff.WikiBffApplication;
import net.hugmesh.wikibff.config.RestampTargetEnvironmentPostProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the ENVIRONMENT-VARIABLE spelling of the 007 re-stamp pin,
 * {@code SPRING_LIQUIBASE_PARAMETERS_WIKI_RESTAMP_TARGET_DOMAIN}, is honoured at a real
 * application boot — the spelling every operator-facing document tells a Deployment to use
 * after a cut-over (see {@link RestampTargetEnvironmentPostProcessor}'s javadoc and the
 * README), and the one no other test in this repo ever supplied as an environment variable.
 *
 * <h2>Why the existing boot tests do not cover it</h2>
 *
 * <p>{@link RestampParameterBindingBootTest} and {@link ExplicitTargetBeatsDerivedBootTest}
 * set the pin as the DOTTED property through {@code @TestPropertySource}. Two links of the
 * production chain are therefore never exercised:
 * <ol>
 *   <li>Relaxed binding of an UPPER_SNAKE environment key into the
 *       {@code spring.liquibase.parameters} MAP. The map key Liquibase receives has to come
 *       out as {@code wiki.restamp.target.domain}; that conversion is done by the
 *       system-environment mapper, which Boot applies only to a
 *       {@link SystemEnvironmentPropertySource} named {@code systemEnvironment}.</li>
 *   <li>Visibility to {@link RestampTargetEnvironmentPostProcessor}. {@code @TestPropertySource}
 *       attaches its values AFTER environment post-processors run, so those tests only show
 *       the explicit value winning by source ORDER. In production the env var is already in
 *       the Environment when the post-processor runs, and the override is protected by its
 *       explicit early return, not by ordering.</li>
 * </ol>
 *
 * <h2>How the environment variable is supplied (no JVM env mutation)</h2>
 *
 * <p>Each case boots {@link WikiBffApplication} through {@link SpringApplication#run} with a
 * custom {@link StandardEnvironment} handed in via {@link SpringApplication#setEnvironment}
 * BEFORE {@code run()}. Its {@code systemEnvironment} source is REPLACED, in place, by a
 * {@link SystemEnvironmentPropertySource} of the same name holding a copy of the real
 * process environment plus the variables under test. That is exactly the source type, name
 * and position the JVM's own environment occupies, and it exists before any
 * post-processor runs — the shape a Kubernetes {@code env:} entry produces.
 *
 * <p>Each boot gets a FRESH database on {@link PostgresTestSupport}'s singleton container
 * (the 007 checksum embeds the brand, so two brands cannot share one).
 *
 * <h2>What each case asserts</h2>
 *
 * <ul>
 *   <li>007's re-stamp changeset is RECORDED AS EXECUTED in {@code databasechangelog} (a
 *       failed arming precondition is silent: {@code onFail="CONTINUE"}).</li>
 *   <li>Its recorded MD5 equals that of a direct Liquibase run with the expected brand —
 *       proof the substituted SQL carried THAT brand, not merely some brand.</li>
 *   <li>The post-processor logged the value as the EXPLICIT one, and contributed no
 *       {@code wikiDerivedRestampTarget} source.</li>
 * </ul>
 *
 * <p>What this does NOT prove: a real pod boot on a cluster (the Deployment template's
 * {@code env:} entry, the image's entrypoint). That remains the only unproven link of #19.
 */
@ExtendWith(OutputCaptureExtension.class)
class EnvVarRestampPinBootTest extends PostgresTestSupport {

    /** The documented operator spelling. Deliberately a literal, never derived from the dotted name. */
    static final String PIN_ENV = "SPRING_LIQUIBASE_PARAMETERS_WIKI_RESTAMP_TARGET_DOMAIN";

    private static final String ISSUERS_ENV = "APP_TENANCY_ISSUERS";
    private static final String ISSUER_URI_ENV = "CASDOOR_ISSUER_URI";
    private static final String SYSTEM_ENV = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;

    private static final String MASTER = "db/changelog/db.changelog-master.xml";
    private static final String RESTAMP_ID = "007-restamp-default-domain-to-brand";
    private static final String DERIVED_SOURCE = "wikiDerivedRestampTarget";

    /** Test-only profile whose config file pins the target to {@link #YML_BRAND}. */
    private static final String YML_PIN_PROFILE = "envpin-yml";
    private static final String YML_BRAND = "brand-c";

    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    // ── cases ─────────────────────────────────────────────────────────────────

    @Test
    void theEnvVarPinAloneArmsTheRestampAndIsSeenAsExplicit(CapturedOutput output) throws Exception {
        String db = freshDatabase();
        try (ConfigurableApplicationContext ctx = boot(db, Map.of(PIN_ENV, "brand-a"))) {
            ConfigurableEnvironment env = ctx.getEnvironment();

            assertThat(env.getPropertySources().get(SYSTEM_ENV))
                    .as("the pin must arrive through the real systemEnvironment source shape")
                    .isInstanceOf(SystemEnvironmentPropertySource.class);
            assertThat(env.getProperty(RestampTargetEnvironmentPostProcessor.TARGET_PROPERTY))
                    .isEqualTo("brand-a");

            assertThat(executed(db, RESTAMP_ID))
                    .as(PIN_ENV + " must reach 007's arming precondition: if relaxed binding drops"
                            + " it from the spring.liquibase.parameters map, the re-stamp is"
                            + " silently PENDING on every pinned Deployment")
                    .isEqualTo(1);
            assertThat(recordedMd5(db))
                    .as("the SQL Liquibase checksummed must have had 'brand-a' substituted in")
                    .isEqualTo(referenceMd5("brand-a"));

            assertThat(output.getAll())
                    .as("the post-processor must have SEEN the env var as the explicit target")
                    .contains("re-stamp target explicitly set to 'brand-a'");
            assertThat(sourceNames(env)).doesNotContain(DERIVED_SOURCE);
        }
    }

    @Test
    void theEnvVarPinBeatsADifferentBrandDerivedFromAppTenancyIssuers(CapturedOutput output)
            throws Exception {
        String db = freshDatabase();
        try (ConfigurableApplicationContext ctx = boot(db, Map.of(
                ISSUER_URI_ENV, "https://id.brand-b.test",
                ISSUERS_ENV, "https://id.brand-b.test=brand-b",
                PIN_ENV, "brand-a"))) {
            ConfigurableEnvironment env = ctx.getEnvironment();

            assertThat(env.getProperty(RestampTargetEnvironmentPostProcessor.TARGET_PROPERTY))
                    .as("documented precedence: an explicit pin always beats the derived brand")
                    .isEqualTo("brand-a");
            assertThat(sourceNames(env))
                    .as("the override must be honoured by the post-processor's explicit check,"
                            + " not by winning an ordering race against a derived contribution")
                    .doesNotContain(DERIVED_SOURCE);
            assertThat(output.getAll())
                    .contains("re-stamp target explicitly set to 'brand-a'")
                    .contains("the derived launch domain is 'brand-b'");

            assertThat(executed(db, RESTAMP_ID)).isEqualTo(1);
            assertThat(recordedMd5(db))
                    .as("Liquibase must have re-stamped to the PINNED brand, not the derived one")
                    .isEqualTo(referenceMd5("brand-a"))
                    .isNotEqualTo(referenceMd5("brand-b"));
        }
    }

    /**
     * Control for the case above: the very same issuer env WITHOUT the pin does arm, to
     * 'brand-b'. Without this, "the pin wins" could pass vacuously on a derivation that
     * never armed at all.
     */
    @Test
    void controlTheSameIssuersEnvWithoutThePinDerivesTheOtherBrand(CapturedOutput output)
            throws Exception {
        String db = freshDatabase();
        try (ConfigurableApplicationContext ctx = boot(db, Map.of(
                ISSUER_URI_ENV, "https://id.brand-b.test",
                ISSUERS_ENV, "https://id.brand-b.test=brand-b"))) {
            ConfigurableEnvironment env = ctx.getEnvironment();

            assertThat(env.getProperty(RestampTargetEnvironmentPostProcessor.TARGET_PROPERTY))
                    .isEqualTo("brand-b");
            assertThat(sourceNames(env)).contains(DERIVED_SOURCE);
            assertThat(output.getAll()).contains("launch domain derived as 'brand-b'");
            assertThat(executed(db, RESTAMP_ID)).isEqualTo(1);
            assertThat(recordedMd5(db)).isEqualTo(referenceMd5("brand-b"));
        }
    }

    /**
     * An env var must outrank a value baked into the image's config files (the
     * {@code application.yml} tier) — that is how a Deployment overrides a packaged default.
     * The config value is supplied by a test-only profile file, so production config is
     * untouched.
     */
    @Test
    void theEnvVarPinOutranksAConfigFileValue(CapturedOutput output) throws Exception {
        String db = freshDatabase();
        try (ConfigurableApplicationContext ctx =
                     boot(db, Map.of(PIN_ENV, "brand-a"), YML_PIN_PROFILE)) {
            ConfigurableEnvironment env = ctx.getEnvironment();

            assertThat(configFileValue(env))
                    .as("precondition: the test profile's config file really does set '"
                            + YML_BRAND + "'")
                    .isEqualTo(YML_BRAND);
            assertThat(env.getProperty(RestampTargetEnvironmentPostProcessor.TARGET_PROPERTY))
                    .as("an environment variable must outrank application.yml")
                    .isEqualTo("brand-a");
            assertThat(output.getAll()).contains("re-stamp target explicitly set to 'brand-a'");
            assertThat(executed(db, RESTAMP_ID)).isEqualTo(1);
            assertThat(recordedMd5(db)).isEqualTo(referenceMd5("brand-a"));
        }
    }

    // ── harness ───────────────────────────────────────────────────────────────

    /**
     * Boots the real application with {@code envVars} present in a genuine
     * {@code systemEnvironment} source before any environment post-processor runs.
     */
    private static ConfigurableApplicationContext boot(
            final String jdbcUrl, final Map<String, String> envVars, final String... extraProfiles) {
        Map<String, Object> processEnv = new HashMap<>(System.getenv());
        // Hermetic: a developer shell exporting any of these must not leak into the case.
        processEnv.keySet().removeAll(List.of(PIN_ENV, ISSUERS_ENV, ISSUER_URI_ENV));
        processEnv.putAll(envVars);

        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(SYSTEM_ENV,
                new SystemEnvironmentPropertySource(SYSTEM_ENV, processEnv));

        SpringApplication app = new SpringApplication(WikiBffApplication.class);
        app.setEnvironment(environment);
        String[] profiles = new String[extraProfiles.length + 1];
        profiles[0] = "test";
        System.arraycopy(extraProfiles, 0, profiles, 1, extraProfiles.length);
        app.setAdditionalProfiles(profiles);

        String r2dbcUrl = jdbcUrl.replaceFirst("^jdbc:", "r2dbc:");
        return app.run(
                "--server.port=0",
                "--spring.r2dbc.url=" + r2dbcUrl,
                "--spring.r2dbc.username=" + POSTGRES.getUsername(),
                "--spring.r2dbc.password=" + POSTGRES.getPassword(),
                "--spring.liquibase.url=" + jdbcUrl,
                "--spring.liquibase.user=" + POSTGRES.getUsername(),
                "--spring.liquibase.password=" + POSTGRES.getPassword(),
                // What PostgresTestSupport's @DynamicPropertySource publishes for @SpringBootTest.
                "--spring.liquibase.parameters.json.type=text");
    }

    private static List<String> sourceNames(final ConfigurableEnvironment env) {
        return env.getPropertySources().stream().map(PropertySource::getName).toList();
    }

    /** The target as set by the test profile's config file, read from that source alone. */
    private static Object configFileValue(final ConfigurableEnvironment env) {
        return env.getPropertySources().stream()
                .filter(s -> s.getName().contains("application-" + YML_PIN_PROFILE))
                .map(s -> s.getProperty(RestampTargetEnvironmentPostProcessor.TARGET_PROPERTY))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static String freshDatabase() throws Exception {
        String name = "envpin_" + DB_SEQ.incrementAndGet();
        try (Connection c = DriverManager.getConnection(jdbc(POSTGRES.getDatabaseName()),
                POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.executeUpdate("CREATE DATABASE \"" + name + "\"");
        }
        return jdbc(name);
    }

    private static String jdbc(final String database) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
                + "/" + database;
    }

    private static int executed(final String url, final String changesetId) throws Exception {
        try (Connection c = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM databasechangelog WHERE id = '"
                     + changesetId + "'")) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    private static String recordedMd5(final String url) throws Exception {
        try (Connection c = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT md5sum FROM databasechangelog WHERE id = '"
                     + RESTAMP_ID + "'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** 007's checksum from a direct Liquibase run on a fresh database with {@code brand} as target. */
    private static String referenceMd5(final String brand) throws Exception {
        String url = freshDatabase();
        try (Connection c = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword())) {
            Database db = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(c));
            try (Liquibase lb = new Liquibase(MASTER, new ClassLoaderResourceAccessor(), db)) {
                lb.setChangeLogParameter("json.type", "text");
                lb.setChangeLogParameter("wiki.restamp.target.domain", brand);
                lb.update(new Contexts(), new LabelExpression());
            }
        }
        String md5 = recordedMd5(url);
        assertThat(md5).as("reference run for '" + brand + "' must record 007").isNotNull();
        return md5;
    }
}
