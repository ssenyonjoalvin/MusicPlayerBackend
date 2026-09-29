package com.pahappa.musicplayer.service;

import com.pahappa.musicplayer.config.AppProperties;
import com.pahappa.musicplayer.exception.ToolUnavailableException;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Finds the external binaries this service shells out to.
 *
 * <p>Resolution order for each tool is: explicit configuration, then a
 * well-known environment variable, then {@code PATH}, then the locations the
 * common Windows installers actually use, and finally a probe of the Python
 * interpreter. Results are cached because the answer cannot change while the
 * JVM is up.
 *
 * <p>Nothing here throws during discovery. A missing tool is a runtime condition
 * with a precise message, not a reason to refuse to boot the API - m4a downloads
 * work without ffmpeg at all.
 */
@Component
public class ToolLocator {

    private static final Logger log = LoggerFactory.getLogger(ToolLocator.class);
    private static final long PROBE_TIMEOUT_SECONDS = 10;

    private final AppProperties properties;
    private final boolean windows = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT)
            .contains("win");

    private volatile List<String> cachedYtDlpCommand;
    private volatile FfmpegLocation cachedFfmpeg;
    private volatile boolean ffmpegProbed;

    public ToolLocator(AppProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void warmUp() {
        log.info("Tool discovery: yt-dlp -> {}", describeYtDlp());
        log.info("Tool discovery: ffmpeg -> {}", ffmpegLocation());
    }

    /**
     * Where ffmpeg lives and how complete it is.
     *
     * <p>yt-dlp needs <em>both</em> {@code ffmpeg} and {@code ffprobe} to
     * post-process, so "an ffmpeg exists" is not the same as "mp3 will work".
     * Reporting the two flags separately is what lets the API explain the
     * difference instead of failing with a generic post-processing error.
     */
    public record FfmpegLocation(Path directory, boolean hasFfmpeg, boolean hasFfprobe) {

        public boolean isComplete() {
            return directory != null && hasFfmpeg && hasFfprobe;
        }

        /** True when at least one of the pair was located. */
        public boolean isPresent() {
            return directory != null && (hasFfmpeg || hasFfprobe);
        }
    }

    /**
     * The ffmpeg binary itself, including the versioned imageio-ffmpeg name.
     * Enough for analysis (loudness); MP3 conversion still needs
     * {@link #requireFfmpeg()}.
     */
    public Optional<Path> ffmpegExecutable() {
        FfmpegLocation location = ffmpegLocation();
        if (location == null || location.directory() == null || !location.hasFfmpeg()) {
            return Optional.empty();
        }
        Path plain = location.directory().resolve(executableName("ffmpeg"));
        if (Files.isRegularFile(plain)) {
            return Optional.of(plain);
        }
        try (Stream<Path> entries = Files.list(location.directory())) {
            return entries.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.startsWith("ffmpeg-win") || name.startsWith("ffmpeg-linux")
                                || name.startsWith("ffmpeg-osx") || name.startsWith("ffmpeg-macos");
                    })
                    .findFirst();
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    /** Never null; {@link FfmpegLocation#isPresent()} tells you if anything was found. */
    public FfmpegLocation ffmpegLocation() {
        FfmpegLocation cached = cachedFfmpeg;
        if (cached != null || ffmpegProbed) {
            return cached;
        }
        synchronized (this) {
            if (!ffmpegProbed) {
                cachedFfmpeg = resolveFfmpeg();
                ffmpegProbed = true;
            }
            return cachedFfmpeg;
        }
    }

    /**
     * Fails with an actionable message unless mp3 conversion is actually
     * possible. Called before any transcode so the client gets a clear
     * {@code tool_unavailable} instead of a downstream post-processing error.
     */
    public void requireFfmpeg() {
        FfmpegLocation location = ffmpegLocation();
        if (location.isComplete()) {
            return;
        }

        StringBuilder message = new StringBuilder("MP3 conversion needs ffmpeg AND ffprobe");
        if (location.isPresent()) {
            message.append("; found only ")
                    .append(location.hasFfmpeg() ? "ffmpeg" : "ffprobe")
                    .append(" in ")
                    .append(location.directory());
        } else {
            message.append(", but neither could be located");
        }
        message.append(". Install a full build (for example `winget install Gyan.FFmpeg` or ")
                .append("`choco install ffmpeg`), or point APP_TOOLS_FFMPEG_PATH at the bin ")
                .append("directory containing both executables. M4A downloads do not need ffmpeg.");

        throw new ToolUnavailableException(message.toString());
    }

    /** Snapshot for the health endpoint, so clients can see what is available. */
    public Diagnostics diagnostics() {
        FfmpegLocation ffmpeg = ffmpegLocation();
        return new Diagnostics(
                describeYtDlp(),
                ffmpeg.directory() == null ? null : ffmpeg.directory().toString(),
                ffmpeg.hasFfmpeg(),
                ffmpeg.hasFfprobe(),
                ffmpeg.isComplete(),
                windows);
    }

    public record Diagnostics(
            String ytDlp,
            String ffmpegDirectory,
            boolean hasFfmpeg,
            boolean hasFfprobe,
            boolean mp3Available,
            boolean windows) {
    }

    // ---------------------------------------------------------------- yt-dlp

    private List<String> resolveYtDlp() {
        Optional<Path> explicit = firstNonBlank(
                        properties.tools().ytDlpPath(), System.getenv("YT_DLP_PATH"), System.getenv("YT_DLP"))
                .map(ToolLocator::toPath)
                .filter(Files::isRegularFile);

        if (explicit.isPresent()) {
            log.info("Using configured yt-dlp at {}", explicit.get());
            return List.of(explicit.get().toAbsolutePath().toString());
        }

        Optional<Path> onPath = findOnPath(executableName("yt-dlp"));
        if (onPath.isPresent()) {
            log.info("Found yt-dlp on PATH at {}", onPath.get());
            return List.of(onPath.get().toAbsolutePath().toString());
        }

        // pip installs the launcher next to the interpreter.
        Optional<Path> python = findPython();
        if (python.isPresent() && python.get().getParent() != null) {
            Path launcher = python.get()
                    .getParent()
                    .resolve(windows ? "Scripts" : "bin")
                    .resolve(executableName("yt-dlp"));
            if (Files.isRegularFile(launcher)) {
                log.info("Found yt-dlp beside the interpreter at {}", launcher);
                return List.of(launcher.toAbsolutePath().toString());
            }
            // Fall back to the module form; works even without the launcher script.
            if (canRun(python.get(), List.of("-m", "yt_dlp", "--version"))) {
                log.info("Falling back to `python -m yt_dlp` using {}", python.get());
                return List.of(python.get().toAbsolutePath().toString(), "-m", "yt_dlp");
            }
        }

        throw new ToolUnavailableException(
                "yt-dlp is not installed. Install it with `pip install -U yt-dlp` or "
                        + "`winget install yt-dlp.yt-dlp`, then restart the service.");
    }

    private String describeYtDlp() {
        try {
            return String.join(" ", ytDlpCommand());
        } catch (ToolUnavailableException ex) {
            return "NOT FOUND";
        }
    }

    /**
     * Command prefix for invoking yt-dlp, e.g. {@code ["yt-dlp.exe"]} or
     * {@code ["python", "-m", "yt_dlp"]} when only the Python package exists.
     */
    public List<String> ytDlpCommand() {
        List<String> cached = cachedYtDlpCommand;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (cachedYtDlpCommand == null) {
                cachedYtDlpCommand = resolveYtDlp();
            }
            return cachedYtDlpCommand;
        }
    }

    // ---------------------------------------------------------------- ffmpeg

    private FfmpegLocation resolveFfmpeg() {
        // 1. Explicit configuration always wins.
        Optional<Path> explicit = firstNonBlank(
                        properties.tools().ffmpegPath(), System.getenv("FFMPEG_LOCATION"), System.getenv("FFMPEG_PATH"))
                .map(ToolLocator::toPath)
                .filter(Files::exists);
        if (explicit.isPresent()) {
            Path directory = Files.isDirectory(explicit.get()) ? explicit.get() : explicit.get().getParent();
            FfmpegLocation location = evaluate(directory);
            log.info("Using configured ffmpeg location {}", location);
            return location;
        }

        FfmpegLocation best = null;

        // 2. Anything already on PATH.
        Optional<Path> onPath = findOnPath(executableName("ffmpeg"));
        if (onPath.isPresent()) {
            best = better(best, evaluate(onPath.get().getParent()));
        }

        // 3. Chocolatey, Scoop and WinGet layouts.
        for (Path candidate : findFfmpegInKnownInstallDirs()) {
            best = better(best, evaluate(candidate.getParent()));
        }

        // 4. The imageio-ffmpeg wheel. Ships ffmpeg under a versioned name and
        //    no ffprobe, so this normally yields a partial location - enough for
        //    diagnostics, not enough for mp3.
        Optional<Path> viaPython = imageioFfmpeg();
        if (viaPython.isPresent()) {
            best = better(best, evaluate(viaPython.get().getParent()));
        }

        log.info("Resolved ffmpeg: {}", best);
        return best == null ? new FfmpegLocation(null, false, false) : best;
    }

    private FfmpegLocation evaluate(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return new FfmpegLocation(directory, false, false);
        }
        boolean ffmpeg = Files.isRegularFile(directory.resolve(executableName("ffmpeg"))) || hasVersionedBinary(directory, "ffmpeg");
        boolean ffprobe = Files.isRegularFile(directory.resolve(executableName("ffprobe")));
        return new FfmpegLocation(directory, ffmpeg, ffprobe);
    }

    /**
     * Recognises the versioned filenames used by the imageio-ffmpeg wheel
     * ({@code ffmpeg-win-x86_64-v7.1.exe}).
     *
     * <p>Counted so diagnostics can say "an ffmpeg exists but yt-dlp cannot use
     * it" rather than "nothing was found". {@link FfmpegLocation#isComplete()}
     * still gates mp3, because that binary lacks ffprobe and is not named the
     * way yt-dlp expects.
     */
    private static boolean hasVersionedBinary(Path directory, String base) {
        String prefix = base.toLowerCase(Locale.ROOT);
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(path -> path.getFileName().toString().toLowerCase(Locale.ROOT))
                    .anyMatch(name -> name.startsWith(prefix + "-win") || name.startsWith(prefix + "-linux"));
        } catch (IOException ex) {
            return false;
        }
    }

    /** A complete pair always beats a partial one. */
    private static FfmpegLocation better(FfmpegLocation current, FfmpegLocation candidate) {
        if (candidate == null || !candidate.isPresent()) {
            return current;
        }
        if (current == null) {
            return candidate;
        }
        return candidate.isComplete() && !current.isComplete() ? candidate : current;
    }

    private Optional<Path> imageioFfmpeg() {
        return findPython()
                .filter(python -> canRun(
                        python, List.of("-c", "import imageio_ffmpeg;print(imageio_ffmpeg.get_ffmpeg_exe())")))
                .flatMap(python -> runAndCapture(
                        python, List.of("-c", "import imageio_ffmpeg;print(imageio_ffmpeg.get_ffmpeg_exe())")))
                .map(ToolLocator::toPath)
                .filter(Files::isRegularFile);
    }

    /** Chocolatey, WinGet and manual "drop it in a folder" layouts. */
    private List<Path> findFfmpegInKnownInstallDirs() {
        if (!windows) {
            return List.of();
        }
        List<Path> roots = List.of(
                Paths.get("C:\\ProgramData\\chocolatey\\lib\\ffmpeg\\tools"),
                Paths.get(System.getProperty("user.home"), "scoop", "apps", "ffmpeg", "current", "bin"),
                Paths.get(
                        System.getenv().getOrDefault("LOCALAPPDATA", ""),
                        "Microsoft",
                        "WinGet",
                        "Packages"));

        List<Path> hits = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root, 4)) {
                // Any ffmpeg-named binary counts, including the versioned
                // imageio-style names.
                walk.filter(Files::isRegularFile)
                        .filter(path -> {
                            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                            return name.equals(executableName("ffmpeg").toLowerCase(Locale.ROOT))
                                    || name.startsWith("ffmpeg-win");
                        })
                        .forEach(hits::add);
            } catch (IOException | UncheckedIOException ex) {
                log.debug("Could not scan {} for ffmpeg: {}", root, ex.getMessage());
            }
        }
        return hits;
    }

    // ------------------------------------------------------------- utilities

    private Optional<Path> findPython() {
        Optional<Path> configured = firstNonBlank(properties.tools().pythonExecutable(), System.getenv("PYTHON"))
                .map(ToolLocator::toPath)
                .filter(Files::isRegularFile);
        if (configured.isPresent()) {
            return configured;
        }

        Optional<Path> onPath = findOnPath(executableName("python"));
        if (onPath.isPresent()) {
            return onPath;
        }
        // `py` is the Windows launcher and is often present without python.exe on PATH.
        return findOnPath("py.exe");
    }

    /** Scans {@code PATH} for an executable, following PATHEXT on Windows. */
    private static Optional<Path> findOnPath(String executable) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return Optional.empty();
        }
        for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String extension : extensionsFor(executable)) {
                Path candidate = Paths.get(dir).resolve(executable + extension);
                if (Files.isRegularFile(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    private static List<String> extensionsFor(String executable) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return List.of("");
        }
        if (executable.endsWith(".exe")) {
            return List.of("");
        }
        String pathExt = System.getenv("PATHEXT");
        List<String> extensions = new ArrayList<>();
        if (pathExt != null) {
            for (String ext : pathExt.split(";")) {
                if (!ext.isBlank()) {
                    extensions.add(ext.toLowerCase(Locale.ROOT));
                }
            }
        }
        if (extensions.isEmpty()) {
            extensions.add(".exe");
            extensions.add(".cmd");
            extensions.add(".bat");
        }
        return extensions;
    }

    private String executableName(String base) {
        return windows ? base + ".exe" : base;
    }

    private static boolean canRun(Path command, List<String> args) {
        return runAndCapture(command, args).isPresent();
    }

    /** Runs a probe command and returns trimmed stdout, empty on any failure. */
    private static Optional<String> runAndCapture(Path command, List<String> args) {
        List<String> full = new ArrayList<>();
        full.add(command.toAbsolutePath().toString());
        full.addAll(args);
        try {
            Process process = new ProcessBuilder(full).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes()).strip();
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            return process.exitValue() == 0 && !output.isBlank()
                    ? Optional.of(output.lines().findFirst().orElse(""))
                    : Optional.empty();
        } catch (IOException ex) {
            return Optional.empty();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private static Optional<String> firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    private static Path toPath(String value) {
        return Paths.get(value);
    }
}
