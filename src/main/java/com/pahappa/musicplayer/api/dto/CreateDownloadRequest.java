package com.pahappa.musicplayer.api.dto;

import com.pahappa.musicplayer.domain.AudioFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/downloads}.
 *
 * <p>{@code url} accepts anything a share sheet might hand over: a full watch
 * URL, a {@code youtu.be} short link, a {@code /shorts/} path, an 11-character
 * bare id, or a URL with extra tracking parameters glued on.
 */
public record CreateDownloadRequest(

        @NotBlank(message = "A YouTube link or video id is required")
        @Size(max = 2048, message = "Link must be 2048 characters or fewer")
        String url,

        /** {@code m4a} (default) or {@code mp3}. Unknown values fall back to m4a. */
        @Size(max = 16)
        String format) {

    public AudioFormat resolvedFormat() {
        if (format == null || format.isBlank()) {
            return AudioFormat.M4A;
        }
        try {
            return AudioFormat.from(format);
        } catch (IllegalArgumentException ignored) {
            return AudioFormat.M4A;
        }
    }
}
