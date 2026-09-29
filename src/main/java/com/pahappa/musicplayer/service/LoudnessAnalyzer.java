package com.pahappa.musicplayer.service;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Measures how loud a finished file is, so clients can play every track at the
 * same perceived volume (what Spotify calls "normalize volume").
 *
 * <p>Uses ffmpeg's EBU R128 meter, the broadcast standard every streaming
 * service normalises against. Two numbers come out:
 * <ul>
 *   <li><b>integrated loudness</b> (LUFS) - the whole track's perceived
 *       loudness, which is what a client compares against its target;</li>
 *   <li><b>true peak</b> (dBTP) - the loudest inter-sample peak, which caps
 *       how far a quiet track may be turned <em>up</em> without clipping.</li>
 * </ul>
 *
 * <p>The audio file is never modified. Storing a measurement instead of
 * re-encoding keeps the download lossless and lets each client pick its own
 * target level.
 *
 * <p>Only {@code ffmpeg} is needed - not {@code ffprobe} - so this works with
 * the partial imageio-ffmpeg install that cannot do MP3 conversion.
 */
@Component
public class LoudnessAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(LoudnessAnalyzer.class);

    /** A full decode runs at ~80x realtime; this is generous even for an hour-long mix. */
    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    /** EBU R128's absolute gate: anything at or below this is silence, not a level. */
    private static final double SILENCE_LUFS = -70.0;

    private static final Pattern INTEGRATED = Pattern.compile("^\\s*I:\\s*(-?\\d+(?:\\.\\d+)?)\\s*LUFS");
    private static final Pattern PEAK = Pattern.compile("^\\s*Peak:\\s*(-?(?:\\d+(?:\\.\\d+)?|inf))\\s*dBFS");

    private final ToolLocator tools;
    private final ProcessRunner processes;

    public LoudnessAnalyzer(ToolLocator tools, ProcessRunner processes) {
        this.tools = tools;
        this.processes = processes;
    }

    /** Loudness of one file. {@code truePeakDb} may be null if ffmpeg did not report it. */
    public record Loudness(double integratedLufs, Double truePeakDb) {
    }

    /** False when no ffmpeg binary exists, so callers can skip work quietly. */
    public boolean isAvailable() {
        return tools.ffmpegExecutable().isPresent();
    }

    /**
     * Measures {@code file}, or returns empty when ffmpeg is missing, fails, or
     * the file is silent. Never throws: loudness is an enhancement, and must
     * not turn a good download into a failed one.
     */
    public Optional<Loudness> measure(Path file) {
        Optional<Path> ffmpeg = tools.ffmpegExecutable();
        if (ffmpeg.isEmpty()) {
            return Optional.empty();
        }
        List<String> command = List.of(
                ffmpeg.get().toAbsolutePath().toString(),
                "-hide_banner",
                "-nostats",
                "-i", file.toAbsolutePath().toString(),
                "-vn",
                // framelog=verbose keeps the ten-per-second momentary readings
                // out of stderr, leaving just the summary block to parse.
                "-af", "ebur128=peak=true:framelog=verbose",
                "-f", "null",
                "-");
        try {
            ProcessRunner.Result result = processes.run(command, TIMEOUT, null);
            if (!result.success()) {
                log.debug("Loudness scan of {} failed: {}", file.getFileName(), result.lastErrorLine().orElse("?"));
                return Optional.empty();
            }
            return parseSummary(result.stderrTail());
        } catch (RuntimeException ex) {
            log.debug("Loudness scan of {} failed: {}", file.getFileName(), ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Reads the "Summary:" block ffmpeg's ebur128 filter prints at exit.
     * Visible for tests.
     */
    static Optional<Loudness> parseSummary(String stderr) {
        if (stderr == null) {
            return Optional.empty();
        }
        // The summary is the last block; earlier matching lines (if any) are
        // per-frame logs, so the last occurrence wins.
        Double integrated = null;
        Double peak = null;
        for (String line : stderr.lines().toList()) {
            Matcher i = INTEGRATED.matcher(line);
            if (i.find()) {
                integrated = Double.parseDouble(i.group(1));
                continue;
            }
            Matcher p = PEAK.matcher(line);
            if (p.find()) {
                String raw = p.group(1);
                peak = raw.endsWith("inf") ? null : Double.parseDouble(raw);
            }
        }
        if (integrated == null || integrated <= SILENCE_LUFS) {
            return Optional.empty();
        }
        return Optional.of(new Loudness(integrated, peak));
    }
}
