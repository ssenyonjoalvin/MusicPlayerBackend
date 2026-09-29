package com.pahappa.musicplayer.api;

import com.pahappa.musicplayer.api.dto.ErrorResponse;
import com.pahappa.musicplayer.exception.ApiException;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Translates exceptions into the {@link ErrorResponse} envelope.
 *
 * <p>Unexpected exceptions are logged with their stack trace and reported as a
 * generic {@code internal_error}; the raw message never reaches the client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ErrorResponse.of(ex.getCode(), ex.getMessage()));
    }

    /** Bean Validation failures on {@code @Valid} request bodies. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("validation_failed", "Request body is invalid", fieldErrors));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("validation_failed", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(
                "validation_failed", "Parameter '" + ex.getName() + "' has an invalid value"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("malformed_body", "Request body is missing or not valid JSON"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("bad_request", ex.getMessage()));
    }

    /** Unmapped static resources should look like any other 404. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("not_found", "Resource not found"));
    }

    /**
     * The client went away mid-response.
     *
     * <p>Completely routine for a media endpoint: a player seeking, stopping, or
     * being backgrounded all abort the connection. Treating it as an error
     * buries real failures under stack traces for a condition the server cannot
     * act on, and the aborted write then triggers a second failure while the
     * error handler tries to write a JSON body onto a response already
     * committed as {@code audio/mp4}.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientDisconnect(AsyncRequestNotUsableException ex) {
        log.debug("Client disconnected during response: {}", ex.getMessage());
    }

    /**
     * The async timeout elapsed before the body finished writing.
     *
     * <p>The client received a truncated response, so this is worth a WARN - but
     * it is a timeout policy outcome, not a defect, and the response is already
     * committed. Retrying with a longer {@code app.streaming.async-timeout} is
     * the remedy.
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<ErrorResponse> handleAsyncTimeout(
            AsyncRequestTimeoutException ex, HttpServletResponse response) {

        log.warn("Response exceeded the async timeout and was truncated: {}", ex.getMessage());
        if (response.isCommitted()) {
            return null;
        }
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(ErrorResponse.of(
                        "stream_timeout",
                        "The audio took too long to send. Retry, or raise app.streaming.async-timeout."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletResponse response) {
        log.error("Unhandled exception", ex);
        if (response.isCommitted()) {
            // Status and headers are already on the wire; writing a body now
            // would only corrupt the response the client is reading.
            return null;
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("internal_error", "Unexpected server error"));
    }
}
