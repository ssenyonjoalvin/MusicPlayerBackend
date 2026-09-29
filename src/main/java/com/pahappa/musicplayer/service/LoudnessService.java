package com.pahappa.musicplayer.service;

import com.pahappa.musicplayer.domain.Track;
import com.pahappa.musicplayer.domain.TrackStatus;
import com.pahappa.musicplayer.repo.TrackRepository;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Keeps every READY track's loudness measured.
 *
 * <p>New downloads are measured by the download worker as soon as they land.
 * Tracks downloaded before this feature existed are measured once, in the
 * background, after startup - on a single virtual thread, so the backfill
 * never competes with real downloads for the download pool.
 */
@Service
public class LoudnessService {

    private static final Logger log = LoggerFactory.getLogger(LoudnessService.class);

    private final LoudnessAnalyzer analyzer;
    private final TrackRepository repository;
    private final TrackStateWriter stateWriter;
    private final FileStorageService storage;
    private final AtomicBoolean backfilling = new AtomicBoolean(false);

    public LoudnessService(
            LoudnessAnalyzer analyzer,
            TrackRepository repository,
            TrackStateWriter stateWriter,
            FileStorageService storage) {
        this.analyzer = analyzer;
        this.repository = repository;
        this.stateWriter = stateWriter;
        this.storage = storage;
    }

    /** Measures one finished file and stores the result. Never throws. */
    public void measureAndStore(UUID trackId, Path file) {
        try {
            analyzer.measure(file).ifPresent(loudness -> {
                stateWriter.attachLoudness(trackId, loudness.integratedLufs(), loudness.truePeakDb());
                log.info("Track {} loudness: {} LUFS, peak {} dBTP",
                        trackId, loudness.integratedLufs(), loudness.truePeakDb());
            });
        } catch (RuntimeException ex) {
            log.debug("Could not store loudness for {}: {}", trackId, ex.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnStartup() {
        if (!analyzer.isAvailable()) {
            log.info("Loudness backfill skipped: ffmpeg not found");
            return;
        }
        if (!backfilling.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("loudness-backfill").start(() -> {
            try {
                List<Track> pending = repository.findByStatusAndLoudnessLufsIsNull(TrackStatus.READY);
                if (pending.isEmpty()) {
                    return;
                }
                log.info("Measuring loudness for {} existing track(s)", pending.size());
                for (Track track : pending) {
                    try {
                        measureAndStore(track.getId(), storage.resolveStored(track.getFilePath()));
                    } catch (RuntimeException ex) {
                        // A missing file is that track's problem, not the backfill's.
                        log.debug("Skipping loudness for {}: {}", track.getId(), ex.getMessage());
                    }
                }
                log.info("Loudness backfill complete");
            } catch (RuntimeException ex) {
                log.warn("Loudness backfill stopped: {}", ex.getMessage());
            } finally {
                backfilling.set(false);
            }
        });
    }
}
