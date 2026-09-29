package com.pahappa.musicplayer.domain;

/**
 * Container formats a track can be stored in.
 *
 * <p>{@link #M4A} is the default because it is a byte-for-byte copy of the
 * source stream: no re-encode, so it is fast and lossless. {@link #MP3} is
 * offered for legacy hardware and requires ffmpeg to transcode.
 */
public enum AudioFormat {

    M4A("m4a", "audio/mp4", false),
    MP3("mp3", "audio/mpeg", true);

    private final String extension;
    private final String contentType;
    private final boolean requiresTranscode;

    AudioFormat(String extension, String contentType, boolean requiresTranscode) {
        this.extension = extension;
        this.contentType = contentType;
        this.requiresTranscode = requiresTranscode;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    /** True when producing this format needs the ffmpeg binary available. */
    public boolean requiresTranscode() {
        return requiresTranscode;
    }

    /** Lenient parse used when the value arrives from a JSON payload. */
    public static AudioFormat from(String raw) {
        if (raw == null || raw.isBlank()) {
            return M4A;
        }
        String normalised = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (normalised) {
            case "m4a", "mp4", "aac", "audio/mp4" -> M4A;
            case "mp3", "mpeg", "audio/mpeg" -> MP3;
            default -> throw new IllegalArgumentException("Unsupported audio format: " + raw);
        };
    }
}
