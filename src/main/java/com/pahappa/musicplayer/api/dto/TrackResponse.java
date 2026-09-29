package com.pahappa.musicplayer.api.dto;

import com.pahappa.musicplayer.domain.Track;
import com.pahappa.musicplayer.domain.TrackStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Client-facing view of a track.
 *
 * <p>Link fields are returned as <em>relative</em> paths on purpose: the API
 * may sit behind a proxy, a LAN IP or a tunnel, and baking a host into stored
 * rows breaks the moment that changes.
 */
public record TrackResponse(
        UUID id,
        String youtubeVideoId,
        String sourceUrl,
        String title,
        String artist,
        String album,
        Integer durationSeconds,
        String thumbnailUrl,
        String format,
        String status,
        Progress progress,
        Long fileSizeBytes,
        String errorMessage,
        String streamUrl,
        String fileUrl,
        String coverUrl,
        Double loudnessLufs,
        Double truePeakDb,
        Instant createdAt,
        Instant updatedAt) {

    /** Live transfer counters; only meaningful while status is DOWNLOADING. */
    public record Progress(
            double percent,
            Long downloadedBytes,
            Long totalBytes,
            Double speedBytesPerSecond,
            Long etaSeconds) {

        static Progress of(Track track) {
            return new Progress(
                    round(track.getProgressPercent()),
                    track.getDownloadedBytes(),
                    track.getTotalBytes(),
                    track.getSpeedBytesPerSecond() == null
                            ? null
                            : round(track.getSpeedBytesPerSecond()),
                    track.getEtaSeconds());
        }
    }

    /**
     * Returns a copy carrying live in-memory progress, which is fresher than the
     * persisted row because progress ticks are deliberately not written to the
     * database.
     */
    public TrackResponse withProgress(Progress live) {
        return live == null ? this : new TrackResponse(
                id,
                youtubeVideoId,
                sourceUrl,
                title,
                artist,
                album,
                durationSeconds,
                thumbnailUrl,
                format,
                status,
                live,
                fileSizeBytes,
                errorMessage,
                streamUrl,
                fileUrl,
                coverUrl,
                loudnessLufs,
                truePeakDb,
                createdAt,
                updatedAt);
    }

    public static TrackResponse from(Track track) {
        UUID id = track.getId();
        boolean ready = track.getStatus() == TrackStatus.READY;
        String base = "/api/v1/tracks/" + id;

        return new TrackResponse(
                id,
                track.getYoutubeVideoId(),
                track.getSourceUrl(),
                track.getTitle(),
                track.getArtist(),
                track.getAlbum(),
                track.getDurationSeconds(),
                track.getThumbnailUrl(),
                track.getAudioFormat().extension(),
                track.getStatus().name(),
                Progress.of(track),
                track.getFileSizeBytes(),
                track.getErrorMessage(),
                ready ? base + "/stream" : null,
                ready ? base + "/file" : null,
                coverUrlFor(track, base),
                track.getLoudnessLufs(),
                track.getTruePeakDb(),
                track.getCreatedAt(),
                track.getUpdatedAt());
    }

    /**
     * Prefers the locally cached artwork; falls back to YouTube's CDN so a
     * client still has something to render before the cover finishes caching.
     */
    private static String coverUrlFor(Track track, String base) {
        if (track.getCoverPath() != null) {
            return base + "/cover";
        }
        return track.getThumbnailUrl();
    }

    private static double round(double value) {
        return Math.round(value * 100d) / 100d;
    }
}
