package com.pahappa.musicplayer.service;

import com.pahappa.musicplayer.config.AppProperties;
import com.pahappa.musicplayer.exception.ApiException;
import com.pahappa.musicplayer.exception.NotFoundException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Reads track metadata by asking yt-dlp to print a fixed set of fields.
 *
 * <p>Deliberately uses {@code --print} templates with {@code KEY=value} lines
 * instead of {@code --dump-json}. The full info JSON is tens of kilobytes with
 * a schema that shifts between yt-dlp releases; this contract is a handful of
 * stable fields, needs no JSON parser, and fails loudly if a field is renamed.
 *
 * <p>Alternative-field syntax ({@code %(artist,uploader,channel)s}) gives the
 * same tolerance yt-dlp's JSON has, for free.
 */
@Service
public class MetadataService {

    private static final Logger log = LoggerFactory.getLogger(MetadataService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(45);

    private final ToolLocator tools;
    private final ProcessRunner processRunner;
    private final AppProperties properties;

    public MetadataService(
            ToolLocator tools, ProcessRunner processRunner, AppProperties properties) {
        this.tools = tools;
        this.processRunner = processRunner;
        this.properties = properties;
    }

    /**
     * @param title    never null or blank; falls back to the video id
     * @param artist   may be null when the source declares no channel/artist
     * @param duration length in whole seconds, or null when unknown (live streams)
     */
    public record TrackMetadata(
            String videoId, String title, String artist, String album, Integer durationSeconds, String thumbnailUrl) {
    }

    public TrackMetadata fetch(String canonicalUrl, Duration timeout) {
        List<String> command = new ArrayList<>(tools.ytDlpCommand());
        command.addAll(List.of(
                "--ignore-config",
                "--no-playlist",
                "--skip-download",
                "--no-warnings",
                "--socket-timeout", "20",
                "--retries", String.valueOf(properties.download().retries()),
                "--print", "ID=%(id)s",
                "--print", "TITLE=%(title)s",
                "--print", "ARTIST=%(artist,uploader,channel,creator)s",
                "--print", "ALBUM=%(album)s",
                "--print", "DURATION=%(duration)s",
                "--print", "THUMBNAIL=%(thumbnail)s",
                canonicalUrl));

        ProcessRunner.Result result;
        try {
            result = processRunner.run(command, timeout, line -> { });
        } catch (ProcessRunner.ProcessLaunchException ex) {
            throw unavailable(ex);
        } catch (ProcessRunner.ProcessTimeoutException ex) {
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY, "metadata_timeout", "YouTube did not respond in time. Try again shortly.");
        }

        if (!result.success()) {
            throw YtDlpErrors.map(
                    result.lastErrorLine().orElse(""), result.exitCode());
        }

        Map<String, String> fields = parseFields(result.stdout());
        String videoId = value(fields, "ID");
        if (videoId == null) {
            throw new NotFoundException(
                    "That link did not resolve to a playable video. It may be private, "
                            + "region-locked, or deleted.");
        }

        return new TrackMetadata(
                videoId,
                orDefault(value(fields, "TITLE"), videoId),
                value(fields, "ARTIST"),
                value(fields, "ALBUM"),
                parseDuration(value(fields, "DURATION")),
                value(fields, "THUMBNAIL"));
    }

    public TrackMetadata fetch(String canonicalUrl) {
        return fetch(canonicalUrl, TIMEOUT);
    }

    /**
     * Collects {@code KEY=value} lines into a map.
     *
     * <p>Only the first line of a value is kept: titles occasionally contain
     * stray newlines, and a multi-line value would corrupt the line protocol.
     */
    static Map<String, String> parseFields(String stdout) {
        Map<String, String> fields = new HashMap<>();
        for (String rawLine : stdout.split("\\R")) {
            String line = rawLine.strip();
            int separator = line.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1).strip();
            fields.putIfAbsent(key, value);
        }
        return fields;
    }

    private static String value(Map<String, String> fields, String key) {
        String raw = fields.get(key);
        if (raw == null || raw.isBlank() || "NA".equalsIgnoreCase(raw) || "None".equals(raw)) {
            return null;
        }
        return raw;
    }

    private static String orDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static Integer parseDuration(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            double seconds = Double.parseDouble(raw);
            return seconds <= 0 ? null : (int) Math.round(seconds);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static ApiException unavailable(Exception cause) {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "tool_unavailable",
                "yt-dlp could not be started. Check that it is installed and on PATH.",
                cause);
    }
}
