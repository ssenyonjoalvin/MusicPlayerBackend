package com.pahappa.musicplayer.service;

import java.util.UUID;

/**
 * Signals that a track row is committed and its download may begin.
 *
 * <p>Published from inside a transaction and consumed
 * {@code AFTER_COMMIT}. Without that boundary the async worker can start before
 * the row is visible, fail to find the track, and silently drop the job.
 */
public record DownloadRequestedEvent(UUID trackId) {
}
