package com.pahappa.musicplayer.api;

import com.pahappa.musicplayer.api.dto.PagedResponse;
import com.pahappa.musicplayer.api.dto.TrackResponse;
import com.pahappa.musicplayer.domain.Track;
import com.pahappa.musicplayer.domain.TrackStatus;
import com.pahappa.musicplayer.exception.ApiException;
import com.pahappa.musicplayer.service.DownloadService;
import com.pahappa.musicplayer.service.FileStorageService;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Library browsing plus the byte-serving endpoints a media player needs.
 */
@RestController
@RequestMapping("/api/v1/tracks")
public class TrackController {

    private static final Logger log = LoggerFactory.getLogger(TrackController.class);
    private static final MediaType IMAGE_JPEG = MediaType.IMAGE_JPEG;
    private static final int COPY_BUFFER_BYTES = 64 * 1024;

    private final DownloadService downloadService;
    private final FileStorageService storage;

    public TrackController(DownloadService downloadService, FileStorageService storage) {
        this.downloadService = downloadService;
        this.storage = storage;
    }

    /** Library listing. Supports paging, free-text search and status filtering. */
    @GetMapping
    public PagedResponse<TrackResponse> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) TrackStatus status) {
        return downloadService.list(page, size, q, status);
    }

    @GetMapping("/{id}")
    public TrackResponse get(@PathVariable UUID id) {
        return downloadService.get(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        downloadService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Streams audio for in-app playback.
     *
     * <p>Honours {@code Range}. This is not optional polish: ExoPlayer and
     * AVPlayer both open media with a ranged request, and without 206 support
     * they fail outright or refuse to seek.
     */
    @GetMapping("/{id}/stream")
    public ResponseEntity<StreamingResponseBody> stream(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {

        Track track = downloadService.requirePlayable(id);
        return serveAudio(track, rangeHeader, ContentDisposition.inline()
                .filename(
                        storage.buildDownloadName(track.getTitle(), track.getArtist(), track.getAudioFormat()),
                        StandardCharsets.UTF_8)
                .build());
    }

    /** Whole-file download as an attachment, with a readable filename. */
    @GetMapping("/{id}/file")
    public ResponseEntity<StreamingResponseBody> file(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {

        Track track = downloadService.requirePlayable(id);
        return serveAudio(track, rangeHeader, ContentDisposition.attachment()
                .filename(
                        storage.buildDownloadName(track.getTitle(), track.getArtist(), track.getAudioFormat()),
                        StandardCharsets.UTF_8)
                .build());
    }

    /**
     * Locally cached artwork, falling back to a redirect to YouTube's CDN when
     * the cache is cold.
     */
    @GetMapping("/{id}/cover")
    public ResponseEntity<Resource> cover(@PathVariable UUID id) {
        Track track = downloadService.requireTrack(id);

        if (track.getCoverPath() != null) {
            try {
                Path cover = storage.resolveStored(track.getCoverPath());
                return ResponseEntity.ok()
                        .contentType(IMAGE_JPEG)
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                        .body(new FileSystemResource(cover));
            } catch (RuntimeException ex) {
                log.debug("Cached cover for {} is missing, falling back to redirect", id);
            }
        }
        if (track.getThumbnailUrl() != null) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(track.getThumbnailUrl()))
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                    .build();
        }
        return ResponseEntity.notFound().build();
    }

    // --------------------------------------------------------------- helpers

    /**
     * Shared 200/206/416 handling for audio delivery.
     *
     * <p>The body is written straight to the output stream instead of going
     * through {@code ResourceRegion}. {@code ResourceRegionHttpMessageConverter}
     * advertises only {@code application/octet-stream}, so a preset
     * {@code audio/mp4} makes it throw at write time - and a 500 on a seek
     * request is exactly the failure a player surfaces as "can't play this".
     *
     * <p>Only the first range of a multi-range request is honoured; that is legal
     * per RFC 9110 and no media player actually sends multi-range.
     */
    private ResponseEntity<StreamingResponseBody> serveAudio(
            Track track, String rangeHeader, ContentDisposition disposition) {

        Path file = storage.resolveStored(track.getFilePath());
        long contentLength = sizeOf(file);
        MediaType contentType = MediaType.parseMediaType(track.getAudioFormat().contentType());

        // Audio for a given track id is stable, but a retry can replace the file,
        // so this is a day rather than the "immutable" a year.
        CacheControl cacheControl = CacheControl.maxAge(Duration.ofDays(1)).cachePublic();

        long start = 0;
        long end = contentLength - 1;
        boolean partial = false;

        if (rangeHeader != null && !rangeHeader.isBlank()) {
            List<HttpRange> ranges;
            try {
                ranges = HttpRange.parseRanges(rangeHeader);
            } catch (IllegalArgumentException ex) {
                return unsatisfiable(contentLength, contentType);
            }
            if (ranges.isEmpty()) {
                return unsatisfiable(contentLength, contentType);
            }

            HttpRange range = ranges.get(0);
            start = range.getRangeStart(contentLength);
            end = range.getRangeEnd(contentLength);

            if (start >= contentLength || start > end) {
                return unsatisfiable(contentLength, contentType);
            }
            partial = true;
        }

        long from = start;
        long count = end - start + 1;

        StreamingResponseBody body = output -> copyRange(file, from, count, output);

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(partial ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
                .contentType(contentType)
                .contentLength(count)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .cacheControl(cacheControl);

        if (partial) {
            builder.header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + contentLength);
        }
        return builder.body(body);
    }

    /** Copies exactly {@code count} bytes starting at {@code from} to the client. */
    private static void copyRange(Path file, long from, long count, OutputStream output) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        long remaining = count;

        try (InputStream in = Files.newInputStream(file, StandardOpenOption.READ)) {
            // Throws EOFException if the file is shorter than expected, which is
            // better than silently returning a short body against a promised
            // Content-Length.
            in.skipNBytes(from);

            while (remaining > 0) {
                int wanted = (int) Math.min(buffer.length, remaining);
                int read = in.read(buffer, 0, wanted);
                if (read < 0) {
                    break;
                }
                output.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    private ResponseEntity<StreamingResponseBody> unsatisfiable(long contentLength, MediaType contentType) {
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .contentType(contentType)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE, "bytes */" + contentLength)
                .build();
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException ex) {
            throw new ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "storage_error", "Could not read the stored audio file", ex);
        }
    }
}
