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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
 * End-to-end coverage of the byte-serving contract.
 *
 * <p>These assertions exist because this exact path was broken once: returning a
 * {@code ResourceRegion} made every ranged request fail with a 500, since
 * {@code ResourceRegionHttpMessageConverter} only advertises
 * {@code application/octet-stream} and rejects a preset {@code audio/mp4}. A 500
 * on a seek request is what a player surfaces as "can't play this", so it is
 * worth pinning down over real HTTP rather than a mocked dispatcher.
 *
 * <p>Uses the JDK HTTP client deliberately: it exercises the actual wire format
 * with no extra test dependency and no mock in the path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TrackStreamingIntegrationTest {

    private static final byte[] PAYLOAD = "0123456789abcdefghij".getBytes(StandardCharsets.UTF_8);

    @TempDir
    static Path libraryRoot;

    @DynamicPropertySource
    static void isolateLibrary(DynamicPropertyRegistry registry) {
        // Absolute path, so no "./" prefix: H2 only requires that for a path that
        // is merely implicitly relative, and "./C:/..." is not a legal path.
        String dbPath = libraryRoot.resolve("db").toString().replace('\\', '/');
        registry.add("app.storage.root", () -> libraryRoot.toString());
        registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + dbPath);
    }

    @Autowired
    private TrackRepository repository;

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private UUID trackId;

    @BeforeEach
    void seedReadyTrack() throws IOException {
        trackId = UUID.randomUUID();

        Path audioDir = Files.createDirectories(libraryRoot.resolve("audio"));
        Files.write(audioDir.resolve(trackId + ".m4a"), PAYLOAD);

        Track track = new Track();
        track.setId(trackId);
        track.setYoutubeVideoId("dQw4w9WgXcQ");
        track.setSourceUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        track.setTitle("Never Gonna Give You Up");
        track.setArtist("Rick Astley");
        track.setAudioFormat(AudioFormat.M4A);
        track.markReady("audio/" + trackId + ".m4a", PAYLOAD.length);
        repository.save(track);
    }

    @AfterEach
    void cleanUp() {
        repository.deleteById(trackId);
    }

    @Test
    @DisplayName("a plain GET returns the whole file with its real media type")
    void servesWholeFile() throws Exception {
        HttpResponse<byte[]> response = get("/stream", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Accept-Ranges")).contains("bytes");
        assertThat(response.headers().firstValue("Content-Type")).contains("audio/mp4");
        assertThat(response.body()).isEqualTo(PAYLOAD);
    }

    @Test
    @DisplayName("a ranged GET returns 206 with an exact Content-Range")
    void servesByteRange() throws Exception {
        HttpResponse<byte[]> response = get("/stream", "bytes=0-3");

        assertThat(response.statusCode()).isEqualTo(206);
        assertThat(response.headers().firstValue("Content-Range"))
                .contains("bytes 0-3/" + PAYLOAD.length);
        assertThat(response.body()).isEqualTo("0123".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a suffix range returns the tail of the file")
    void servesSuffixRange() throws Exception {
        HttpResponse<byte[]> response = get("/stream", "bytes=-4");

        assertThat(response.statusCode()).isEqualTo(206);
        assertThat(response.headers().firstValue("Content-Range"))
                .contains("bytes 16-19/" + PAYLOAD.length);
        assertThat(response.body()).isEqualTo("ghij".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("an open-ended range runs to the end of the file")
    void servesOpenEndedRange() throws Exception {
        HttpResponse<byte[]> response = get("/stream", "bytes=15-");

        assertThat(response.statusCode()).isEqualTo(206);
        assertThat(response.body()).isEqualTo("fghij".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a range past the end of the file is unsatisfiable, not a 500")
    void rejectsRangePastEof() throws Exception {
        HttpResponse<byte[]> response = get("/stream", "bytes=9999-10000");

        assertThat(response.statusCode()).isEqualTo(416);
        assertThat(response.headers().firstValue("Content-Range")).contains("bytes */" + PAYLOAD.length);
    }

    @Test
    @DisplayName("garbage in the Range header is unsatisfiable, not a 500")
    void rejectsMalformedRange() throws Exception {
        assertThat(get("/stream", "bytes=not-a-range").statusCode()).isEqualTo(416);
    }

    @Test
    @DisplayName("the attachment endpoint serves the same bytes with a download filename")
    void servesAttachment() throws Exception {
        HttpResponse<byte[]> response = get("/file", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Disposition"))
                .hasValueSatisfying(value -> assertThat(value)
                        .startsWith("attachment")
                        .contains("Never Gonna Give You Up.m4a"));
        assertThat(response.body()).isEqualTo(PAYLOAD);
    }

    @Test
    @DisplayName("a track that is not READY is not streamable")
    void refusesUnreadyTrack() throws Exception {
        repository.findById(trackId).ifPresent(track -> {
            track.markFailed("nope");
            repository.save(track);
        });

        assertThat(get("/stream", null).statusCode()).isEqualTo(404);
    }

    private HttpResponse<byte[]> get(String suffix, String rangeHeader) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/tracks/" + trackId + suffix))
                .timeout(Duration.ofSeconds(30))
                .GET();
        if (rangeHeader != null) {
            request.header("Range", rangeHeader);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }
}
