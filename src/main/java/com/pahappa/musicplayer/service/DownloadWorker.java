package com.pahappa.musicplayer.service;

import com.pahappa.musicplayer.domain.Track;
import com.pahappa.musicplayer.exception.ApiException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Executes one download on the background pool.
 *
 * <p>Started from an {@code AFTER_COMMIT} event rather than a direct call, for
 * two reasons: the worker must not look for a row that is not committed yet, and
 * an over-capacity executor must not roll back the accepted job.
 */
@Service
public class DownloadWorker {

    private static final Logger log = LoggerFactory.getLogger(DownloadWorker.class);

    private final TrackStateWriter stateWriter;
    private final YtDlpDownloader downloader;
    private final DownloadProgressRegistry progress;
    private final ThumbnailService thumbnails;
    private final FileStorageService storage;
    private final LoudnessService loudness;

    public DownloadWorker(
            TrackStateWriter stateWriter,
            YtDlpDownloader downloader,
            DownloadProgressRegistry progress,
            ThumbnailService thumbnails,
            FileStorageService storage,
            LoudnessService loudness) {
        this.stateWriter = stateWriter;
        this.downloader = downloader;
        this.progress = progress;
        this.thumbnails = thumbnails;
        this.storage = storage;
        this.loudness = loudness;
    }

    /**
     * Consumes the request and hands off to the download pool.
     *
     * <p>{@code fallbackExecution = true} is essential, not decorative. The
     * event is published from two kinds of caller:
     *
     * <ul>
     *   <li><b>inside</b> a transaction (retry, delete) - the listener must wait
     *       for {@code AFTER_COMMIT}, or the worker races ahead of the row it is
     *       about to look up.</li>
     *   <li><b>outside</b> any transaction (a fresh submit, which deliberately
     *       holds no transaction across the slow metadata call) - with
     *       {@code AFTER_COMMIT} alone the synchronization is never active, the
     *       event is published to nobody, and the download silently never
     *       starts.</li>
     * </ul>
     *
     * {@code fallbackExecution} covers both: defer when a transaction exists,
     * run immediately when it does not.
     *
     * <p>{@code @Async} on an event listener is proxied by the event multicaster,
     * so this genuinely returns to the caller immediately.
     */
    @Async("downloadExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDownloadRequested(DownloadRequestedEvent event) {
        runDownload(event.trackId());
    }

    void runDownload(UUID trackId) {
        Track track = stateWriter.find(trackId).orElse(null);
        if (track == null) {
            log.warn("Download requested for unknown track {}", trackId);
            return;
        }

        progress.start(track);
        stateWriter.markDownloading(trackId);

        try {
            YtDlpDownloader.DownloadResult result = downloader.download(
                    track, track.getSourceUrl(), update -> progress.update(trackId, update));

            stateWriter
                    .markReady(trackId, result.file(), result.sizeBytes(), storage.relativise(result.file()))
                    .ifPresent(this::cacheCover);
            // After READY, not before: the track is playable the moment the file
            // lands, and a few seconds later it also plays at the right volume.
            loudness.measureAndStore(trackId, result.file());
        } catch (ApiException ex) {
            stateWriter.markFailed(trackId, ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Unexpected failure downloading track {}", trackId, ex);
            stateWriter.markFailed(trackId, "Unexpected server error while downloading.");
        } finally {
            progress.finish(trackId);
            // .part files are dead weight once the audio has been filed away.
            storage.purgeWorkDirectory(trackId.toString());
        }
    }

    /** Artwork is a nicety; never let it turn a good download into a failure. */
    private void cacheCover(Track ready) {
        try {
            stateWriter.attachCover(ready.getId(), thumbnails.cacheCover(ready));
        } catch (RuntimeException ex) {
            log.debug("Cover caching skipped for track {}: {}", ready.getId(), ex.getMessage());
        }
    }
}
