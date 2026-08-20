package net.hugmesh.wikibff.wiki;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * The JSON shapes crossing the {@code /bff/wiki/**} HTTP boundary. Plain records
 * ({@code @JsonIgnoreProperties(ignoreUnknown)} so an evolving UI never breaks
 * deserialization). The request bodies carry jakarta validation; the
 * {@code authorSubject} / {@code domain} are NEVER accepted from the request body — the
 * service sets them from the validated JWT (author == Casdoor sub; domain == validated
 * issuer) so a caller cannot author under another user or forge a cross-tenant scope.
 */
public final class WikiDto {

    private WikiDto() {}

    /**
     * A page summary (the list view — omits the heavy content columns
     * {@code contentMarkdown} and {@code contentHtml}).
     *
     * @param id            the DB-generated UUID primary key (changelog 006)
     * @param slug          the URL-stable page key within the brand
     * @param title         the page title
     * @param authorSubject the Casdoor {@code sub} of the last author (server-derived)
     * @param version       the optimistic-concurrency counter (bumped on every write)
     * @param updatedAt     the timestamp of the most recent write
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PageSummary(String id, String slug, String title, String authorSubject,
                              long version, Instant updatedAt) {
        /**
         * Construct a {@link PageSummary} from a {@link WikiPage} row (omitting content columns).
         *
         * @param p the source {@link WikiPage}
         * @return a {@link PageSummary} populated from {@code p}
         */
        public static PageSummary of(WikiPage p) {
            return new PageSummary(p.id(), p.slug(), p.title(), p.authorSubject(),
                    p.version(), p.updatedAt());
        }
    }

    /**
     * The full page (the get view — markdown source + rendered HTML snapshot).
     *
     * @param id              the DB-generated UUID primary key (changelog 006)
     * @param domain          the brand/tenant the page belongs to (server-derived; never
     *                        accepted from the request body)
     * @param slug            the URL-stable page key within the brand
     * @param title           the page title
     * @param contentMarkdown the markdown edit source of truth (what authors write)
     * @param contentHtml     the rendered HTML snapshot (computed at write time; what the
     *                        read path and ZIM (re)build serve)
     * @param authorSubject   the Casdoor {@code sub} of the last author (server-derived;
     *                        re-stamped on every update)
     * @param version         the optimistic-concurrency counter (bumped on every write)
     * @param createdAt       the timestamp of the first create
     * @param updatedAt       the timestamp of the most recent write
     * @param published       whether the page has been explicitly published via
     *                        {@code POST /admin/pages/{slug}/publish} (Liquibase changeSet 005)
     * @param publishedAt     the timestamp of the last publish action; {@code null} when the
     *                        page has never been published (Liquibase changeSet 005)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Page(String id, String domain, String slug, String title,
                       String contentMarkdown, String contentHtml, String authorSubject,
                       long version, Instant createdAt, Instant updatedAt,
                       boolean published, Instant publishedAt) {
        /**
         * Construct a {@link Page} from a {@link WikiPage} row (all fields).
         *
         * @param p the source {@link WikiPage}
         * @return a {@link Page} populated from {@code p}
         */
        public static Page of(WikiPage p) {
            return new Page(p.id(), p.domain(), p.slug(), p.title(), p.contentMarkdown(),
                    p.contentHtml(), p.authorSubject(), p.version(), p.createdAt(), p.updatedAt(),
                    p.published(), p.publishedAt());
        }
    }

    /**
     * The page-list response wrapper.
     *
     * @param pages the ordered list of {@link PageSummary} records for the caller's brand
     *              (newest-updated first)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PageList(List<PageSummary> pages) {
        /**
         * Defensive copy on the way IN. The accessor below already copies on the way OUT,
         * but a record's canonical constructor still stores the CALLER's list by reference
         * — a caller that kept and mutated it would mutate this record (EI_EXPOSE_REP2).
         * Copying here makes the field immutable at rest (which in turn makes the
         * accessor's {@code copyOf} a no-op returning the same instance).
         */
        public PageList {
            pages = List.copyOf(pages);
        }

        @Override
        public List<PageSummary> pages() {
            return List.copyOf(pages);
        }
    }

    // ── request bodies ─────────────────────────────────────────────────

    /** Slug constraint — lowercase URL-safe (the ZIM/Kiwix path key). */
    public static final String SLUG_PATTERN = "^[a-z0-9]+(?:-[a-z0-9]+)*$";

    /**
     * {@code POST /bff/wiki/admin/pages}. The author + domain are NEVER read from the
     * body — the service stamps them from the JWT.
     *
     * @param slug            the URL-stable page key within the brand; must match
     *                        {@link #SLUG_PATTERN} (lowercase, hyphen-separated, URL-safe)
     *                        and be unique within the caller's brand
     * @param title           the page title; required, max 512 characters
     * @param contentMarkdown the markdown source; required, max 1 MiB; rendered to HTML
     *                        at write time by the service
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreatePage(
            @NotBlank(message = "slug is required")
            @Size(max = 512, message = "slug is too long")
            @Pattern(regexp = SLUG_PATTERN, message = "slug must be lowercase, hyphen-separated, url-safe")
            String slug,
            @NotBlank(message = "title is required") @Size(max = 512, message = "title is too long")
            String title,
            @NotBlank(message = "content is required") @Size(max = 1_048_576, message = "content is too long")
            String contentMarkdown) {}

    /**
     * {@code PUT /bff/wiki/admin/pages/{slug}}. {@code expectedVersion} drives the
     * optimistic-concurrency check — a write whose {@code expectedVersion} does not match
     * the stored {@code version} is a 409 (a stale edit, e.g. a concurrent overwrite).
     *
     * @param title           the new page title; required, max 512 characters
     * @param contentMarkdown the new markdown source; required, max 1 MiB; rendered to
     *                        HTML at write time by the service
     * @param expectedVersion the page version the caller last read; must equal the stored
     *                        version or the update is rejected with 409 (stale edit)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpdatePage(
            @NotBlank(message = "title is required") @Size(max = 512, message = "title is too long")
            String title,
            @NotBlank(message = "content is required") @Size(max = 1_048_576, message = "content is too long")
            String contentMarkdown,
            long expectedVersion) {}
}
