package com.pahappa.musicplayer.domain;

/** Lifecycle of a track in the library. */
public enum TrackStatus {

    /** Accepted and persisted, waiting for a worker slot. */
    QUEUED,

    /** A {@code yt-dlp} process is currently writing the file. */
    DOWNLOADING,

    /** File is on disk and the stream endpoint can serve it. */
    READY,

    /** Terminal failure; {@code errorMessage} explains why and retry is allowed. */
    FAILED;

    public boolean isTerminal() {
        return this == READY || this == FAILED;
    }

    public boolean isPlayable() {
        return this == READY;
    }
}
