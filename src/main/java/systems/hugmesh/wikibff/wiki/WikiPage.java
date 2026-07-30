package systems.hugmesh.wikibff.wiki;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * One row of the {@code wiki_page} content store (Liquibase {@code 001}) — TIER 1
 * (WRITE). Admins author markdown; the BFF renders it to HTML at WRITE time and stores
 * BOTH: {@code contentMarkdown} is the edit source of truth and {@code contentHtml} is
 * the rendered snapshot the read path / ZIM (re)build (PR4) serves.
 *
 * <p>The {@code domain} is the brand/tenant the page belongs to, stamped SERVER-SIDE
 * from the author's validated-issuer brand (NEVER a request field). A scoped ADMIN of
 * brand X reads only brand X's pages; SUPERADMIN reads every brand's. The
 * {@code (domain, slug)} pair is UNIQUE — the same slug may exist independently per
 * brand.
 *
 * <p>{@code authorSubject} is the immutable Casdoor {@code sub} of the last author
 * (owner track), set from the JWT — never the request body — so a caller cannot author
 * under another subject. Seeded platform docs carry {@code @system}. {@code version} is
 * bumped on every write and drives optimistic-concurrency (a stale edit is a 409).
 *
 * @param id              DB-generated UUID — PRIMARY KEY (changelog 006)
 * @param domain          the brand/tenant the page belongs to (server-derived; {@code default} single-tenant)
 * @param slug            the URL-stable page key within a brand
 * @param title           the page title
 * @param contentMarkdown the markdown edit source of truth
 * @param contentHtml     the rendered HTML snapshot (the ZIM (re)build source)
 * @param authorSubject   the immutable Casdoor sub of the last author (owner track)
 * @param version         the optimistic-concurrency / history counter (bumped per write)
 * @param createdAt       first-created timestamp
 * @param updatedAt       last-written timestamp
 * @param published       whether the page has been explicitly published (changeSet 005)
 * @param publishedAt     the timestamp of the last publish action; null = never published (changeSet 005)
 * @see WikiPageRepository
 * @see WikiService
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Table("wiki_page")
public record WikiPage(
        @Id
        @Column("id")
        String id,
        @Column("domain")
        String domain,
        @Column("slug")
        String slug,
        @Column("title")
        String title,
        @Column("content_markdown")
        String contentMarkdown,
        @Column("content_html")
        String contentHtml,
        @Column("author_subject")
        String authorSubject,
        @Column("version")
        long version,
        @Column("created_at")
        Instant createdAt,
        @Column("updated_at")
        Instant updatedAt,
        @Column("is_published")
        boolean published,
        @Column("published_at")
        Instant publishedAt) {
}
