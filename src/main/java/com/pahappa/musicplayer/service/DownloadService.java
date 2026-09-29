package com.pahappa.musicplayer.service;

import com.pahappa.musicplayer.api.dto.PagedResponse;
import com.pahappa.musicplayer.api.dto.TrackResponse;
import com.pahappa.musicplayer.domain.AudioFormat;
import com.pahappa.musicplayer.domain.Track;
import com.pahappa.musicplayer.domain.TrackStatus;
import com.pahappa.musicplayer.exception.ApiException;
import com.pahappa.musicplayer.exception.NotFoundException;
import com.pahappa.musicplayer.exception.QueueFullException;
import com.pahappa.musicplayer.repo.TrackRepository;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for submitting, inspecting and retiring tracks.
 *
 * <p>Submitting is <em>idempotent per video id</em>: pasting a link that is
 * already queued, downloading, or in the library returns the existing track
 * rather than creating a duplicate. That matters because mobile share sheets
 * fire repeatedly and users double-tap.
 */
@Service
public class DownloadService {

    private static final Logger log = LoggerFactory.getLogger(DownloadService.class);
    private static final int MAX_PAGE_SIZE = 100;

    private final TrackRepository repository;
    private final YouTubeLinkParser linkParser;
    private final MetadataService metadataService;
    private final DownloadProgressRegistry progress;
    private final ProcessRunner processRunner;
    private final FileStorageService storage;
    private final ThumbnailService thumbnails;
    private final TrackStateWriter stateWriter;
    private final ApplicationEventPublisher events;

    public DownloadService(
            TrackRepository repository,
            YouTubeLinkParser linkParser,
            MetadataService metadataService,
            DownloadProgressRegistry progress,
            ProcessRunner processRunner,
            FileStorageService storage,
            ThumbnailService thumbnails,
            TrackStateWriter stateWriter,
            ApplicationEventPublisher events) {
        this.repository = repository;
        this.linkParser = linkParser;
        this.metadataService = metadataService;
        this.progress = progress;
        this.processRunner = processRunner;
        this.storage = storage;
        this.thumbnails = thumbnails;
        this.stateWriter = stateWriter;
        this.events = events;
    }

    // ------------------------------------------------------------- commands

    /**
     * Accepts a share link and ensures a download exists for it.
     *
     * <p>Metadata is resolved synchronously so the response already carries a
     * real title and artist, which is what the client renders in its list.
     *
     * <p>Deliberately <em>not</em> transactional. Wrapping this in one
     * transaction would pin a pooled connection for the whole yt-dlp round trip,
     * which is tens of seconds; the individual persistence steps carry their own
     * short transactions instead.
     *
     * @return the track, plus a flag telling the caller whether this call
     *         actually started new work (useful for choosing 201 vs 200)
     */
    public Submission submit(String rawUrl, AudioFormat format) {
        YouTubeLinkParser.ParsedLink link = linkParser.parse(rawUrl);

        Optional<Track> existing = stateWriter.findByVideoId(link.videoId());
        if (existing.isPresent()) {
            Track track = existing.get();
            if (track.getStatus() == TrackStatus.FAILED) {
                // Re-pasting a link is an implicit retry.
                track.resetForRetry();
                dispatch(stateWriter.save(track));
                return new Submission(track, true);
            }
            return new Submission(track, false);
        }

        // No transaction is held here; this is the slow external call.
        MetadataService.TrackMetadata metadata = metadataService.fetch(link.canonicalUrl());

        Track saved;
        try {
            saved = stateWriter.createQueued(metadata.videoId(), link.canonicalUrl(), metadata, format);
        } catch (DataIntegrityViolationException ex) {
            // Two clients submitted the same link at the same instant. The
            // unique constraint did its job; hand back the winner.
            log.info("Concurrent submit for video {}; returning existing track", metadata.videoId());
            return new Submission(
                    stateWriter
                            .findByVideoId(metadata.videoId())
                            .orElseThrow(() ->
                                    new IllegalStateException("Track vanished after a duplicate submit", ex)),
                    false);
        }

        dispatch(saved);
        return new Submission(saved, true);
    }

    /** Result of a submit call. */
    public record Submission(Track track, boolean started) {
    }

    /**
     * Re-queues a failed track.
     *
     * <p>The audio format is reset to the requested one, so a client can switch
     * a track from a failed mp3 attempt to m4a via the retry endpoint.
     */
    @Transactional
    public TrackResponse retry(UUID trackId, AudioFormat format) {
        Track track = requireTrack(trackId);
        if (track.getStatus() == TrackStatus.DOWNLOADING || track.getStatus() == TrackStatus.QUEUED) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "already_downloading", "This track is already downloading.");
        }
        storage.purgeWorkDirectory(trackId.toString());
        track.resetForRetry();
        if (format != null) {
            track.setAudioFormat(format);
        }
        Track saved = repository.save(track);
        dispatch(saved);
        return toResponse(saved);
    }

    /**
     * Kills an in-flight yt-dlp process. The worker observes the cancellation
     * and records the failure, so the client sees a consistent terminal state.
     */
    @Transactional(readOnly = true)
    public boolean cancel(UUID trackId) {
        requireTrack(trackId);
        return processRunner.cancel(trackId.toString());
    }

    /**
     * Removes a track and its files.
     *
     * <p>An active download is cancelled first: deleting the row while yt-dlp is
     * still writing would leave an orphaned file on disk.
     */
    @Transactional
    public void delete(UUID trackId) {
        Track track = requireTrack(trackId);
        if (track.getStatus() == TrackStatus.DOWNLOADING || track.getStatus() == TrackStatus.QUEUED) {
            processRunner.cancel(trackId.toString());
        }
        if (track.getFilePath() != null) {
            storage.deleteQuietly(storage.resolveStored(track.getFilePath()));
        }
        thumbnails.evict(track);
        storage.purgeWorkDirectory(trackId.toString());
        progress.finish(trackId);
        repository.delete(track);
        log.info("Deleted track {}", trackId);
    }

    // -------------------------------------------------------------- queries

    @Transactional(readOnly = true)
    public TrackResponse get(UUID trackId) {
        return toResponse(requireTrack(trackId));
    }

    /**
     * Lists the library newest-first, optionally filtered to a status or a
     * free-text query.
     */
    @Transactional(readOnly = true)
    public PagedResponse<TrackResponse> list(Integer page, Integer size, String query, TrackStatus status) {
        Pageable pageable = PageRequest.of(
                Math.max(0, page == null ? 0 : page),
                Math.min(Math.max(1, size == null ? 20 : size), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<Track> results;
        boolean hasQuery = query != null && !query.isBlank();

        if (hasQuery) {
            results = repository.search(likePattern(query), pageable);
        } else if (status != null) {
            results = repository.findByStatusOrderByCreatedAtDesc(status, pageable);
        } else {
            results = repository.findAll(pageable);
        }

        return PagedResponse.from(results, this::toResponse);
    }

    /** Resolves a playable track, or explains why it is not playable. */
    @Transactional(readOnly = true)
    public Track requirePlayable(UUID trackId) {
        Track track = requireTrack(trackId);
        if (!track.getStatus().isPlayable() || track.getFilePath() == null) {
            throw new NotFoundException("Track " + trackId + " is not ready to play (status="
                    + track.getStatus() + ")");
        }
        return track;
    }

    @Transactional(readOnly = true)
    public Track requireTrack(UUID trackId) {
        return repository.findById(trackId).orElseThrow(() -> NotFoundException.track(trackId));
    }

    public long countByStatus(TrackStatus status) {
        return repository.countByStatus(status);
    }

    // --------------------------------------------------------------- helpers

    /**
     * Queues the download. Runs inside the caller's transaction, so the event is
     * only delivered once that transaction commits.
     */
    private void dispatch(Track track) {
        try {
            events.publishEvent(new DownloadRequestedEvent(track.getId()));
        } catch (RuntimeException ex) {
            // The executor is saturated; leave an honest, actionable state.
            log.warn("Could not queue download for track {}: {}", track.getId(), ex.getMessage());
            track.markFailed("The download queue is full. Try again shortly.");
            stateWriter.save(track);
            throw new QueueFullException("The download queue is full. Try again in a moment.");
        }
    }

    /**
     * Merges live progress over the persisted row.
     *
     * <p>Only for a track that is genuinely downloading, otherwise a stale
     * in-memory entry could resurrect progress on a deleted track.
     */
    private TrackResponse toResponse(Track track) {
        TrackResponse response = TrackResponse.from(track);
        if (track.getStatus() != TrackStatus.DOWNLOADING) {
            return response;
        }
        YtDlpDownloader.TransferProgress live = progress.get(track.getId());
        if (live == null) {
            return response;
        }
        return response.withProgress(new TrackResponse.Progress(
                live.percent(),
                live.downloadedBytes(),
                live.totalBytes(),
                live.speedBytesPerSecond(),
                live.etaSeconds()));
    }

    /** Escapes LIKE wildcards so a query containing % or _ cannot widen the match. */
    private static String likePattern(String query) {
        String escaped = query.strip()
                .toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    /** Startup hook: any job left in flight belonged to a previous process. */
    @Transactional
    public int reconcileOnStartup() {
        int stranded = stateWriter.reconcileInterrupted();
        if (stranded > 0) {
            log.warn("Marked {} interrupted download(s) as failed on startup", stranded);
        }
        return stranded;
    }
}
