package net.hugmesh.wikibff.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The README says which Maven phase CI runs, and therefore whether PMD, Checkstyle and
 * SpotBugs gate the build. That sentence went stale once already: CI moved to {@code
 * ./mvnw verify} (PR #10, issue #1) while the README kept saying CI only ran {@code test}
 * and that none of the analyzers gated it.
 *
 * <p>This pins the README to the CI file it describes. It reads {@code .gitlab-ci.yml}, finds
 * the Maven command the {@code test} job really runs, and fails if the README claims a
 * different one or revives the retired "none of the three gate CI" wording.
 *
 * <h2>What it deliberately does NOT assert</h2>
 *
 * <p>It does not pin the analyzers' finding counts: those are a property of the code, not of
 * the CI file, and the build itself fails when they change.
 */
@DisplayName("README CI gate — the documented Maven phase is the one .gitlab-ci.yml runs")
class CiGateReadmeTest {

    private static final Pattern MVN_LINE = Pattern.compile("\\./mvnw\\b.*\\b(verify|test)\\b");

    private static String read(final String name) throws IOException {
        Path p = Path.of(name);
        assertThat(p)
                .as(name + " must be readable from the module root (surefire's working"
                        + " directory)")
                .exists();
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** The Maven phase the CI {@code test} job runs, ignoring comment lines. */
    private static String ciPhase() throws IOException {
        List<String> lines = read(".gitlab-ci.yml").lines().toList();
        String phase = null;
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("#")) {
                continue;
            }
            var m = MVN_LINE.matcher(t);
            if (m.find()) {
                assertThat(phase)
                        .as("the CI file must run exactly one Maven phase command, saw a second: "
                                + t)
                        .isNull();
                phase = m.group(1);
            }
        }
        assertThat(phase).as("a ./mvnw test|verify command in .gitlab-ci.yml").isNotNull();
        return phase;
    }

    @Test
    @DisplayName("CI runs verify, the phase PMD, Checkstyle and SpotBugs bind to")
    void ciRunsVerify() throws IOException {
        assertThat(ciPhase())
                .as("if CI stops running verify the analyzers stop gating it, and the README"
                        + " paragraphs this test guards must be revisited")
                .isEqualTo("verify");
    }

    @Test
    @DisplayName("the README names that same phase for the CI test job and its diagram")
    void readmeNamesTheCiPhase() throws IOException {
        String readme = read("README.md");
        String command = "./mvnw -B -s settings.xml " + ciPhase();
        assertThat(readme.split(Pattern.quote(command), -1).length - 1)
                .as("the README must show the CI command '" + command + "' in both the"
                        + " pipeline diagram and the job bullet")
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("the README no longer says the analyzers do not gate CI")
    void readmeDoesNotClaimTheAnalyzersAreUngated() throws IOException {
        String flat = read("README.md").replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
        assertThat(flat)
                .doesNotContain("none of the three currently gate")
                .doesNotContain("so none of the three run in ci today")
                .doesNotContain("only runs `./mvnw test`");
    }
}
