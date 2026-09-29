package com.pahappa.musicplayer.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurerProbe;

class WebConfigTest {

    private static Long configuredTimeoutMillis(AppProperties properties) {
        AsyncSupportConfigurerProbe configurer = new AsyncSupportConfigurerProbe();
        new WebConfig(properties).configureAsyncSupport(configurer);
        return configurer.timeoutMillis();
    }

    @Test
    @DisplayName("applies the configured async timeout to the framework, not just to a field")
    void appliesConfiguredAsyncTimeout() {
        AppProperties properties = new AppProperties(
                null, null, null, new AppProperties.Streaming(Duration.ofMinutes(7)), null);

        assertThat(configuredTimeoutMillis(properties)).isEqualTo(Duration.ofMinutes(7).toMillis());
    }

    @Test
    @DisplayName("defaults well past the 30s container limit that truncates tracks")
    void defaultBeatsContainerDefault() {
        AppProperties properties = new AppProperties(null, null, null, null, null);

        assertThat(configuredTimeoutMillis(properties))
                .as("Tomcat's default is 30000ms; at or below that a slow client gets a truncated file")
                .isEqualTo(Duration.ofMinutes(10).toMillis())
                .isGreaterThan(30_000L);
    }
}
