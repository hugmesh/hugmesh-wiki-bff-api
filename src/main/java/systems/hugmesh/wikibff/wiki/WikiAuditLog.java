package systems.hugmesh.wikibff.wiki;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * One row of the {@code wiki_audit_log} table (Liquibase {@code 003}) — the wiki-bff's
 * OWN content-edit audit trail. Every wiki write/publish action ({@code wiki.page.create}
 * / {@code wiki.page.update} / {@code wiki.page.publish}) is recorded here so the full
 * history of who edited what (and when) is auditable.
 *
 * <p>The {@code domain} is the brand/tenant the action belongs to, stamped SERVER-SIDE
 * from the actor's validated-issuer brand. A scoped ADMIN reads only own-brand audit;
 * SUPERADMIN reads every brand's.
 *
 * @param id            DB-generated UUID — PRIMARY KEY (changelog 006)
 * @param domain        the brand/tenant the action belongs to (server-derived)
 * @param actorSubject  the acting principal's Casdoor sub
 * @param action        the action verb (e.g. {@code wiki.page.create})
 * @param target        the target (page id / slug)
 * @param detail        free-text detail (nullable; e.g. the new version)
 * @param createdAt     when the action occurred
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Table("wiki_audit_log")
public record WikiAuditLog(
        @Id
        @Column("id")
        String id,
        @Column("domain")
        String domain,
        @Column("actor_subject")
        String actorSubject,
        @Column("action")
        String action,
        @Column("target")
        String target,
        @Column("detail")
        String detail,
        @Column("created_at")
        Instant createdAt) {
}
