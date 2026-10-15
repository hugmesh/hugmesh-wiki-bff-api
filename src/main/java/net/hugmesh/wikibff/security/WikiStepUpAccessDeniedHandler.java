package net.hugmesh.wikibff.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.web.access.server.BearerTokenServerAccessDeniedHandler;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import net.hugmesh.wikibff.web.StepUpRequiredException;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Makes a STEP-UP refusal recognisable on the wire, and changes nothing else
 * (hugmesh-wiki-bff-api#35).
 *
 * <p>Installed as the chain's {@code accessDeniedHandler}. When {@link WikiStepUpGate}
 * recorded that {@code SecurityConfig.stepUp}'s base rule GRANTED and the
 * {@code MFA_SATISFIED} leg denied, it renders status 403 (unchanged), the shared
 * {@code {"error": "<message>"}} body (carrying the legacy {@code requires step-up} phrase)
 * and the {@value StepUpRequiredException#SIGNAL_HEADER} header naming the flavour. EVERY other
 * denial is delegated, untouched, to {@link #DEFAULT}.
 *
 * <p>That single recorded condition covers: flag dark (the wrapper returns {@code base}, so
 * nothing records), a role denial (the attribute is written only after {@code base} granted),
 * and a method-security denial from a controller {@code @PreAuthorize} (the path gate granted,
 * so no attribute). An unauthenticated caller never reaches an access-denied handler: the
 * exception-translation filter routes it to the authentication entry point (401).
 *
 * <h2>Why the delegate is the BEARER handler</h2>
 * Installing an explicit {@code accessDeniedHandler} makes {@code ServerHttpSecurity} discard
 * its default handlers wholesale. For a chain that configures {@code oauth2ResourceServer}
 * (both of this service's chains do) those defaults resolve to
 * {@link BearerTokenServerAccessDeniedHandler}, which sends the RFC 6750
 * {@code WWW-Authenticate} challenge and an empty body; the bare
 * {@code HttpStatusServerAccessDeniedHandler} would drop that challenge and invent a
 * {@code text/plain} body on every non-step-up 403. {@code StepUpAccessDeniedWiringTest}
 * extracts Spring's own handler at runtime and compares the whole response, so a Spring
 * upgrade that resolves its defaults differently fails there rather than moving the wire.
 *
 * <p>The step-up response is the one deliberate exception: it carries no
 * {@code WWW-Authenticate}, because re-presenting the same bearer credential will not satisfy it.
 */
public class WikiStepUpAccessDeniedHandler implements ServerAccessDeniedHandler {

    /** What {@code ServerHttpSecurity} installs on a chain that configures {@code oauth2ResourceServer}. */
    private static final ServerAccessDeniedHandler DEFAULT =
            new BearerTokenServerAccessDeniedHandler();

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
        return WikiStepUpGate.deniedSurface(exchange)
                .map(surface -> writeStepUpRefusal(exchange, surface))
                .orElseGet(() -> DEFAULT.handle(exchange, denied));
    }

    private static Mono<Void> writeStepUpRefusal(ServerWebExchange exchange,
                                                 WikiStepUpGate.Surface surface) {
        StepUpRequiredException refusal = surface.denial();
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(refusal.status());
        response.getHeaders().set(StepUpRequiredException.SIGNAL_HEADER, refusal.retryHeader());
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory().wrap(errorBody(refusal.getMessage()));
        return response.writeWith(Mono.just(buffer));
    }

    /** {@code {"error": "<message>"}}, built through Jackson so no message can produce malformed JSON. */
    private static byte[] errorBody(String message) {
        try {
            return JSON.writeValueAsBytes(Map.of("error", message));
        } catch (JsonProcessingException e) {
            // Unreachable for a Map<String,String>; the caller is already being refused.
            return ("{\"error\":\"" + StepUpRequiredException.LEGACY_PHRASE
                    + " re-authentication\"}").getBytes(StandardCharsets.UTF_8);
        }
    }
}
