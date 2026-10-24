package com.pm.filmfuse.common.exception;

import com.pm.filmfuse.common.http.PayloadTooLargeException;
import com.pm.filmfuse.common.http.RequestBodyLimitFilter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.util.DisconnectedClientHelper;

/**
 * Translates exceptions into {@link ErrorResponseDTO} ({@code {message, fieldErrors?}}).
 *
 * <p>Domain exceptions carry text written for the caller and are returned verbatim. Anything else
 * (constraint violations, bugs) is logged with a short error id and answered with a fixed
 * sentence plus that id - echoing arbitrary {@code ex.getMessage()} leaks SQL and config keys.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponseDTO> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(new ErrorResponseDTO("Validation failed", fieldErrors));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponseDTO> handleMethodValidation(HandlerMethodValidationException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getParameterValidationResults().forEach(result -> {
            String name = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().forEach(error ->
                    fieldErrors.putIfAbsent(name != null ? name : "parameter", error.getDefaultMessage()));
        });
        return ResponseEntity.badRequest().body(new ErrorResponseDTO("Validation failed", fieldErrors));
    }

    /** A required query parameter is absent (e.g. {@code /movies/search} without {@code q}). */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponseDTO> handleMissingParameter(MissingServletRequestParameterException ex) {
        return ResponseEntity.badRequest().body(new ErrorResponseDTO("Validation failed",
                Map.of(ex.getParameterName(), "This parameter is required")));
    }

    /**
     * A path variable or query parameter that does not convert (e.g. {@code /movies/abc}). The
     * converter's own message names Java types, so the field gets a fixed sentence instead.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponseDTO> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        Class<?> requiredType = ex.getRequiredType();
        String problem = requiredType != null && (Number.class.isAssignableFrom(requiredType)
                || (requiredType.isPrimitive() && requiredType != boolean.class))
                ? "Must be a whole number" : "Has an invalid value";
        return ResponseEntity.badRequest().body(new ErrorResponseDTO("Validation failed", Map.of(ex.getName(), problem)));
    }

    /**
     * A malformed body. The parser's message names Java types and offsets - do not forward it. A
     * body cut off by {@link RequestBodyLimitFilter} (no Content-Length) arrives here too: 413.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponseDTO> handleUnreadable(HttpMessageNotReadableException ex) {
        if (PayloadTooLargeException.isCause(ex)) {
            return payloadTooLarge();
        }
        log.debug("Rejected an unreadable request body", ex);
        return ResponseEntity.badRequest().body(ErrorResponseDTO.of("The request body could not be read as JSON."));
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponseDTO> handleBadRequest(BadRequestException ex) {
        return ResponseEntity.badRequest().body(ErrorResponseDTO.of(ex.getMessage()));
    }

    @ExceptionHandler(FieldValidationException.class)
    public ResponseEntity<ErrorResponseDTO> handleFieldValidation(FieldValidationException ex) {
        return ResponseEntity.badRequest().body(new ErrorResponseDTO(ex.getMessage(), ex.fieldErrors()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponseDTO> handleNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponseDTO.of(ex.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponseDTO> handleConflict(ConflictException ex) {
        log.debug("Conflict: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponseDTO.of(ex.getMessage()));
    }

    @ExceptionHandler(GoneException.class)
    public ResponseEntity<ErrorResponseDTO> handleGone(GoneException ex) {
        return ResponseEntity.status(HttpStatus.GONE).body(ErrorResponseDTO.of(ex.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponseDTO> handleForbidden(ForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponseDTO.of(ex.getMessage()));
    }

    /** TMDB / Supabase failed or timed out. The cause (upstream status, body) is logged only. */
    @ExceptionHandler(UpstreamUnavailableException.class)
    public ResponseEntity<ErrorResponseDTO> handleUpstream(UpstreamUnavailableException ex) {
        log.warn("Upstream unavailable: {}", ex.getMessage(), ex.getCause());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponseDTO.of(ex.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponseDTO> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponseDTO.of("You do not have access to this resource."));
    }

    /**
     * A uniqueness or foreign-key constraint refused the write (e.g. two requests racing for the
     * same username). The driver message quotes index names and values, so it is logged only.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponseDTO> handleDataIntegrity(DataIntegrityViolationException ex) {
        String errorId = newErrorId();
        log.warn("Data integrity violation [{}]", errorId, ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponseDTO.of("That change conflicts with data that already exists. (error " + errorId + ")"));
    }

    /**
     * Anything unexpected becomes a 500 with an error id. Spring's own request exceptions (bad
     * path variable, unsupported method, missing parameter) already carry the right 4xx, so they
     * are rethrown untouched, as is a client disconnect.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseDTO> handleUnexpected(Exception ex) throws Exception {
        if (ex instanceof ErrorResponse || ex instanceof TypeMismatchException
                || ex instanceof AsyncRequestNotUsableException
                || DisconnectedClientHelper.isClientDisconnectedException(ex)) {
            throw ex;
        }
        if (PayloadTooLargeException.isCause(ex)) {
            return payloadTooLarge();
        }
        String errorId = newErrorId();
        log.error("Unhandled exception [{}]", errorId, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponseDTO.of("Something went wrong on our end. (error " + errorId + ")"));
    }

    private static ResponseEntity<ErrorResponseDTO> payloadTooLarge() {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(ErrorResponseDTO.of(RequestBodyLimitFilter.message()));
    }

    private static String newErrorId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
