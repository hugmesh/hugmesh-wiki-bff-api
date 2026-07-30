package systems.hugmesh.wikibff.access;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AccessState} contract: the enum is a CODE-SIDE lens over the closed
 * {@code wiki_access.state} vocabulary, and its wire form is BYTE-IDENTICAL to the prior
 * lowercase {@code "active"/"revoked"} strings the system stored / exchanged (the same
 * tokens still exposed as {@link WikiAccess#STATE_ACTIVE} / {@link WikiAccess#STATE_REVOKED},
 * which remain the persisted value). These tests pin that equivalence so the enum can never
 * silently drift the DB/JSON value.
 */
class AccessStateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void value_isTheExactLowercaseWireToken() {
        assertThat(AccessState.ACTIVE.value()).isEqualTo("active");
        assertThat(AccessState.REVOKED.value()).isEqualTo("revoked");
    }

    @Test
    void value_isByteIdenticalToTheWikiAccessStringConstants() {
        // The enum value() must be the SAME token the entity/repository persist.
        assertThat(AccessState.ACTIVE.value()).isEqualTo(WikiAccess.STATE_ACTIVE);
        assertThat(AccessState.REVOKED.value()).isEqualTo(WikiAccess.STATE_REVOKED);
    }

    @Test
    void values_areExactlyTheClosedSet() {
        // The closed set is exactly {active, revoked} — the DB CHECK ck_wiki_access_state.
        assertThat(java.util.Arrays.stream(AccessState.values()).map(AccessState::value).toList())
                .containsExactly("active", "revoked");
    }

    @Test
    void fromWire_roundTripsEveryToken() {
        for (AccessState s : AccessState.values()) {
            assertThat(AccessState.fromWire(s.value())).isEqualTo(s);
        }
    }

    @Test
    void fromWire_parsesTheWikiAccessStringConstants() {
        assertThat(AccessState.fromWire(WikiAccess.STATE_ACTIVE)).isEqualTo(AccessState.ACTIVE);
        assertThat(AccessState.fromWire(WikiAccess.STATE_REVOKED)).isEqualTo(AccessState.REVOKED);
    }

    @Test
    void fromWire_isNullTolerant_forNullAndUnknown() {
        assertThat(AccessState.fromWire(null)).isNull();
        assertThat(AccessState.fromWire("")).isNull();
        assertThat(AccessState.fromWire("deleted")).isNull();
        assertThat(AccessState.fromWire("ACTIVE")).isNull();   // case-sensitive: column is lowercase
        assertThat(AccessState.fromWire("Revoked")).isNull();
    }

    @Test
    void isValid_acceptsOnlyTheTwoTokens() {
        assertThat(AccessState.isValid("active")).isTrue();
        assertThat(AccessState.isValid("revoked")).isTrue();
        assertThat(AccessState.isValid("bogus")).isFalse();
        assertThat(AccessState.isValid(null)).isFalse();
        assertThat(AccessState.isValid("ACTIVE")).isFalse();
    }

    @Test
    void json_serializesToLowercaseToken() throws Exception {
        assertThat(MAPPER.writeValueAsString(AccessState.ACTIVE)).isEqualTo("\"active\"");
        assertThat(MAPPER.writeValueAsString(AccessState.REVOKED)).isEqualTo("\"revoked\"");
    }

    @Test
    void json_deserializesFromLowercaseToken() throws Exception {
        assertThat(MAPPER.readValue("\"active\"", AccessState.class)).isEqualTo(AccessState.ACTIVE);
        assertThat(MAPPER.readValue("\"revoked\"", AccessState.class)).isEqualTo(AccessState.REVOKED);
    }

    @Test
    void json_unknownTokenDeserializesToNull_neverThrows() throws Exception {
        assertThat(MAPPER.readValue("\"nope\"", AccessState.class)).isNull();
    }
}
