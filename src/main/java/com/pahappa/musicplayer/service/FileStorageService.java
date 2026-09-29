package com.pahappa.musicplayer.service;

import com.pahappa.musicplayer.config.AppProperties;
import com.pahappa.musicplayer.domain.AudioFormat;
import com.pahappa.musicplayer.exception.NotFoundException;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Owns the on-disk library layout.
 *
 * <pre>
 *   data/
 *     audio/   &lt;uuid&gt;.m4a          finished, servable files
 *     covers/  &lt;uuid&gt;.jpg          cached artwork
 *     work/    &lt;uuid&gt;/            yt-dlp scratch space, incl. .part files
 * </pre>
 *
 * <p>Only the <em>relative</em> path is persisted, so the library can be moved
 * or mounted elsewhere without rewriting rows.
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);
    private static final int MAX_NAME_LENGTH = 120;

    private final Path root;
    private final Path audioDir;
    private final Path coverDir;
    private final Path workDir;

    public FileStorageService(AppProperties properties) {
        this.root = Paths.get(properties.storage().resolvedRoot()).toAbsolutePath().normalize();
        this.audioDir = root.resolve("audio");
        this.coverDir = root.resolve("covers");
        this.workDir = root.resolve("work");
    }

    @PostConstruct
    void createLayout() {
        try {
            for (Path dir : new Path[] {root, audioDir, coverDir, workDir}) {
                Files.createDirectories(dir);
            }
            log.info("Library root: {}", root);
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot create library directories under " + root, ex);
        }
    }

    public Path root() {
        return root;
    }

    /**
     * Scratch directory for one download attempt.
     *
     * <p>Isolating each attempt means a failed run can never leave a stray
     * {@code .part} file that a later attempt would mistake for finished work.
     */
    public Path workDirectory(String trackId) {
        Path dir = workDir.resolve(safeSegment(trackId));
        try {
            Files.createDirectories(dir);
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot create work directory " + dir, ex);
        }
        return dir;
    }

    /** Destination for a completed file, always inside {@code audio/}. */
    public Path audioFile(String trackId, AudioFormat format) {
        return audioDir.resolve(safeSegment(trackId) + "." + format.extension());
    }

    public Path coverFile(String trackId) {
        return coverDir.resolve(safeSegment(trackId) + ".jpg");
    }

    /**
     * Turns a stored relative path into an absolute one, refusing anything that
     * escapes the library root.
     */
    public Path resolveStored(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new NotFoundException("Track has no stored file");
        }
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            log.warn("Rejected path traversal attempt: {}", relativePath);
            throw new NotFoundException("Track file is not available");
        }
        if (!Files.isRegularFile(resolved)) {
            throw new NotFoundException("Track file is missing from disk");
        }
        return resolved;
    }

    public String relativise(Path absolute) {
        return root.relativize(absolute.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    public long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException ex) {
            return 0L;
        }
    }

    /**
     * Human-friendly download filename, e.g.
     * {@code Rick Astley - Never Gonna Give You Up.m4a}.
     *
     * <p>YouTube titles very often already begin with the artist
     * ("Rick Astley - Never Gonna Give You Up (Official Video)"), so the artist
     * is only prefixed when the title does not already lead with it.
     */
    public String buildDownloadName(String title, String artist, AudioFormat format) {
        String cleanTitle = nullSafe(title).strip();
        String cleanArtist = nullSafe(artist).strip();

        String base;
        if (cleanArtist.isEmpty()) {
            base = cleanTitle;
        } else if (cleanTitle.toLowerCase(Locale.ROOT).startsWith(cleanArtist.toLowerCase(Locale.ROOT))) {
            base = cleanTitle;
        } else {
            base = cleanArtist + " - " + cleanTitle;
        }
        return sanitiseFileName(base) + "." + format.extension();
    }

    /** Strips characters that are illegal in Windows filenames and clamps length. */
    public static String sanitiseFileName(String raw) {
        String cleaned = nullSafe(raw)
                .replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F]", "")
                .replaceAll("\\s+", " ")
                .replaceAll("[.\\s]+$", "")
                .strip();
        if (cleaned.isBlank()) {
            cleaned = "track";
        }
        if (cleaned.length() > MAX_NAME_LENGTH) {
            cleaned = cleaned.substring(0, MAX_NAME_LENGTH).strip();
        }
        return cleaned;
    }

    /** Deletes a file, logging rather than throwing if it is already gone. */
    public void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            log.warn("Could not delete {}: {}", path, ex.getMessage());
        }
    }

    /** Removes a download attempt's scratch directory and everything in it. */
    public void purgeWorkDirectory(String trackId) {
        Path dir = workDir.resolve(safeSegment(trackId));
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> deleteQuietly(path));
        } catch (IOException ex) {
            log.warn("Could not purge {}: {}", dir, ex.getMessage());
        }
    }

    /**
     * Restricts a caller-derived path fragment to a single safe filename
     * component, so an id can never introduce a directory level.
     */
    private static String safeSegment(String value) {
        String cleaned = nullSafe(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]", "");
        return cleaned.isBlank() ? "unknown" : cleaned;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
