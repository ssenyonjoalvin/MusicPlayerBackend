package com.pahappa.musicplayer.service;

import com.pahappa.musicplayer.domain.Track;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Holds live transfer counters for in-flight downloads.
 *
 * <p>Progress ticks arrive several times a second. Writing each one to the
 * database would be pure write amplification for data that is worthless one
 * second later, so live progress lives in memory and is merged into the
 * response by the status endpoint. Only state transitions
 * ({@code QUEUED -> DOWNLOADING -> READY|FAILED}) are persisted.
 *
 * <p>Entries are removed on completion, so the map is bounded by the number of
 * concurrent downloads. A restart legitimately loses live progress; startup
 * reconciliation converts any interrupted job to {@code FAILED} instead.
 */
@Component
public class DownloadProgressRegistry {

    private final Map<UUID, YtDlpDownloader.TransferProgress> live = new ConcurrentHashMap<>();

    public void start(Track track) {
        live.put(
                track.getId(),
                new YtDlpDownloader.TransferProgress(0d, null, null, null, null));
    }

    public void update(UUID trackId, YtDlpDownloader.TransferProgress progress) {
        if (progress != null) {
            live.put(trackId, progress);
        }
    }

    public YtDlpDownloader.TransferProgress get(UUID trackId) {
        return live.get(trackId);
    }

    public void finish(UUID trackId) {
        live.remove(trackId);
    }

    public boolean isTracked(UUID trackId) {
        return live.containsKey(trackId);
    }
}
