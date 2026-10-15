package net.hugmesh.wikibff.web;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;
import net.hugmesh.commons.web.ApiException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Renders {@link ApiException} as the {@code {"error": message}} JSON body (and
 * the carried status) the UIs read, AND maps jakarta Bean Validation failures to a
 * clean {@code 400} in the SAME shape (with a {@code fields} map of per-field
 * errors). Works unchanged under WebFlux: {@code @RestControllerAdvice} +
 * {@code @ExceptionHandler} returning a {@code ResponseEntity} is handled
 * reactively (a controller's {@code Mono.error(...)} is routed here).
 *
 * <p>Validation shapes handled (the security-hardening "Bean Validation on all
 * paths" task):
 * <ul>
 *   <li>{@link WebExchangeBindException} — a {@code @Valid @RequestBody} DTO whose
 *       field constraints failed;</li>
 *   <li>{@link ConstraintViolationException} — a {@code @Validated} controller's
 *       {@code @PathVariable}/{@code @RequestParam} constraint failed;</li>
 *   <li>{@link ServerWebInputException} — a malformed/missing body or an
 *       unparseable param (surface as 400, not 500).</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * Renders an {@link ApiException} as a JSON response using the status and message
     * it carries.
     *
     * @param ex the {@code ApiException} thrown by a controller or service
     * @return a {@code ResponseEntity} with the exception's HTTP status and a body of
     *         {@code {"error": message}}, plus the {@code X-Step-Up-Required} header when (and
     *         only when) {@code ex} is a {@link StepUpRequiredException}
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handle(ApiException ex) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.status());
        if (ex instanceof StepUpRequiredException stepUp) {
            response = response.header(StepUpRequiredException.SIGNAL_HEADER, stepUp.retryHeader());
        }
        return response.body(Map.of("error", ex.getMessage()));
    }

    /**
     * A {@code @Valid @RequestBody} DTO failed field validation.
     *
     * @param ex the bind exception produced by WebFlux when one or more {@code @Valid}
     *           DTO field constraints are violated
     * @return a 400 Bad Request {@code ResponseEntity} with a body containing
     *         {@code {"error": "validation failed", "fields": {field: message, ...}}}
     */
    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<Map<String, Object>> handleBodyValidation(WebExchangeBindException ex) {
        // First-wins: toMap merge function keeps the first message seen for each field.
        Map<String, String> fields = ex.getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage(),
                        (first, ignored) -> first,
                        TreeMap::new));
        return badRequest(fields);
    }

    /**
     * A {@code @Validated} {@code @PathVariable}/{@code @RequestParam} failed.
     *
     * @param ex the constraint violation exception produced when a path variable or
     *           request parameter fails a {@code @Validated} constraint
     * @return a 400 Bad Request {@code ResponseEntity} with a body containing
     *         {@code {"error": "validation failed", "fields": {param: message, ...}}}
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleParamValidation(ConstraintViolationException ex) {
        // First-wins: toMap merge function keeps the first message seen for each param.
        Map<String, String> fields = ex.getConstraintViolations().stream()
                .collect(Collectors.toMap(
                        ApiExceptionHandler::lastNode,
                        ConstraintViolation::getMessage,
                        (first, ignored) -> first,
                        TreeMap::new));
        return badRequest(fields);
    }

    /**
     * A malformed/missing body or an unparseable param (e.g. wrong content-type, a
     * missing required body) — 400, not 500. {@link WebExchangeBindException}
     * subclasses this, so the more-specific handler above wins for DTO validation.
     *
     * @param ex the {@code ServerWebInputException} produced by WebFlux for malformed
     *           or missing request input
     * @return a 400 Bad Request {@code ResponseEntity} with a body containing
     *         {@code {"error": "validation failed", "detail": reason}}
     */
    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<Map<String, Object>> handleInput(ServerWebInputException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "validation failed");
        body.put("detail", ex.getReason() == null ? "malformed request" : ex.getReason());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Builds a 400 Bad Request {@code ResponseEntity} with the standard validation
     * error body containing an {@code "error"} key and the supplied per-field error map
     * under the {@code "fields"} key.
     *
     * @param fields a map of field/parameter names to their corresponding constraint
     *               violation messages
     * @return a 400 Bad Request {@code ResponseEntity} with body
     *         {@code {"error": "validation failed", "fields": fields}}
     */
    private static ResponseEntity<Map<String, Object>> badRequest(Map<String, String> fields) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "validation failed");
        body.put("fields", fields);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * The leaf property of a constraint-violation path = the param/field name.
     *
     * @param v the constraint violation whose property path is to be reduced
     * @return the last dot-separated node of the violation's property path, which
     *         corresponds to the param or field name that failed the constraint
     */
    private static String lastNode(ConstraintViolation<?> v) {
        String path = v.getPropertyPath().toString();
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : path;
    }
}
