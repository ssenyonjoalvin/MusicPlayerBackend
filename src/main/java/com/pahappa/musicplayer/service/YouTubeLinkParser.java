package com.pahappa.musicplayer.service;

import com.pahappa.musicplayer.exception.InvalidLinkException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Turns whatever a share sheet produces into a canonical YouTube watch URL.
 *
 * <p>Accepted input includes the full {@code watch?v=...} form, {@code youtu.be}
 * short links, {@code /shorts/}, {@code /embed/}, {@code /live/} paths, a bare
 * 11-character video id, and any of those wrapped in sharing chatter such as
 * {@code "Listen: https://youtu.be/abc12345678 on YouTube"}.
 *
 * <p>Only YouTube hosts are accepted. That is a deliberate security boundary:
 * the value is handed to a subprocess, and an open redirector or a
 * {@code file://} URL must never reach it.
 */
@Component
public class YouTubeLinkParser {

    /** YouTube ids are exactly 11 URL-safe base64 characters. */
    private static final Pattern VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    /** Finds the first http(s) token inside arbitrary share text. */
    private static final Pattern URL_IN_TEXT =
            Pattern.compile("https?://[^\\s<>\"'\\u00A0]+", Pattern.CASE_INSENSITIVE);

    private static final String CANONICAL_BASE = "https://www.youtube.com/watch?v=";

    /**
     * @param videoId      the 11-character YouTube id
     * @param canonicalUrl stable, tracking-free URL to hand to yt-dlp
     */
    public record ParsedLink(String videoId, String canonicalUrl) {
    }

    public ParsedLink parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidLinkException("A YouTube link or video id is required");
        }

        String text = raw.strip();

        Optional<String> embedded = firstUrlIn(text);
        if (embedded.isPresent()) {
            return parseUrl(embedded.get());
        }

        // No scheme at all: "youtu.be/abc12345678" or a bare id.
        String withoutScheme = stripLeadingWwwWww(text);
        if (withoutScheme.regionMatches(true, 0, "youtu.be/", 0, "youtu.be/".length())) {
            return parseUrl("https://" + withoutScheme);
        }
        if (VIDEO_ID.matcher(text).matches()) {
            return new ParsedLink(text, CANONICAL_BASE + text);
        }

        throw new InvalidLinkException(
                "Could not find a YouTube link in the supplied text: " + preview(text));
    }

    /** Convenience for callers that only need the id. */
    public String parseVideoId(String raw) {
        return parse(raw).videoId();
    }

    private ParsedLink parseUrl(String candidate) {
        URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException ex) {
            throw new InvalidLinkException("Malformed link: " + preview(candidate));
        }

        if (!isYouTubeHost(uri.getHost())) {
            throw new InvalidLinkException(
                    "Only YouTube links are supported. Received host: " + safeHost(uri));
        }

        String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
        String[] segments = pathSegments(uri.getPath());
        String route = segments.length > 0 ? segments[0].toLowerCase(Locale.ROOT) : "";

        // Dispatch on the *route* (first path segment), not the whole path:
        // /shorts/{id} and /watch?v={id} put the id in different places.
        String candidateId = switch (route) {
            case "watch" -> queryParam(query, "v");
            case "playlist", "browse", "results", "feed", "channel" ->
                    throw new InvalidLinkException(
                            "Playlist and channel links are not supported yet - paste a single video link");
            // These routes carry the id as the segment *after* the route name.
            case "shorts", "embed", "live", "v" -> segments.length > 1 ? segments[1] : null;
            // youtu.be/{id} and anything else: the id is the first segment.
            default -> segments.length > 0 ? segments[0] : null;
        };

        if (candidateId == null || !VIDEO_ID.matcher(candidateId).matches()) {
            throw new InvalidLinkException("Link does not contain a valid YouTube video id");
        }

        return new ParsedLink(candidateId, CANONICAL_BASE + candidateId);
    }

    private static boolean isYouTubeHost(String host) {
        if (host == null) {
            return false;
        }
        String normalised = host.toLowerCase(Locale.ROOT);
        return normalised.equals("youtu.be")
                || normalised.equals("youtube.com")
                || normalised.endsWith(".youtube.com")
                || normalised.endsWith(".youtu.be");
    }

    /** Splits a URL path into its non-blank segments. */
    private static String[] pathSegments(String path) {
        if (path == null || path.isBlank()) {
            return new String[0];
        }
        return java.util.Arrays.stream(path.split("/"))
                .filter(segment -> !segment.isBlank())
                .toArray(String[]::new);
    }

    private static String queryParam(String rawQuery, String key) {
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            if (pair.substring(0, eq).equalsIgnoreCase(key)) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }

    private static Optional<String> firstUrlIn(String text) {
        Matcher matcher = URL_IN_TEXT.matcher(text);
        return matcher.find() ? Optional.of(matcher.group()) : Optional.empty();
    }

    private static String stripLeadingWwwWww(String text) {
        return text.regionMatches(true, 0, "www.", 0, 4) ? text.substring(4) : text;
    }

    private static String safeHost(URI uri) {
        return uri.getHost() == null ? "(none)" : uri.getHost();
    }

    /** Echoes a short, safe slice of the input back to help the user fix it. */
    private static String preview(String text) {
        String flat = text.replaceAll("\\s+", " ");
        return flat.length() <= 80 ? flat : flat.substring(0, 77) + "...";
    }
}
