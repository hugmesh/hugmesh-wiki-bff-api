package systems.hugmesh.wikibff.access;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The closed set of {@code wiki_access.access_type} kinds — the WHAT a per-principal
 * override does: a GRANT of read, or a BAN/TIMEOUT of read. This is a genuinely CLOSED
 * vocabulary: it is pinned by the 004 DB CHECK constraint
 * ({@code ck_wiki_access_type: access_type IN ('read','banned')}), so a stored row can
 * only ever carry one of these two tokens.
 *
 * <h2>Wire form is BYTE-IDENTICAL to the prior {@code String} ("read"/"banned")</h2>
 * Each constant carries the EXACT lowercase token the system has always stored /
 * exchanged — the SAME tokens still exposed as {@link WikiAccess#TYPE_READ} /
 * {@link WikiAccess#TYPE_BANNED}, which remain the persisted/transmitted value:
 * <ul>
 *   <li><b>DB</b> — the {@code wiki_access.access_type varchar} column keeps its
 *       lowercase strings. The entity ({@link WikiAccess#accessType()}) still persists a
 *       {@code String}, so R2DBC reads/writes are unchanged; this enum is a CODE-SIDE
 *       lens over that string, never a column type change.</li>
 *   <li><b>JSON</b> — {@link #value()} is annotated {@link JsonValue}, so anywhere an
 *       enum value were serialized it emits the lowercase token (not the Java constant
 *       name); {@link #fromWire(String)} (also a {@link JsonCreator}) parses the same
 *       tokens back. The {@code WikiAccessDto.Access.accessType} field still binds as a
 *       {@code String}, so this is not on any current JSON path — but the round-trip is
 *       wire-safe by construction.</li>
 * </ul>
 *
 * <p>Routing the access-type branching ({@link WikiAccessController}'s revoke
 * validation, {@link WikiAccessGuard}'s ban/grant precedence) through this enum replaces
 * the scattered {@code "read".equals(accessType)} / {@code "banned".equals(accessType)}
 * string comparisons with a typed, typo-proof check, WITHOUT changing a single stored or
 * transmitted value.
 */
public enum AccessType {

    /** A GRANT of wiki READ access to a principal who would not have it by role. */
    READ(WikiAccess.TYPE_READ),
    /** A BAN (or, with a future {@code expires_at}, a TIMEOUT) denying wiki read. */
    BANNED(WikiAccess.TYPE_BANNED);

    private final String value;

    AccessType(String value) {
        this.value = value;
    }

    /** The exact lowercase wire token ({@code read | banned}) — the DB/JSON form. */
    @JsonValue
    public String value() {
        return value;
    }

    /**
     * Parse a wire token ({@code read | banned}) to its {@link AccessType}, or
     * {@code null} when the token is null / unrecognised. Null-tolerant by design: the
     * read path ({@link WikiAccessGuard}) treats an unknown / null type as "neither a ban
     * nor a grant", so it never throws on a stray row — exactly the prior
     * {@code "banned".equals(accessType)} / {@code "read".equals(accessType)}
     * fall-through behaviour.
     */
    @JsonCreator
    public static AccessType fromWire(String wire) {
        if (wire == null) {
            return null;
        }
        for (AccessType t : values()) {
            if (t.value.equals(wire)) {
                return t;
            }
        }
        return null;
    }

    /**
     * True iff {@code wire} is one of the two valid access-type tokens. The typed
     * equivalent of the hand-rolled {@code read|banned||null} ternary the controller
     * used to validate the {@code @PathVariable accessType}, kept so callers that still
     * hold a raw {@code String} get the same accept/reject verdict.
     */
    public static boolean isValid(String wire) {
        return fromWire(wire) != null;
    }
}
