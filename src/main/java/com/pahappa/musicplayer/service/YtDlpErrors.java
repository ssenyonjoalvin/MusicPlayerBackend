package com.pahappa.musicplayer.service;

import com.pahappa.musicplayer.exception.ApiException;
import java.util.Locale;
import org.springframework.http.HttpStatus;

/**
 * Translates yt-dlp's free-text stderr into stable API error codes.
 *
 * <p>Shared by the metadata and download paths: the same underlying problem
 * (a private video, a region block) should surface identically whether the user
 * hit it at submit time or mid-transfer.
 *
 * <p>Matching is substring-based and ordered, because yt-dlp has no machine
 * readable error taxonomy on stderr.
 */
final class YtDlpErrors {

    private YtDlpErrors() {
    }

    static ApiException map(String detail, int exitCode) {
        String lower = detail.toLowerCase(Locale.ROOT);

        if (lower.contains("private video") || lower.contains("this video is private")) {
            return new ApiException(HttpStatus.FORBIDDEN, "video_private", "This video is private.");
        }
        if (lower.contains("video unavailable") || lower.contains("has been removed")) {
            return new ApiException(
                    HttpStatus.GONE, "video_unavailable", "This video is no longer available.");
        }
        if (lower.contains("not available in your country")
                || lower.contains("blocked it in your country")
                || lower.contains("geo restricted")) {
            return new ApiException(
                    HttpStatus.FORBIDDEN, "geo_blocked", "This video is not available in your region.");
        }
        if (lower.contains("sign in to confirm")
                || lower.contains("confirm your age")
                || lower.contains("age-restricted")
                || lower.contains("login required")) {
            return new ApiException(
                    HttpStatus.FORBIDDEN,
                    "video_restricted",
                    "This video is age-restricted or requires a login, so it cannot be downloaded.");
        }
        if (lower.contains("unsupported url")) {
            return new ApiException(
                    HttpStatus.BAD_REQUEST, "invalid_link", "That is not a supported video link.");
        }
        if (lower.contains("http error 429") || lower.contains("too many requests")) {
            return new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "rate_limited",
                    "YouTube is rate limiting this server. Wait a few minutes and try again.");
        }
        if (lower.contains("requested format is not available")) {
            return new ApiException(
                    HttpStatus.BAD_GATEWAY, "format_unavailable", "No downloadable audio stream was offered for this video.");
        }
        if (lower.contains("ffprobe") || (lower.contains("ffmpeg") && lower.contains("not found"))) {
            return new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "tool_unavailable",
                    "MP3 conversion needs ffmpeg and ffprobe, which are not both available on this "
                            + "server. Install a full ffmpeg build, or request the m4a format instead.");
        }
        if (lower.contains("unable to download") || lower.contains("connection")) {
            return new ApiException(
                    HttpStatus.BAD_GATEWAY, "network_error", "The network dropped during download. Retry the track.");
        }

        String suffix = detail.isBlank() ? "yt-dlp exited with code " + exitCode : detail;
        return new ApiException(
                HttpStatus.BAD_GATEWAY, "download_failed", "Download failed: " + suffix);
    }
}
