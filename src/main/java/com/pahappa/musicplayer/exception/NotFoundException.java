package com.pahappa.musicplayer.exception;

import org.springframework.http.HttpStatus;

public class NotFoundException extends ApiException {

    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "not_found", message);
    }

    public static NotFoundException track(Object id) {
        return new NotFoundException("No track with id " + id);
    }
}
