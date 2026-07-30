package systems.hugmesh.wikibff.wiki;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * One row of the {@code wiki_attachment} table (Liquibase {@code 002}) — a per-page
 * binary attachment (image / file) stored inline so the content store is a single
 * source of truth the ZIM (re)build (PR4) packages alongside the page HTML (no separate
 * object-store dependency on the default-on Pi path).
 *
 * <p>{@code pageId} is the FK to {@link WikiPage#id()} (ON DELETE CASCADE). The
 * {@code domain} is denormalised from the owning page so an attachment read is
 * domain-scopable without a join.
 *
 * <p><b>BYTEA size concern.</b> The {@code data} field stores raw bytes inline in
 * Postgres as a {@code BYTEA} column and is loaded fully into a {@code byte[]} on the
 * JVM. This means every download or list-with-data call holds the full attachment in
 * heap. Practical limits: Postgres {@code BYTEA} supports up to 1 GB per row, but large
 * attachments (more than a few MB) will stress both JVM heap and the R2DBC wire transfer.
 * The current design is appropriate for the default-on Pi path where attachments are
 * typically small images or documents. For production-scale deployments with large
 * binary assets, an external object-store tier (e.g. S3-compatible store with pre-signed
 * URLs) would be needed to avoid heap pressure and long wire transfers.
 *
 * @param id        DB-generated UUID — PRIMARY KEY (changelog 006)
 * @param pageId    FK to {@link WikiPage#id()}
 * @param domain    the brand/tenant (= the owning page's domain)
 * @param filename  the display/download name
 * @param mimeType  the content type served on download
 * @param data      the raw bytes (BYTEA); see size concern note on the class Javadoc
 * @param createdAt creation timestamp
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Table("wiki_attachment")
public record WikiAttachment(
        @Id
        @Column("id")
        String id,
        @Column("page_id")
        String pageId,
        @Column("domain")
        String domain,
        @Column("filename")
        String filename,
        @Column("mime_type")
        String mimeType,
        @Column("data")
        byte[] data,
        @Column("created_at")
        Instant createdAt) {

    @Override
    public byte[] data() {
        return data.clone();
    }
}
