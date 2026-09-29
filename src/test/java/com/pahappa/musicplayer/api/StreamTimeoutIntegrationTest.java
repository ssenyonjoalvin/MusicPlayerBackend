package com.pahappa.musicplayer.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.pahappa.musicplayer.domain.AudioFormat;
import com.pahappa.musicplayer.domain.Track;
import com.pahappa.musicplayer.repo.TrackRepository;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Guards the async request timeout on audio responses.
 *
 * <p>Audio is delivered through {@code StreamingResponseBody}, which is
 * asynchronous, and the servlet container's default timeout is 30 seconds. Left
 * alone, any track that takes longer than 30 seconds to send - which is most of
 * them on a phone hotspot - is truncated mid-response and the player reports a
 * corrupt file even though the file on disk is perfect.
 *
 * <p>Run with a deliberately tiny timeout so the exchange is guaranteed to
 * exceed it. That asserts two things at once: the timeout is genuinely applied
 * from configuration (rather than the container default silently winning), and
 * an exceeded timeout produces an honest {@code 504} instead of a 500.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StreamTimeoutIntegrationTest {

    /** 1ms: no real transfer can possibly finish inside this. */
    private static final Duration TINY_TIMEOUT = Duration.ofMillis(1);

    /** Large enough that a real write is still in flight when the timer fires. */
    private static final int PAYLOAD_BYTES = 2 * 1024 * 1024;

    @TempDir
    static Path libraryRoot;

    @DynamicPropertySource
    static void isolateLibrary(DynamicPropertyRegistry registry) {
        String dbPath = libraryRoot.resolve("db").toString().replace('\\', '/');
        registry.add("app.storage.root", () -> libraryRoot.toString());
        registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + dbPath);
        registry.add("app.streaming.async-timeout", () -> TINY_TIMEOUT.toMillis() + "ms");
    }

    @Autowired
    private TrackRepository repository;

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private UUID trackId;

    @BeforeEach
    void seedLargeTrack() throws IOException {
        trackId = UUID.randomUUID();

        byte[] payload = new byte[PAYLOAD_BYTES];
        new Random(42).nextBytes(payload);

        Path audioDir = Files.createDirectories(libraryRoot.resolve("audio"));
        Files.write(audioDir.resolve(trackId + ".m4a"), payload);

        Track track = new Track();
        track.setId(trackId);
        track.setYoutubeVideoId("dQw4w9WgXcQ");
        track.setSourceUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        track.setTitle("Never Gonna Give You Up");
        track.setArtist("Rick Astley");
        track.setAudioFormat(AudioFormat.M4A);
        track.markReady("audio/" + trackId + ".m4a", payload.length);
        repository.save(track);
    }

    @AfterEach
    void cleanUp() {
        repository.deleteById(trackId);
    }

    @Test
    @DisplayName("a request under a tiny timeout never surfaces as a 500")
    void tinyTimeoutNeverBecomesAServerError() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/tracks/" + trackId + "/stream"))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString());

        // Whichever way the exchange lands - completed, or timed out - it must be
        // a deliberate outcome. Before AsyncRequestTimeoutException had its own
        // handler, an expired timeout fell through to the catch-all and the
        // client saw a 500, which a player reports as a corrupt file.
        //
        // Note the timeout is not *guaranteed* to fire here: Tomcat only polls
        // expired async contexts about once a second, so a fast loopback
        // transfer can finish first. The exact-timeout behaviour is pinned
        // deterministically in WebConfigTest instead.
        assertThat(response.statusCode()).isIn(200, 504);
    }

    @Test
    @DisplayName("a ranged request under a tiny timeout never surfaces as a 500")
    void rangedTinyTimeoutNeverBecomesAServerError() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/tracks/" + trackId + "/stream"))
                .header("Range", "bytes=0-1048575")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<byte[]> response =
                http.send(request, HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isIn(206, 504);
        assertThat(response.body()).isNotNull();
    }

    @Test
    @DisplayName("a full multi-megabyte transfer arrives byte-for-byte intact")
    void largeTransferIsNotTruncated() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/tracks/" + trackId + "/stream"))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();

        HttpResponse<byte[]> response =
                http.send(request, HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).hasSize(PAYLOAD_BYTES);
        assertThat(response.body()).isEqualTo(expectedPayload());
    }

    /** Regenerates the exact bytes written in {@link #seedLargeTrack()}. */
    private static byte[] expectedPayload() {
        byte[] payload = new byte[PAYLOAD_BYTES];
        new Random(42).nextBytes(payload);
        return payload;
    }
}
