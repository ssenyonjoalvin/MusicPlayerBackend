package com.pahappa.musicplayer.service;

import com.pahappa.musicplayer.config.AppProperties;
import com.pahappa.musicplayer.domain.AudioFormat;
import com.pahappa.musicplayer.domain.Track;
import com.pahappa.musicplayer.exception.ApiException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Runs the actual audio download.
 *
 * <p>Two format strategies, chosen for how they fail:
 * <ul>
 *   <li><b>m4a</b> asks for {@code bestaudio[ext=m4a]} and writes it straight
 *       out. No ffmpeg, no transcode, no quality loss - a byte-for-byte copy.</li>
 *   <li><b>mp3</b> pulls the best audio stream and lets ffmpeg re-encode, then
 *       embeds ID3 tags. Slower, but playable on legacy hardware.</li>
 * </ul>
 *
 * <p>Progress arrives on stdout as sentinel-prefixed lines rather than the
 * default carriage-return redraw, which makes parsing deterministic instead of
 * screen-scraping.
 */
@Service
public class YtDlpDownloader {

    private static final Logger log = LoggerFactory.getLogger(YtDlpDownloader.class);
    private static final String PROGRESS_SENTINEL = "@P@";
    private static final long PROGRESS_MIN_INTERVAL_MS = 500;

    private final ToolLocator tools;
    private final ProcessRunner processRunner;
    private final FileStorageService storage;
    private final AppProperties properties;

    public YtDlpDownloader(
            ToolLocator tools,
            ProcessRunner processRunner,
            FileStorageService storage,
            AppProperties properties) {
        this.tools = tools;
        this.processRunner = processRunner;
        this.storage = storage;
        this.properties = properties;
    }

    /** A snapshot of transfer state, safe to persist or serialise. */
    public record TransferProgress(
            double percent, Long downloadedBytes, Long totalBytes, Double speedBytesPerSecond, Long etaSeconds) {
    }

    /** Where the finished audio ended up. */
    public record DownloadResult(Path file, long sizeBytes) {
    }

    public DownloadResult download(
            Track track, String canonicalUrl, Consumer<TransferProgress> onProgress) {

        AudioFormat format = track.getAudioFormat();
        if (format.requiresTranscode()) {
            tools.requireFfmpeg();
        }

        Path workDir = storage.workDirectory(track.getId().toString());
        List<String> command = buildCommand(track, canonicalUrl, workDir, format);

        ProgressParser parser = new ProgressParser(onProgress);

        ProcessRunner.Result result;
        try {
            result = processRunner.run(command, properties.download().timeout(), track.getId().toString(), parser::accept);
        } catch (ProcessRunner.ProcessLaunchException ex) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "tool_unavailable",
                    "yt-dlp could not be started. Check that it is installed and on PATH.",
                    ex);
        } catch (ProcessRunner.ProcessCancelledException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "cancelled", "Download was cancelled", ex);
        } catch (ProcessRunner.ProcessTimeoutException ex) {
            throw new ApiException(
                    HttpStatus.GATEWAY_TIMEOUT,
                    "download_timeout",
                    "Download timed out. Check your connection and retry.");
        }

        parser.flush();

        if (!result.success()) {
            throw YtDlpErrors.map(result.lastErrorLine().orElse(""), result.exitCode());
        }

        Path produced = locateOutput(workDir, format)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.BAD_GATEWAY,
                        "download_failed",
                        "yt-dlp reported success but no audio file was produced."));

        return promote(produced, storage.audioFile(track.getId().toString(), format));
    }

    private List<String> buildCommand(
            Track track, String canonicalUrl, Path workDir, AudioFormat format) {

        List<String> command = new ArrayList<>(tools.ytDlpCommand());
        command.addAll(List.of(
                "--ignore-config",
                "--no-playlist",
                "--newline",
                "--no-overwrites",
                "--socket-timeout", "20",
                "--retries", String.valueOf(properties.download().retries()),
                "--fragment-retries", String.valueOf(properties.download().retries()),
                "--concurrent-fragments", "4",
                "--progress-template",
                "download:" + PROGRESS_SENTINEL + "%(progress.status)s|"
                        + "%(progress.downloaded_bytes)s|"
                        + "%(progress.total_bytes)s|"
                        + "%(progress.total_bytes_estimate)s|"
                        + "%(progress.speed)s|"
                        + "%(progress.eta)s",
                "--output", workDir.resolve(track.getId() + ".%(ext)s").toString()));

        if (format.requiresTranscode()) {
            // requireFfmpeg() has already proven this is non-null by this point.
            Path ffmpegDirectory = tools.ffmpegLocation().directory();
            if (ffmpegDirectory != null) {
                command.addAll(List.of("--ffmpeg-location", ffmpegDirectory.toString()));
            }
            command.addAll(List.of(
                    "-f", "bestaudio/best",
                    "-x",
                    "--audio-format", "mp3",
                    "--audio-quality", "0",
                    "--embed-metadata"));
        } else {
            command.addAll(List.of("-f", "bestaudio[ext=m4a]/bestaudio"));
        }

        command.add(canonicalUrl);
        return command;
    }

    /**
     * Finds the real output file.
     *
     * <p>Rather than trusting a predicted filename, this scans the work
     * directory: yt-dlp may keep a different container than requested, and
     * {@code .part} / {@code .ytdl} scratch files must never be mistaken for a
     * finished track.
     */
    private static Optional<Path> locateOutput(Path workDir, AudioFormat format) {
        String expectedSuffix = "." + format.extension();
        try (var entries = Files.list(workDir)) {
            // Prefer the container we asked for; fall back to the largest finished
            // audio file if yt-dlp handed back something else.
            return entries.filter(Files::isRegularFile)
                    .filter(YtDlpDownloader::isFinishedAudio)
                    .max(Comparator.comparing((Path path) -> hasExtension(path, expectedSuffix))
                            .thenComparingLong(YtDlpDownloader::sizeQuietly));
        } catch (IOException ex) {
            log.warn("Could not list {}: {}", workDir, ex.getMessage());
            return Optional.empty();
        }
    }

    private static boolean hasExtension(Path path, String suffix) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix);
    }

    private static boolean isFinishedAudio(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".part") || name.endsWith(".ytdl") || name.endsWith(".temp")) {
            return false;
        }
        return name.endsWith(".m4a")
                || name.endsWith(".mp3")
                || name.endsWith(".m4b")
                || name.endsWith(".webm")
                || name.endsWith(".opus")
                || name.endsWith(".ogg")
                || name.endsWith(".aac");
    }

    private static long sizeQuietly(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ex) {
            return 0L;
        }
    }

    /** Moves the finished file into the servable library, replacing any previous copy. */
    private DownloadResult promote(Path source, Path destination) {
        try {
            Files.createDirectories(destination.getParent());
            try {
                Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.FileAlreadyExistsException ex) {
                // Retry across filesystems (e.g. library on another volume).
                Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(source);
            }
            return new DownloadResult(destination, Files.size(destination));
        } catch (IOException ex) {
            throw new ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "storage_error",
                    "Downloaded the audio but could not file it into the library: " + ex.getMessage(),
                    ex);
        }
    }

    /**
     * Parses the sentinel lines and rate-limits callbacks.
     *
     * <p>yt-dlp can emit progress many times per second. Persisting every tick
     * would hammer the database for no benefit, so updates are throttled to
     * {@link #PROGRESS_MIN_INTERVAL_MS} - while still always delivering the
     * final state.
     */
    private static final class ProgressParser {

        private final Consumer<TransferProgress> sink;
        private TransferProgress latest;
        private long lastEmitAt;

        ProgressParser(Consumer<TransferProgress> sink) {
            this.sink = sink;
        }

        void accept(String line) {
            if (line == null || !line.startsWith(PROGRESS_SENTINEL)) {
                return;
            }
            TransferProgress parsed = parseProgressLine(line.substring(PROGRESS_SENTINEL.length()));
            if (parsed == null) {
                return;
            }
            latest = parsed;
            long now = System.currentTimeMillis();
            if (now - lastEmitAt >= PROGRESS_MIN_INTERVAL_MS) {
                lastEmitAt = now;
                sink.accept(parsed);
            }
        }

        /** Emits the last known state, bypassing the throttle. */
        void flush() {
            if (latest != null) {
                sink.accept(latest);
            }
        }
    }

    /**
     * Parses {@code status|downloaded|total|totalEstimate|speed|eta}.
     *
     * <p>yt-dlp prints {@code NA} for unknown values, most often
     * {@code total_bytes} on chunked transfers, where only the running estimate
     * is meaningful.
     */
    static TransferProgress parseProgressLine(String payload) {
        String[] parts = payload.split("\\|", -1);
        if (parts.length < 4) {
            return null;
        }

        long downloaded = parseLong(parts[1]);
        long total = parseLong(parts[2]);
        long estimate = parseLong(parts[3]);
        double speed = parseDouble(parts.length > 4 ? parts[4] : null);
        long eta = parseLong(parts.length > 5 ? parts[5] : null);

        // Written as if/else rather than a ternary on purpose: mixing a primitive
        // long with a null branch makes the conditional a *numeric* conditional
        // expression of type long, which silently unboxes the null and throws an
        // NPE exactly when YouTube omits a byte count.
        Long totalBytes = null;
        if (total > 0) {
            totalBytes = total;
        } else if (estimate > 0) {
            totalBytes = estimate;
        }

        double percent = 0d;
        if (totalBytes != null && downloaded > 0) {
            percent = Math.min(100d, downloaded * 100d / totalBytes);
        } else if ("finished".equalsIgnoreCase(parts[0].strip())) {
            percent = 100d;
        }

        return new TransferProgress(
                Math.round(percent * 100d) / 100d,
                downloaded > 0 ? Long.valueOf(downloaded) : null,
                totalBytes,
                speed > 0 ? Double.valueOf(speed) : null,
                eta >= 0 ? Long.valueOf(eta) : null);
    }

    private static long parseLong(String raw) {
        if (raw == null) {
            return -1L;
        }
        String value = raw.strip();
        if (value.isEmpty() || "NA".equalsIgnoreCase(value)) {
            return -1L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }

    private static double parseDouble(String raw) {
        if (raw == null) {
            return -1d;
        }
        String value = raw.strip();
        if (value.isEmpty() || "NA".equalsIgnoreCase(value)) {
            return -1d;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return -1d;
        }
    }
}
