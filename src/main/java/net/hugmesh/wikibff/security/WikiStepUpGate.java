package net.hugmesh.wikibff.security;

import org.springframework.web.server.ServerWebExchange;

import net.hugmesh.wikibff.web.StepUpRequiredException;

import java.util.Optional;

/**
 * The step-up FLAVOUR this BFF advertises, and the one-request handoff that tells the
 * access-denied handler a 403 came from the step-up leg rather than from a role leg.
 *
 * <p>{@code SecurityConfig.stepUp(base, surface)} composes the privileged-path rule as
 * {@code base AND hasAuthority(MFA_SATISFIED)}. When it denies, Spring raises an ordinary
 * {@link org.springframework.security.access.AccessDeniedException} that records nothing about
 * which conjunct said no. The gate runs inside {@code authorizeExchange} and so already holds
 * the {@link ServerWebExchange}: the decision is RECORDED at the point it is made
 * ({@link #recordDenial}) and READ back by the handler ({@link #deniedSurface}). There is no
 * path inventory to duplicate and so none to drift.
 *
 * <p>Fail-CLOSED in the direction that matters: a missing attribute means "no signal", i.e.
 * render exactly what Spring would have. This type cannot grant anything; it only decorates a
 * refusal that has already been decided.
 *
 * @see StepUpRequiredException
 * @see WikiStepUpAccessDeniedHandler
 */
public final class WikiStepUpGate {

    private WikiStepUpGate() {}

    /**
     * The step-up FLAVOUR this BFF advertises, in the platform's {@code X-<flavour>-StepUp}
     * spelling. It is the VALUE of the {@value StepUpRequiredException#SIGNAL_HEADER}
     * response header on a step-up refusal, not a request header this service reads.
     */
    public static final String STEP_UP_HEADER = "X-Wiki-StepUp";

    /**
     * The two PRIVILEGED path groups {@code SecurityConfig.commonRoutes} wraps in the step-up
     * composition, each with the verb that reads in its refusal message. The ordinary
     * {@code /bff/wiki/**} read floor is deliberately absent: reading is not step-up gated.
     */
    public enum Surface {

        /** {@code /bff/wiki/superadmin/**} — the audit trail and access-state views. */
        SUPERADMIN("wiki management"),

        /** {@code /bff/wiki/admin/**} — the write/publish console seam. */
        ADMIN("wiki administration");

        private final String verb;

        Surface(String verb) {
            this.verb = verb;
        }

        /** The operation as it reads in the refusal message. */
        public String verb() {
            return verb;
        }

        /** The refusal to emit when the step-up leg denied on this surface. */
        public StepUpRequiredException denial() {
            return StepUpRequiredException.forRetryHeader(STEP_UP_HEADER, verb);
        }
    }

    /**
     * Exchange-attribute key for the handoff. Fully-qualified so it cannot collide, and
     * deliberately NOT a header: nothing a client sends can set it and it never reaches the wire.
     */
    private static final String DENIED_ATTRIBUTE = WikiStepUpGate.class.getName() + ".DENIED";

    /**
     * Record that the step-up conjunct — and only it — refused this exchange. Called from
     * {@code SecurityConfig.stepUp} after {@code base} has GRANTED and the
     * {@code MFA_SATISFIED} leg denies.
     */
    public static void recordDenial(ServerWebExchange exchange, Surface surface) {
        exchange.getAttributes().put(DENIED_ATTRIBUTE, surface);
    }

    /** The surface whose step-up leg refused this exchange, or empty for any other refusal. */
    public static Optional<Surface> deniedSurface(ServerWebExchange exchange) {
        Object recorded = exchange.getAttributes().get(DENIED_ATTRIBUTE);
        return recorded instanceof Surface surface ? Optional.of(surface) : Optional.empty();
    }
}
