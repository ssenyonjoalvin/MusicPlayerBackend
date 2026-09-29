package com.pahappa.musicplayer.service;

import com.pahappa.musicplayer.domain.Track;
import com.pahappa.musicplayer.domain.TrackStatus;
import com.pahappa.musicplayer.repo.TrackRepository;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Short, self-contained transactions for the download worker's state changes.
 *
 * <p>Exists as its own bean so each transition gets a real Spring proxy and a
 * real transaction boundary. The alternative - annotating the long-running
 * download method - would hold a database transaction open for the entire
 * transfer, which is exactly what we must not do.
 */
@Service
public class TrackStateWriter {

    private static final Logger log = LoggerFactory.getLogger(TrackStateWriter.class);

    private final TrackRepository repository;

    public TrackStateWriter(TrackRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Track save(Track track) {
        return repository.save(track);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Track> markDownloading(UUID trackId) {
        return repository.findById(trackId).map(track -> {
            track.markDownloading();
            return repository.save(track);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Track> markReady(UUID trackId, Path file, long sizeBytes, String relativePath) {
        return repository.findById(trackId).map(track -> {
            track.markReady(relativePath, sizeBytes);
            Track saved = repository.save(track);
            log.info("Track {} ready: {} ({} bytes)", trackId, file.getFileName(), sizeBytes);
            return saved;
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Track> markFailed(UUID trackId, String message) {
        return repository.findById(trackId).map(track -> {
            track.markFailed(message);
            log.warn("Track {} failed: {}", trackId, message);
            return repository.save(track);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Track> attachCover(UUID trackId, String relativePath) {
        if (relativePath == null) {
            return Optional.empty();
        }
        return repository.findById(trackId).map(track -> {
            track.setCoverPath(relativePath);
            return repository.save(track);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Track> attachLoudness(UUID trackId, double loudnessLufs, Double truePeakDb) {
        return repository.findById(trackId).map(track -> {
            track.setLoudnessLufs(loudnessLufs);
            track.setTruePeakDb(truePeakDb);
            return repository.save(track);
        });
    }

    @Transactional(readOnly = true)
    public Optional<Track> find(UUID trackId) {
        return repository.findById(trackId);
    }

    @Transactional(readOnly = true)
    public Optional<Track> findByVideoId(String videoId) {
        return repository.findByYoutubeVideoId(videoId);
    }

    /**
     * Persists a newly accepted track.
     *
     * <p>Its own short transaction so the caller can resolve metadata - which
     * shells out to yt-dlp and can take tens of seconds - <em>without</em>
     * holding a pooled database connection for the duration. The unique
     * constraint on {@code youtube_video_id} is what makes concurrent submits of
     * the same link safe.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Track createQueued(
            String videoId,
            String canonicalUrl,
            MetadataService.TrackMetadata metadata,
            com.pahappa.musicplayer.domain.AudioFormat format) {

        Track track = new Track();
        track.setYoutubeVideoId(videoId);
        track.setSourceUrl(canonicalUrl);
        track.setTitle(metadata.title());
        track.setArtist(metadata.artist());
        track.setAlbum(metadata.album());
        track.setDurationSeconds(metadata.durationSeconds());
        track.setThumbnailUrl(metadata.thumbnailUrl());
        track.setAudioFormat(format);
        track.setStatus(TrackStatus.QUEUED);
        return repository.save(track);
    }

    /**
     * Startup reconciliation.
     *
     * <p>A job can only be {@code QUEUED} or {@code DOWNLOADING} if the previous
     * JVM died mid-flight, because a clean shutdown leaves nothing in flight.
     * Those rows are marked failed so the UI never shows a download that will
     * never progress and the user is offered a retry.
     */
    @Transactional
    public int reconcileInterrupted() {
        List<Track> stranded = repository.findByStatusIn(List.of(TrackStatus.QUEUED, TrackStatus.DOWNLOADING));
        stranded.forEach(track -> track.markFailed("Interrupted by a server restart. Tap retry to resume."));
        repository.saveAll(stranded);
        return stranded.size();
    }
}
