package com.pahappa.musicplayer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pahappa.musicplayer.exception.InvalidLinkException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The parser is the app's front door: every request from a mobile share sheet
 * lands here, and the shapes are more varied than they look.
 */
class YouTubeLinkParserTest {

    private final YouTubeLinkParser parser = new YouTubeLinkParser();

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                "https://youtube.com/watch?v=dQw4w9WgXcQ",
                "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
                "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
                "https://www.youtube.com/watch?app=desktop&v=dQw4w9WgXcQ&t=42s",
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLabcdefghijklmnop",
                "https://youtu.be/dQw4w9WgXcQ",
                "https://youtu.be/dQw4w9WgXcQ?t=43",
                "https://www.youtube.com/shorts/dQw4w9WgXcQ",
                "https://www.youtube.com/embed/dQw4w9WgXcQ",
                "https://www.youtube.com/live/dQw4w9WgXcQ",
                "https://www.youtube.com/v/dQw4w9WgXcQ",
                "dQw4w9WgXcQ",
                "youtu.be/dQw4w9WgXcQ",
                "www.youtu.be/dQw4w9WgXcQ",
                "  https://youtu.be/dQw4w9WgXcQ  ",
                "Check this out: https://youtu.be/dQw4w9WgXcQ via YouTube",
                "Look at this https://www.youtube.com/watch?v=dQw4w9WgXcQ now"
            })
    @DisplayName("accepts every real-world share shape")
    void extractsVideoId(String input) {
        assertThat(parser.parse(input).videoId()).isEqualTo("dQw4w9WgXcQ");
    }

    @Test
    @DisplayName("normalises to a tracking-free canonical watch URL")
    void canonicalisesUrl() {
        String canonical = parser.parse("https://www.youtube.com/watch?app=desktop&v=dQw4w9WgXcQ&t=42s")
                .canonicalUrl();

        assertThat(canonical).isEqualTo("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
    }

    @Test
    @DisplayName("strips tracking params so the same video is always the same id")
    void playlistParamsDoNotChangeIdentity() {
        String withList = parser.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLabc&index=4")
                .canonicalUrl();
        String plain = parser.parse("https://youtu.be/dQw4w9WgXcQ").canonicalUrl();

        assertThat(withList).isEqualTo(plain);
    }

    @Test
    @DisplayName("handles ids containing url-safe base64 punctuation")
    void handlesDashesAndUnderscores() {
        // '-' and '_' are legal in a video id and must survive validation.
        assertThat(parser.parse("https://youtu.be/a-b_c-d1e2F").videoId())
                .isEqualTo("a-b_c-d1e2F");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://vimeo.com/123456789",
                "https://www.youtube.com.evil.example/watch?v=dQw4w9WgXcQ",
                "https://soundcloud.com/some/track",
                "file:///etc/passwd",
                "https://www.youtube.com/playlist?list=PLabcdefghijklmnopqrstuvwxyz"
            })
    @DisplayName("rejects non-YouTube hosts and look-alike domains")
    void rejectsForeignLinks(String input) {
        assertThatThrownBy(() -> parser.parse(input)).isInstanceOf(InvalidLinkException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "just some text", "https://www.youtube.com/watch?v=tooshort"})
    @DisplayName("rejects input that is not a video link")
    void rejectsGarbage(String input) {
        assertThatThrownBy(() -> parser.parse(input)).isInstanceOf(InvalidLinkException.class);
    }

    @Test
    @DisplayName("rejects null rather than throwing an NPE at the controller")
    void rejectsNull() {
        assertThatThrownBy(() -> parser.parse(null)).isInstanceOf(InvalidLinkException.class);
    }

    @Test
    @DisplayName("truncates the echoed preview instead of reflecting a huge payload")
    void truncatesLongInput() {
        String spam = "9".repeat(500);

        assertThatThrownBy(() -> parser.parse(spam))
                .isInstanceOf(InvalidLinkException.class)
                .hasMessageEndingWith("...")
                .satisfies(ex -> assertThat(ex.getMessage()).hasSizeLessThan(200));
    }
}
