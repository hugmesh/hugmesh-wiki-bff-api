package net.hugmesh.wikibff.access;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * A single {@code wiki_access} row — the desired-state record of a per-principal wiki
 * access OVERRIDE that sits on TOP of the role cascade (schema in Liquibase
 * {@code db/changelog/004-wiki-access.xml}). CLONED in shape from the admin-bff
 * {@code AccessSuspension}.
 *
 * <p>The overlay is the source of truth for the grant / timeout / ban model:
 * <ul>
 *   <li>{@code accessType} — {@link #TYPE_READ} (a GRANT giving a USER read they would
 *       not have by role) or {@link #TYPE_BANNED} (a BAN/TIMEOUT denying read);
 *       CHECK-constrained in the DB.</li>
 *   <li>{@code state} — {@link #STATE_ACTIVE} while the override is in force; the
 *       service CASes it to {@link #STATE_REVOKED} on restore/revoke (the row is NEVER
 *       deleted — it is the audit trail).</li>
 *   <li>{@code expiresAt} — {@code null} means PERMANENT (a permanent grant, or a
 *       permanent ban); a FUTURE instant means a TIMEOUT that auto-restores when the
 *       clock passes WITHOUT a write ({@link #isEffectiveAt} treats {@code expiresAt <=
 *       now} as no-longer-active).</li>
 *   <li>{@code domain} — the brand/tenant the override belongs to, stamped SERVER-SIDE
 *       from the validated issuer. A scoped ADMIN acts only within their own brand;
 *       SUPERADMIN acts cross-domain.</li>
 * </ul>
 *
 * @param id              surrogate UUID primary key, DB-generated (changelog 006)
 * @param domain          brand/tenant the override belongs to; stamped SERVER-SIDE from
 *                        the validated JWT issuer, never a caller-supplied field
 * @param userSubject     Casdoor subject of the principal whose access is being overridden
 * @param accessType      {@link #TYPE_READ} (grant) or {@link #TYPE_BANNED} (ban/timeout);
 *                        CHECK-constrained in the DB
 * @param state           {@link #STATE_ACTIVE} while in force; CAS'd to
 *                        {@link #STATE_REVOKED} on restore/revoke — never deleted
 * @param grantedBySubject Casdoor subject of the admin who issued the override
 * @param reason          optional free-text audit reason supplied by the admin;
 *                        {@code null} when no reason was given
 * @param createdAt       wall-clock instant the row was inserted (set to {@code now()} in SQL)
 * @param expiresAt       {@code null} for a PERMANENT override; a future instant for a
 *                        TIMEOUT (auto-restores when the clock passes without a write)
 * @param revokedAt       {@code null} while active; the instant the CAS
 *                        {@code active -> revoked} was applied
 */
@Table("wiki_access")
public record WikiAccess(
        @Id
        @Column("id")
        String id,
        @Column("domain")
        String domain,
        @Column("user_subject")
        String userSubject,
        @Column("access_type")
        String accessType,
        @Column("state")
        String state,
        @Column("granted_by_subject")
        String grantedBySubject,
        @Column("reason")
        String reason,
        @Column("created_at")
        Instant createdAt,
        @Column("expires_at")
        Instant expiresAt,
        @Column("revoked_at")
        Instant revokedAt) {

    // The state / access_type values are the persisted+transmitted wire tokens (the DB
    // CHECK-constrained closed sets). The {@link AccessState} / {@link AccessType} enums
    // are CODE-SIDE lenses over these IDENTICAL strings (their value() == these constants),
    // used for typed branching; the column and DTO fields STAY String.

    public static final String STATE_ACTIVE = "active";
    public static final String STATE_REVOKED = "revoked";

    /** A GRANT of wiki READ access to a principal who would not have it by role. */
    public static final String TYPE_READ = "read";
    /** A BAN (or, with a future {@code expiresAt}, a TIMEOUT) denying wiki read. */
    public static final String TYPE_BANNED = "banned";

    /**
     * @return {@code true} iff {@code state} equals {@link #STATE_ACTIVE}
     */
    public boolean isActive() {
        return STATE_ACTIVE.equals(state);
    }

    /**
     * A timed (non-permanent) override whose TTL has passed at {@code now} — an expired
     * timeout.
     *
     * @param now the reference instant against which {@code expiresAt} is compared
     * @return {@code true} iff {@code expiresAt} is non-null and is not after {@code now}
     *         (i.e. the TTL has elapsed)
     */
    public boolean isExpiredAt(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    /**
     * Is this override IN EFFECT at {@code now} — i.e. {@code state='active'} AND not
     * past its TTL. An active permanent row ({@code expiresAt} null) is always in effect;
     * an active timed row is in effect only until its {@code expiresAt}. This is the
     * single predicate the request-time enforcement guard applies, so an expired timeout
     * auto-restores (stops denying) WITHOUT a write.
     *
     * @param now the reference instant to evaluate temporal validity against
     * @return {@code true} iff the override is active and has not yet expired
     */
    public boolean isEffectiveAt(Instant now) {
        return isActive() && !isExpiredAt(now);
    }
}
