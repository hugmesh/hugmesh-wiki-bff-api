package net.hugmesh.wikibff.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import net.hugmesh.commons.web.ApiException;
import net.hugmesh.wikibff.security.WikiStepUpGate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The wire contract of the step-up refusal type (hugmesh-wiki-bff-api#35): header value shape,
 * the legacy phrase, and the {@link ApiExceptionHandler} seam that renders it when a controller
 * (rather than the filter chain) throws it.
 */
class StepUpRequiredExceptionTest {

    @Test
    void everySurfaceDenial_carriesTheLegacyPhraseAndAPlatformShapedFlavour() {
        for (WikiStepUpGate.Surface surface : WikiStepUpGate.Surface.values()) {
            StepUpRequiredException e = surface.denial();
            assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(e.getMessage()).contains(StepUpRequiredException.LEGACY_PHRASE);
            assertThat(e.retryHeader()).isEqualTo("X-Wiki-StepUp")
                    .matches("^X-[A-Za-z0-9-]{1,60}-StepUp$");
        }
    }

    @Test
    void aFlavourThatIsNotPlatformShaped_isRejectedAtConstruction() {
        for (String bad : new String[] {null, "", "X-StepUp", "Wiki-StepUp", "X-Wiki-StepUp\r\nSet-Cookie: a=b",
                "X-Wiki-Step-Up"}) {
            assertThatThrownBy(() -> StepUpRequiredException.forRetryHeader(bad, "v"))
                    .as("flavour %s", bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void apiExceptionHandler_addsTheSignalForAStepUpRefusal_andOnlyForThat() {
        ApiExceptionHandler handler = new ApiExceptionHandler();

        ResponseEntity<Map<String, Object>> stepUp =
                handler.handle(WikiStepUpGate.Surface.ADMIN.denial());
        assertThat(stepUp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(stepUp.getHeaders().get(StepUpRequiredException.SIGNAL_HEADER))
                .containsExactly("X-Wiki-StepUp");
        assertThat(String.valueOf(stepUp.getBody().get("error")))
                .contains(StepUpRequiredException.LEGACY_PHRASE);

        ResponseEntity<Map<String, Object>> plain =
                handler.handle(new ApiException(HttpStatus.FORBIDDEN, "nope"));
        assertThat(plain.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(plain.getHeaders().get(StepUpRequiredException.SIGNAL_HEADER)).isNull();
        assertThat(plain.getBody()).isEqualTo(Map.of("error", "nope"));
    }
}
