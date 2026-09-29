package com.pahappa.musicplayer.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AppPropertiesTest {

    @Test
    @DisplayName("zero configuration still yields usable defaults")
    void defaultsWhenNothingSupplied() {
        AppProperties properties = new AppProperties(null, null, null, null, null);

        assertThat(properties.storage().root()).isEqualTo("data");
        assertThat(properties.storage().database()).isEqualTo("./data/library");
        assertThat(properties.download().maxConcurrent()).isEqualTo(2);
        assertThat(properties.download().timeout()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("the async stream timeout defaults well beyond the servlet container's 30s")
    void asyncTimeoutIsGenerousByDefault() {
        AppProperties properties = new AppProperties(null, null, null, null, null);

        // Tomcat's default is 30s. Anything at or below it truncates real tracks
        // on a slow connection, so guard the floor here rather than trusting a
        // YAML value nobody remembers changing.
        assertThat(properties.streaming().asyncTimeout())
                .isGreaterThan(Duration.ofSeconds(30))
                .isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("nonsensical numeric settings are clamped instead of accepted")
    void clampsInvalidNumbers() {
        AppProperties properties = new AppProperties(
                null, null, new AppProperties.Download(0, null, -5), null, null);

        assertThat(properties.download().maxConcurrent()).isEqualTo(2);
        assertThat(properties.download().retries()).isZero();
    }

    @Test
    @DisplayName("a blank storage root falls back rather than resolving to the working directory")
    void blankStorageFallsBack() {
        AppProperties properties = new AppProperties(new AppProperties.Storage("  ", ""), null, null, null, null);

        assertThat(properties.storage().root()).isEqualTo("data");
        assertThat(properties.storage().database()).isEqualTo("./data/library");
    }
}
