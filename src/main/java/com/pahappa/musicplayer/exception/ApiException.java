package com.pahappa.musicplayer.exception;

import org.springframework.http.HttpStatus;

/**
 * Base type for errors that map to a deliberate HTTP response.
 *
 * <p>Concrete rather than abstract: most failures are a code plus a message with
 * no extra behaviour, so subclasses are reserved for the cases that carry their
 * own construction rules (see {@link NotFoundException}).
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public ApiException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    /** Stable machine-readable code so clients can branch without string matching. */
    public String getCode() {
        return code;
    }
}
