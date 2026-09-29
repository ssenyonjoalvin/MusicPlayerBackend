package com.pahappa.musicplayer.api.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Uniform error envelope. Every non-2xx response from this API has this shape,
 * so a mobile client needs exactly one error parser.
 */
public record ErrorResponse(
        String code,
        String message,
        Instant timestamp,
        Map<String, String> fieldErrors) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, Instant.now(), Map.of());
    }

    public static ErrorResponse of(String code, String message, Map<String, String> fieldErrors) {
        return new ErrorResponse(code, message, Instant.now(), fieldErrors);
    }
}
