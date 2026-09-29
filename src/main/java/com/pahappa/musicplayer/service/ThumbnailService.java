package com.pahappa.musicplayer.service;

import com.pahappa.musicplayer.domain.Track;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Caches thumbnail artwork next to the audio.
 *
 * <p>Copying the image locally keeps the library genuinely offline-first: once a
 * track is downloaded, list art renders without reaching YouTube again. Entirely
 * best-effort - a missing cover must never fail a download.
 */
@Service
public class ThumbnailService {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final long MAX_BYTES = 8L * 1024 * 1024;

    private final FileStorageService storage;
    private final HttpClient httpClient;

    public ThumbnailService(FileStorageService storage) {
        this.storage = storage;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Downloads the artwork and returns its library-relative path, or null if it
     * could not be fetched. Never throws.
     */
    public String cacheCover(Track track) {
        String source = track.getThumbnailUrl();
        if (source == null || source.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(source);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                return null;
            }

            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(TIMEOUT)
                    .header("User-Agent", "music-player/1.0")
                    .GET()
                    .build();

            HttpResponse<Path> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofFile(downloadTarget(track)));

            if (response.statusCode() != 200) {
                Files.deleteIfExists(response.body());
                return null;
            }
            if (sizeOf(response.body()) > MAX_BYTES) {
                log.warn("Discarding oversized cover for track {}", track.getId());
                Files.deleteIfExists(response.body());
                return null;
            }

            return storage.relativise(response.body());
        } catch (IOException | RuntimeException ex) {
            log.debug("Cover download skipped for track {}: {}", track.getId(), ex.getMessage());
            return null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private Path downloadTarget(Track track) {
        Path target = storage.coverFile(track.getId().toString());
        try {
            Files.deleteIfExists(target);
            return target;
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot prepare cover file " + target, ex);
        }
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ex) {
            return 0L;
        }
    }

    /** Removes a cached cover. Best-effort. */
    public void evict(Track track) {
        storage.deleteQuietly(storage.coverFile(track.getId().toString()));
    }
}
