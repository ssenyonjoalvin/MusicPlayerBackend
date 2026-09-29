package com.pahappa.musicplayer.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the {@code KEY=value} line protocol used to read metadata out of
 * yt-dlp, including the fields yt-dlp reports as {@code NA}.
 */
class MetadataServiceTest {

    @Test
    @DisplayName("parses a full metadata response")
    void parsesFields() {
        String stdout = """
                ID=dQw4w9WgXcQ
                TITLE=Never Gonna Give You Up
                ARTIST=Rick Astley
                ALBUM=Whenever You Need Somebody
                DURATION=212.0
                THUMBNAIL=https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg
                """;

        Map<String, String> fields = MetadataService.parseFields(stdout);

        assertThat(fields)
                .containsEntry("ID", "dQw4w9WgXcQ")
                .containsEntry("TITLE", "Never Gonna Give You Up")
                .containsEntry("ARTIST", "Rick Astley")
                .containsEntry("DURATION", "212.0");
    }

    @Test
    @DisplayName("keeps NA rather than inventing a value")
    void preservesNaMarkers() {
        // Downstream, a present "NA" means "unknown"; an absent key would look
        // like a protocol failure, so the distinction has to survive.
        Map<String, String> fields = MetadataService.parseFields("ID=abc\nALBUM=NA\n");

        assertThat(fields).containsEntry("ALBUM", "NA");
        assertThat(fields).doesNotContainKey("ARTIST");
    }

    @Test
    @DisplayName("splits on the first '=' so values may contain '='")
    void splitsOnFirstSeparatorOnly() {
        Map<String, String> fields = MetadataService.parseFields("TITLE=a=b=c");

        assertThat(fields).containsEntry("TITLE", "a=b=c");
    }

    @Test
    @DisplayName("keeps the first occurrence of a repeated key")
    void firstValueWins() {
        Map<String, String> fields = MetadataService.parseFields("ID=first\nID=second\n");

        assertThat(fields).containsEntry("ID", "first");
    }

    @Test
    @DisplayName("returns an empty map for empty output instead of throwing")
    void toleratesEmptyOutput() {
        assertThat(MetadataService.parseFields("")).isEmpty();
        assertThat(MetadataService.parseFields("\n\n")).isEmpty();
    }

    @Test
    @DisplayName("ignores warning chatter that precedes the fields")
    void ignoresNonFieldLines() {
        Map<String, String> fields = MetadataService.parseFields("WARNING: something\nID=abc\nnot a field\n");

        assertThat(fields).hasSize(1).containsEntry("ID", "abc");
    }
}
