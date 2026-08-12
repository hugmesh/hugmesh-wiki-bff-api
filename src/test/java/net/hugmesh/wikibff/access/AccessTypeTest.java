package net.hugmesh.wikibff.access;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AccessType} contract: the enum is a CODE-SIDE lens over the closed
 * {@code wiki_access.access_type} vocabulary, and its wire form is BYTE-IDENTICAL to the
 * prior lowercase {@code "read"/"banned"} strings the system stored / exchanged (the same
 * tokens still exposed as {@link WikiAccess#TYPE_READ} / {@link WikiAccess#TYPE_BANNED},
 * which remain the persisted value). These tests pin that equivalence so the enum can
 * never silently drift the DB/JSON value.
 */
class AccessTypeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void value_isTheExactLowercaseWireToken() {
        // The stored/transmitted token is the lowercase form, NOT the Java constant name.
        assertThat(AccessType.READ.value()).isEqualTo("read");
        assertThat(AccessType.BANNED.value()).isEqualTo("banned");
    }

    @Test
    void value_isByteIdenticalToTheWikiAccessStringConstants() {
        // The enum value() must be the SAME token the entity/repository/DTO persist+exchange.
        assertThat(AccessType.READ.value()).isEqualTo(WikiAccess.TYPE_READ);
        assertThat(AccessType.BANNED.value()).isEqualTo(WikiAccess.TYPE_BANNED);
    }

    @Test
    void values_areExactlyTheClosedSet() {
        // The closed set is exactly {read, banned} — the DB CHECK ck_wiki_access_type.
        assertThat(java.util.Arrays.stream(AccessType.values()).map(AccessType::value).toList())
                .containsExactly("read", "banned");
    }

    @Test
    void fromWire_roundTripsEveryToken() {
        for (AccessType t : AccessType.values()) {
            // value() -> fromWire() returns the SAME constant (round-trip identity).
            assertThat(AccessType.fromWire(t.value())).isEqualTo(t);
        }
    }

    @Test
    void fromWire_parsesTheWikiAccessStringConstants() {
        assertThat(AccessType.fromWire(WikiAccess.TYPE_READ)).isEqualTo(AccessType.READ);
        assertThat(AccessType.fromWire(WikiAccess.TYPE_BANNED)).isEqualTo(AccessType.BANNED);
    }

    @Test
    void fromWire_isNullTolerant_forNullAndUnknown() {
        // A null / unknown / wrong-case token maps to null (the guard treats null as
        // "neither a ban nor a grant", exactly the prior string-equals fall-through).
        assertThat(AccessType.fromWire(null)).isNull();
        assertThat(AccessType.fromWire("")).isNull();
        assertThat(AccessType.fromWire("revoke")).isNull();
        assertThat(AccessType.fromWire("READ")).isNull();   // case-sensitive: the column is lowercase
        assertThat(AccessType.fromWire("Banned")).isNull();
    }

    @Test
    void isValid_acceptsOnlyTheTwoTokens() {
        // The controller's prior read|banned||null ternary accepted exactly these two.
        assertThat(AccessType.isValid("read")).isTrue();
        assertThat(AccessType.isValid("banned")).isTrue();
        assertThat(AccessType.isValid("bogus")).isFalse();
        assertThat(AccessType.isValid(null)).isFalse();
        assertThat(AccessType.isValid("READ")).isFalse();
    }

    @Test
    void json_serializesToLowercaseToken() throws Exception {
        // @JsonValue => the enum serializes to its lowercase wire token, never "READ".
        assertThat(MAPPER.writeValueAsString(AccessType.READ)).isEqualTo("\"read\"");
        assertThat(MAPPER.writeValueAsString(AccessType.BANNED)).isEqualTo("\"banned\"");
    }

    @Test
    void json_deserializesFromLowercaseToken() throws Exception {
        // @JsonCreator(fromWire) => the lowercase token parses back to the constant.
        assertThat(MAPPER.readValue("\"read\"", AccessType.class)).isEqualTo(AccessType.READ);
        assertThat(MAPPER.readValue("\"banned\"", AccessType.class)).isEqualTo(AccessType.BANNED);
    }

    @Test
    void json_unknownTokenDeserializesToNull_neverThrows() throws Exception {
        // An unrecognised token is tolerated (null), mirroring the read-path leniency.
        assertThat(MAPPER.readValue("\"nope\"", AccessType.class)).isNull();
    }
}
