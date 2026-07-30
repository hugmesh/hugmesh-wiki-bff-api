package systems.hugmesh.wikibff.access;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The closed set of {@code wiki_access.state} lifecycle values — the strict
 * {@code active -> revoked} CAS the overlay walks (a row is NEVER deleted; revoke is the
 * audit of WHEN the override was lifted). This is a genuinely CLOSED vocabulary: it is
 * pinned by the 004 DB CHECK constraint
 * ({@code ck_wiki_access_state: state IN ('active','revoked')}), so a stored row can only
 * ever carry one of these two tokens.
 *
 * <h2>Wire form is BYTE-IDENTICAL to the prior {@code String} ("active"/"revoked")</h2>
 * Each constant carries the EXACT lowercase token the system has always stored /
 * exchanged — the SAME tokens still exposed as {@link WikiAccess#STATE_ACTIVE} /
 * {@link WikiAccess#STATE_REVOKED}, which remain the persisted/transmitted value:
 * <ul>
 *   <li><b>DB</b> — the {@code wiki_access.state varchar} column keeps its lowercase
 *       strings. The entity ({@link WikiAccess#state()}) still persists a {@code String},
 *       so R2DBC reads/writes are unchanged; this enum is a CODE-SIDE lens over that
 *       string, never a column type change.</li>
 *   <li><b>JSON</b> — {@link #value()} is annotated {@link JsonValue}, so anywhere an
 *       enum value were serialized it emits the lowercase token (not the Java constant
 *       name); {@link #fromWire(String)} (also a {@link JsonCreator}) parses the same
 *       tokens back. The {@code WikiAccessDto.Access.state} field still binds as a
 *       {@code String}, so this is not on any current JSON path — but the round-trip is
 *       wire-safe by construction.</li>
 * </ul>
 *
 * <p>This is the SECONDARY lens (the finder-argument vocabulary); the
 * {@link WikiAccess#STATE_ACTIVE} / {@link WikiAccess#STATE_REVOKED} String constants
 * stay the value handed to the repository and stamped on a row. The enum exists so the
 * closed set has a single typed home alongside {@link AccessType}, WITHOUT changing a
 * single stored or transmitted value.
 */
public enum AccessState {

    /** The override is IN FORCE (subject to its TTL) — the only state the service inserts. */
    ACTIVE(WikiAccess.STATE_ACTIVE),
    /** The override has been LIFTED (CAS active->revoked); the row is retained = audit. */
    REVOKED(WikiAccess.STATE_REVOKED);

    private final String value;

    AccessState(String value) {
        this.value = value;
    }

    /** The exact lowercase wire token ({@code active | revoked}) — the DB/JSON form. */
    @JsonValue
    public String value() {
        return value;
    }

    /**
     * Parse a wire token ({@code active | revoked}) to its {@link AccessState}, or
     * {@code null} when the token is null / unrecognised. Null-tolerant by design so a
     * caller holding a raw {@code String} never throws on a stray value — mirroring the
     * prior {@code "active".equals(state)} fall-through behaviour.
     */
    @JsonCreator
    public static AccessState fromWire(String wire) {
        if (wire == null) {
            return null;
        }
        for (AccessState s : values()) {
            if (s.value.equals(wire)) {
                return s;
            }
        }
        return null;
    }

    /** True iff {@code wire} is one of the two valid lifecycle tokens. */
    public static boolean isValid(String wire) {
        return fromWire(wire) != null;
    }
}
