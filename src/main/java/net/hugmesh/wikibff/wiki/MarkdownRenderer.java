package net.hugmesh.wikibff.wiki;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Renders authored markdown to the stored HTML snapshot at WRITE time (TIER 1). The
 * wiki authors markdown ({@code content_markdown} = the edit source of truth); this
 * transform produces {@code content_html} = the rendered snapshot the read path and the
 * ZIM (re)build (PR4) serve, so rendering happens ONCE per write and the read path
 * (kiwix-serve) carries no render step.
 *
 * <p><b>Safe by default.</b> The CommonMark {@link HtmlRenderer} is left with raw-HTML
 * ESCAPING ON ({@code escapeHtml(true)} / no {@code sanitizeUrls(false)}), so any raw
 * {@code <script>}/inline HTML an author embeds is escaped rather than emitted live —
 * the stored HTML is XSS-free for the read path / ZIM without an extra sanitizer pass.
 * GitHub-flavoured tables are enabled (the seeded platform docs use them).
 */
@Component
public class MarkdownRenderer {

    private final Parser parser;
    private final HtmlRenderer renderer;

    /**
     * Builds the CommonMark parser and HTML renderer with GitHub-Flavoured Markdown
     * (GFM) tables enabled and HTML escaping turned on. The GFM {@link TablesExtension}
     * is registered on both the parser and the renderer so pipe-table syntax in source
     * markdown is emitted as {@code <table>} elements. Raw inline HTML in the source is
     * escaped ({@code escapeHtml(true)}) rather than passed through, so the produced
     * {@code content_html} is safe to serve without an additional sanitiser pass.
     */
    public MarkdownRenderer() {
        List<Extension> extensions = List.of(TablesExtension.create());
        this.parser = Parser.builder().extensions(extensions).build();
        // escapeHtml(true): raw inline HTML in the source is ESCAPED, not emitted — the
        // stored content_html is safe to serve directly (no <script> passthrough).
        this.renderer = HtmlRenderer.builder().extensions(extensions).escapeHtml(true).build();
    }

    /**
     * Render markdown to safe HTML. A null/blank source renders to the empty string.
     *
     * @param markdown the CommonMark (GFM) markdown source to render; may be
     *                 {@code null} or empty
     * @return the rendered HTML string, or {@code ""} if {@code markdown} is
     *         {@code null} or empty
     */
    public String toHtml(String markdown) {
        if (markdown == null || markdown.isEmpty()) return "";
        Node document = parser.parse(markdown);
        return renderer.render(document);
    }
}
