package net.hugmesh.wikibff.web;

import org.springframework.http.HttpStatus;
import net.hugmesh.commons.web.ApiException;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The ONE place that mints a "step-up required" refusal in the wiki BFF, and the ONE place
 * that owns the structured signal telling a client <em>which</em> step-up flavour to satisfy.
 *
 * <h2>Why this exists</h2>
 * {@code SecurityConfig.stepUp} composes the privileged-path rule as {@code base AND
 * hasAuthority(MFA_SATISFIED)}, and both conjuncts used to refuse with the same bare 403 (no
 * body, no header). A UI can therefore not tell "you are not an admin" from "you are an admin
 * who has not stepped up", and may only offer the MFA challenge for the second. See
 * hugmesh-wiki-bff-api#35.
 *
 * <h2>The wire contract</h2>
 * Byte-compatible with hugmesh-admin-bff-api's, hugmesh-forum-bff-api's and
 * hugmesh-mod-bff-api's exception of the same name, so there is ONE contract across BFFs:
 * <ul>
 *   <li><b>Status stays 403</b> (deliberately not 428: every existing client branches on
 *       403).</li>
 *   <li><b>Body is the unchanged {@code {"error": "<message>"}}</b>.</li>
 *   <li><b>The signal is a response header</b>, {@value #SIGNAL_HEADER}, whose value names
 *       the step-up FLAVOUR in the platform's {@code X-<flavour>-StepUp} spelling — here
 *       {@link net.hugmesh.wikibff.security.WikiStepUpGate#STEP_UP_HEADER}. Absent on every
 *       non-step-up response.</li>
 * </ul>
 *
 * <h2>What the value means HERE</h2>
 * This BFF has no step-up TOKEN leg: the gate is {@code hasAuthority(MFA_SATISFIED)} and
 * nothing else. The correct client follow-up is to complete a challenge (which stamps the
 * caller's next token) and RETRY the same request unchanged. The value is carried so the
 * flavour is observable, not because the service reads it back.
 *
 * <h2>Backward compatibility</h2>
 * Every message carries {@value #LEGACY_PHRASE}, which an old client with no structured
 * detection greps for. The constructor composes it, so no caller can mint a refusal without it.
 */
public final class StepUpRequiredException extends ApiException {

    private static final long serialVersionUID = 1L;

    /** The structured signal: a response header present on (and ONLY on) a step-up refusal. */
    public static final String SIGNAL_HEADER = "X-Step-Up-Required";

    /**
     * The free-text phrase a client with no structured leg greps for. The only place in main
     * sources where the phrase is emitted.
     */
    public static final String LEGACY_PHRASE = "requires step-up";

    /**
     * The platform detector rule for a flavour name. Also makes header-value injection (CR/LF)
     * structurally impossible: a bad value throws at construction and so can never reach the
     * wire.
     */
    private static final Pattern SAFE_HEADER_NAME =
            Pattern.compile("^X-[A-Za-z0-9-]{1,60}-StepUp$");

    private final String retryHeader;

    private StepUpRequiredException(String retryHeader, String message) {
        super(HttpStatus.FORBIDDEN, withLegacyPhrase(message, retryHeader));
        if (retryHeader == null || !SAFE_HEADER_NAME.matcher(retryHeader).matches()) {
            throw new IllegalArgumentException(
                    "step-up retry header must be an X-<flavour>-StepUp gate constant, got: "
                            + retryHeader);
        }
        this.retryHeader = retryHeader;
    }

    /** A message that already carries the phrase passes through verbatim; otherwise it is composed. */
    private static String withLegacyPhrase(String message, String retryHeader) {
        if (message != null && message.toLowerCase(Locale.ROOT).contains(LEGACY_PHRASE)) {
            return message;
        }
        if (message == null || message.isBlank()) {
            return "this operation " + LEGACY_PHRASE + " re-authentication (present "
                    + retryHeader + ")";
        }
        return message + " (" + LEGACY_PHRASE + " re-authentication: present " + retryHeader + ")";
    }

    /** The step-up flavour advertised on the wire. */
    public String retryHeader() {
        return retryHeader;
    }

    /**
     * The canonical refusal: {@code "<verb> requires step-up re-authentication (present
     * <retryHeader>)"} — the same template the sibling BFFs emit.
     *
     * @param retryHeader a gate's {@code STEP_UP_HEADER} constant
     * @param verb        the operation as it reads in the message, e.g. {@code "wiki administration"}
     */
    public static StepUpRequiredException forRetryHeader(String retryHeader, String verb) {
        return new StepUpRequiredException(retryHeader,
                verb + " " + LEGACY_PHRASE + " re-authentication (present " + retryHeader + ")");
    }
}
