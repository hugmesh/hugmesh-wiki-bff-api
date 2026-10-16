package net.hugmesh.wikibff.wiki;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.DefaultUrlSanitizer;
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
 * <p><b>Raw HTML and URLs are neutralised at render time.</b> Two separate holes are
 * closed, and neither is closed by the other:
 * <ul>
 *   <li>Raw HTML: the CommonMark {@link HtmlRenderer} runs with {@code escapeHtml(true)},
 *       so any {@code <script>}/inline HTML an author embeds is escaped rather than
 *       emitted live.</li>
 *   <li>Link and image destinations: {@code escapeHtml} does not touch them, so
 *       {@code [x](javascript:alert(1))} would otherwise become a live
 *       {@code <a href="javascript:...">}. The renderer therefore runs with
 *       {@code sanitizeUrls(true)} and an explicit {@link DefaultUrlSanitizer} that
 *       allows only the {@code http}, {@code https} and {@code mailto} schemes; any
 *       other scheme ({@code javascript:}, {@code vbscript:}, {@code data:},
 *       {@code file:}) has its destination dropped to an empty string. Scheme-less
 *       (relative / fragment) destinations are kept.</li>
 * </ul>
 * With both in place the stored {@code content_html} carries no script-bearing markup or
 * URL for the schemes covered by {@code MarkdownRendererTest}; consumers should still
 * treat it as untrusted-by-default and keep their own sanitiser as defence in depth.
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
     * escaped ({@code escapeHtml(true)}) rather than passed through, and link/image
     * destinations are restricted to {@code http}/{@code https}/{@code mailto} (plus
     * scheme-less relative references) via {@code sanitizeUrls(true)}.
     */
    public MarkdownRenderer() {
        List<Extension> extensions = List.of(TablesExtension.create());
        this.parser = Parser.builder().extensions(extensions).build();
        // escapeHtml(true): raw inline HTML in the source is ESCAPED, not emitted (no
        // <script> passthrough). sanitizeUrls(true) + an EXPLICIT allow-list: escapeHtml
        // does not cover link/image destinations, and the allow-list is spelled out here
        // so a library default change cannot silently widen it.
        this.renderer = HtmlRenderer.builder()
                .extensions(extensions)
                .escapeHtml(true)
                .sanitizeUrls(true)
                .urlSanitizer(new DefaultUrlSanitizer(List.of("http", "https", "mailto")))
                .build();
    }

    /**
     * Render markdown to HTML with raw HTML escaped and link/image schemes restricted. A null/blank source renders to the empty string.
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
