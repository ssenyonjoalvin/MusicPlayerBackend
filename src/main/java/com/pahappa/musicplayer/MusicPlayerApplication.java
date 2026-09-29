package com.pahappa.musicplayer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Offline-first audio library API.
 *
 * <p>Clients hand us a YouTube share link; we resolve its metadata, pull the
 * audio-only stream via {@code yt-dlp} and keep the result in a local library
 * that mobile players can stream and cache.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
public class MusicPlayerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MusicPlayerApplication.class, args);
    }
}
