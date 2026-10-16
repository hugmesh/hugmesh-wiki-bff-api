package net.hugmesh.wikibff.wiki;

import org.junit.jupiter.api.Test;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * USER-SITUATIONAL unit test for the write-time markdown->HTML transform. Proves the
 * render produces HTML (the stored content_html the read path / ZIM serves), renders
 * GitHub-flavoured tables (the seeded platform docs use them), and is SAFE by default —
 * raw inline HTML / {@code <script>} is ESCAPED, not emitted live, so the stored HTML is
 * XSS-free for the read path without a separate sanitizer pass.
 */
class MarkdownRendererTest {

    private final MarkdownRenderer renderer = new MarkdownRenderer();

    @Test
    void rendersHeadingAndParagraph() {
        String html = renderer.toHtml("# Title\n\nhello **world**");
        assertThat(html).contains("<h1>Title</h1>");
        assertThat(html).contains("<strong>world</strong>");
    }

    @Test
    void rendersGfmTable() {
        String md = "| a | b |\n| - | - |\n| 1 | 2 |";
        String html = renderer.toHtml(md);
        assertThat(html).contains("<table>");
        assertThat(html).contains("<td>1</td>");
    }

    @Test
    void escapesRawHtml_noScriptPassthrough() {
        String html = renderer.toHtml("hi <script>alert(1)</script>");
        // The raw <script> is ESCAPED (&lt;script&gt;), never emitted as a live tag.
        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
    }

    /** Link destinations in every spelling that must never reach an href/src attribute. */
    @ParameterizedTest
    @ValueSource(strings = {
            "[x](javascript:alert(1))",
            "[x](JaVaScRiPt:alert(1))",
            "[x](vbscript:msgbox(1))",
            "[x](data:text/html;base64,AAA)",
            "[x](&#106;avascript:alert(1))",
            "[x](&#x6A;avascript:alert(1))",
            "[x](<javascript:alert(1)>)",
            "[x](file:///etc/passwd)",
            "[x][r]\n\n[r]: javascript:alert(1)",
            "<javascript:alert(1)>",
            "[x](javascript:alert(1) \"title\")",
    })
    void dangerousLinkSchemesNeverReachHref(String markdown) {
        String html = renderer.toHtml(markdown);
        assertThat(html.toLowerCase()).doesNotContain("href=\"javascript:")
                .doesNotContain("href=\"vbscript:")
                .doesNotContain("href=\"data:")
                .doesNotContain("href=\"file:");
        assertThat(html).doesNotContainPattern("(?i)href=\"\\s*(java|vb)");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "![i](javascript:alert(1))",
            "![i](data:text/html;base64,AAA)",
            "![i](data:image/svg+xml;base64,AAA)",
            "![i](vbscript:msgbox(1))",
            "![i](JAVASCRIPT:alert(1))",
            "![i](&#106;avascript:alert(1))",
            "![i][r]\n\n[r]: data:text/html;base64,AAA",
    })
    void dangerousImageSchemesNeverReachSrc(String markdown) {
        String html = renderer.toHtml(markdown);
        assertThat(html.toLowerCase()).doesNotContain("src=\"javascript:")
                .doesNotContain("src=\"vbscript:")
                .doesNotContain("src=\"data:");
        assertThat(html).doesNotContainPattern("(?i)src=\"\\s*(java|vb)");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "[x](https://example.org/a?b=1) | https://example.org/a?b=1",
            "[x](http://example.org/a)      | http://example.org/a",
            "[x](mailto:a@example.org)      | mailto:a@example.org",
            "[x](/wiki/page)                | /wiki/page",
            "[x](#anchor)                   | #anchor",
            "[x](../other)                  | ../other",
    })
    void ordinaryLinksSurvive(String markdown, String href) {
        assertThat(renderer.toHtml(markdown)).contains("href=\"" + href + "\"").contains(">x</a>");
    }

    @Test
    void ordinaryImageSurvives() {
        assertThat(renderer.toHtml("![i](https://example.org/a.png)"))
                .contains("<img src=\"https://example.org/a.png\"");
    }

    @Test
    void reportedExploitInputRendersWithEmptyDestinations() {
        String html = renderer.toHtml(
                "[x](javascript:alert(1)) ![i](data:text/html;base64,AAA)");
        assertThat(html).isEqualTo(
                "<p><a rel=\"nofollow\" href=\"\">x</a> <img src=\"\" alt=\"i\" /></p>\n");
    }

    @Test
    void blankRendersEmpty() {
        assertThat(renderer.toHtml(null)).isEmpty();
        assertThat(renderer.toHtml("")).isEmpty();
    }
}
