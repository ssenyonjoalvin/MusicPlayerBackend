package com.pahappa.musicplayer.exception;

import org.springframework.http.HttpStatus;

/** The submitted text is not a link we can turn into a track. */
public class InvalidLinkException extends ApiException {

    public InvalidLinkException(String message) {
        super(HttpStatus.BAD_REQUEST, "invalid_link", message);
    }
}
