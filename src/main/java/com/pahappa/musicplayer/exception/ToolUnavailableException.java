package com.pahappa.musicplayer.exception;

import org.springframework.http.HttpStatus;

/** A required external binary (yt-dlp, ffmpeg, python) is missing or unusable. */
public class ToolUnavailableException extends ApiException {

    public ToolUnavailableException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "tool_unavailable", message);
    }
}
