package net.hugmesh.wikibff.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The deploy runbook is this repo's README, and DECISION 31 (operator, 2026-09-17,
 * hugmesh/hugmesh-generator#347) made one of its paragraphs load-bearing for startup.
 *
 * <h2>The defect this exists to stop coming back</h2>
 *
 * <p>Until 2026-09-17 the README <em>did</em> document the permanent pin — in a blockquote
 * roughly a hundred lines <em>below</em> a code block captioned
 * {@code "# The whole cut-over. One step."} A deployer who read the procedure and stopped
 * there set ONE variable, and met {@code ValidationFailedException} weeks later on a boot
 * whose error names a checksum rather than the config change. The remedy was to move the
 * pin into the procedure itself.
 *
 * <p>That remedy is prose, and prose is exactly what a suite cannot see: nothing else in
 * this repo parses README.md, so a later agent could restore the one-step caption, delete
 * the pin from the procedure, or push the pointer back down the page, and every one of the
 * 249 other tests would stay green. This test is the pin ON THE PIN.
 *
 * <h2>What it deliberately does NOT assert</h2>
 *
 * <p>Not wording, not headings, not length — only the four properties whose loss is the
 * documented regression: the pointer comes first, its link resolves, both variables live
 * in the procedure together, and the procedure never again calls itself one step.
 * Restructuring the README freely is fine; quietly losing the pin is not.
 */
@DisplayName("README cut-over runbook — decision 31's pin stays where a deployer reads it")
class CutOverRunbookReadmeTest {

    private static final String README = "README.md";
    private static final String CUT_OVER_VAR = "APP_TENANCY_ISSUERS=";
    private static final String PIN_VAR = "SPRING_LIQUIBASE_PARAMETERS_WIKI_RESTAMP_TARGET_DOMAIN";

    /**
     * How far apart the two assignments may drift and still count as ONE procedure. The
     * defect being pinned had them ~100 lines apart; the fixed layout has them ~20 apart,
     * with the step-1 boot log quoted between them.
     */
    private static final int SAME_PROCEDURE_LINES = 40;

    private static List<String> readme() throws IOException {
        Path p = Path.of(README);
        assertThat(p)
                .as("the runbook must be readable from the module root (surefire's working"
                        + " directory); if this fails the test is looking in the wrong place,"
                        + " not the README missing")
                .exists();
        return Files.readAllLines(p, StandardCharsets.UTF_8);
    }

    /** GitHub's heading -> anchor slug: lowercase, drop punctuation, spaces become hyphens. */
    private static String slug(final String heading) {
        String text = heading.replaceFirst("^#+\\s*", "").trim().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                out.append(c);
            } else if (c == ' ') {
                out.append('-');
            }
        }
        return out.toString();
    }

    private static int indexOfLineStartingWith(final List<String> lines, final String prefix) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    @DisplayName("the FIRST bullet of '## Current State' is the deploy pointer, naming BOTH variables")
    void theDeployPointerIsTheFirstThingCurrentStateSays() throws IOException {
        List<String> lines = readme();
        int heading = indexOfLineStartingWith(lines, "## Current State");
        assertThat(heading).as("'## Current State' heading").isNotNegative();

        int firstBullet = -1;
        for (int i = heading + 1; i < lines.size(); i++) {
            if (lines.get(i).startsWith("## ")) {
                break;
            }
            if (lines.get(i).startsWith("- ")) {
                firstBullet = i;
                break;
            }
        }
        assertThat(firstBullet).as("first bullet under '## Current State'").isNotNegative();

        StringBuilder bullet = new StringBuilder(lines.get(firstBullet));
        for (int i = firstBullet + 1; i < lines.size() && !lines.get(i).startsWith("- "); i++) {
            bullet.append('\n').append(lines.get(i));
        }

        assertThat(bullet.toString())
                .as("A deployer skims Current State and stops. The pointer at the cut-over"
                        + " runbook has to be the FIRST thing there, and it has to name BOTH"
                        + " variables — burying it lower down reproduces the very placement"
                        + " defect decision 31 was recorded to fix.")
                .contains("APP_TENANCY_ISSUERS")
                .contains(PIN_VAR)
                .containsIgnoringCase("TWO environment variables");
    }

    @Test
    @DisplayName("that pointer's anchor link resolves to a real heading in this README")
    void theRunbookLinkIsNotDead() throws IOException {
        List<String> lines = readme();
        String joined = String.join("\n", lines);

        int link = joined.indexOf("](#cut-over-");
        assertThat(link)
                .as("the Current State pointer links to the cut-over runbook by anchor")
                .isNotNegative();
        String anchor = joined.substring(link + 3, joined.indexOf(')', link));

        List<String> anchors = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith("#")) {
                anchors.add(slug(line));
            }
        }

        assertThat(anchors)
                .as("A dead in-page anchor sends a deployer who clicked 'read the runbook"
                        + " first' to the top of the page instead, which is the same outcome"
                        + " as not linking at all. Anchor computed GitHub-style from the"
                        + " headings actually present.")
                .contains(anchor);
    }

    @Test
    @DisplayName("the cut-over procedure carries BOTH variables, together, not a hundred lines apart")
    void thePinTravelsWithTheProcedure() throws IOException {
        List<String> lines = readme();
        int section = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("### Cut-over:")) {
                section = i;
                break;
            }
        }
        assertThat(section).as("'### Cut-over:' section heading").isNotNegative();

        int cutOver = -1;
        int pin = -1;
        for (int i = section; i < lines.size(); i++) {
            if (lines.get(i).startsWith("## ")) {
                break;
            }
            if (cutOver < 0 && lines.get(i).startsWith(CUT_OVER_VAR)) {
                cutOver = i;
            }
            if (pin < 0 && lines.get(i).startsWith(PIN_VAR + "=")) {
                pin = i;
            }
        }

        assertThat(cutOver)
                .as("the armed-boot assignment must appear as a pasteable line in the"
                        + " cut-over section")
                .isNotNegative();
        assertThat(pin)
                .as("THE REGRESSION: the permanent pin must be a pasteable line inside the"
                        + " cut-over procedure itself. Documenting it only in a blockquote"
                        + " further down is what shipped a one-variable procedure for weeks.")
                .isNotNegative();
        assertThat(pin - cutOver)
                .as("the two assignments must read as one procedure (they were ~100 lines"
                        + " apart when the defect was found)")
                .isPositive()
                .isLessThanOrEqualTo(SAME_PROCEDURE_LINES);
    }

    @Test
    @DisplayName("the procedure never again calls itself a single step")
    void theCutOverIsNeverCaptionedAsOneStep() throws IOException {
        List<String> lines = readme();
        int section = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("### Cut-over:")) {
                section = i;
                break;
            }
        }
        assertThat(section).isNotNegative();

        List<String> offenders = new ArrayList<>();
        for (int i = section; i < lines.size(); i++) {
            if (lines.get(i).startsWith("## ")) {
                break;
            }
            String lower = lines.get(i).toLowerCase(Locale.ROOT);
            if (lower.contains("one step") || lower.contains("one environment variable")) {
                offenders.add((i + 1) + ": " + lines.get(i));
            }
        }

        assertThat(offenders)
                .as("The caption '# The whole cut-over. One step.' is the exact wording that"
                        + " told deployers to set one variable while the second was"
                        + " load-bearing for startup. The cut-over is TWO variables and TWO"
                        + " boots (decision 31, operator 2026-09-17).")
                .isEmpty();
    }

    @Test
    @DisplayName("the runbook still states, plainly, that no real pod has ever booted this")
    void theEvidenceIsStillDescribedAsInferred() throws IOException {
        assertThat(String.join("\n", readme()))
                .as("Decision 31 was confirmed on INFERRED evidence: DerivedTargetRerunTest"
                        + " drives liquibase.update() directly and is deliberately not a"
                        + " @SpringBootTest, so the crashlooping pod has never been observed."
                        + " Deleting that admission makes the runbook read as if it had been."
                        + " When someone finally boots a real pod, REPLACE this note with the"
                        + " real failure text rather than removing it.")
                .containsIgnoringCase("No real pod has ever booted");
    }
}
