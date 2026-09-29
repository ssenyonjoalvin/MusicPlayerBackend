package com.pahappa.musicplayer.exception;

import org.springframework.http.HttpStatus;

/** All download workers are busy and the backlog is at capacity. */
public class QueueFullException extends ApiException {

    public QueueFullException(String message) {
        super(HttpStatus.TOO_MANY_REQUESTS, "queue_full", message);
    }
}
