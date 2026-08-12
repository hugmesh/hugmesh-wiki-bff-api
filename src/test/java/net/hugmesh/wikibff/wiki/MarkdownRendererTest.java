package net.hugmesh.wikibff.wiki;

import org.junit.jupiter.api.Test;

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

    @Test
    void blankRendersEmpty() {
        assertThat(renderer.toHtml(null)).isEmpty();
        assertThat(renderer.toHtml("")).isEmpty();
    }
}
