package com.pahappa.musicplayer.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.pahappa.musicplayer.domain.Track;
import com.pahappa.musicplayer.domain.TrackStatus;
import com.pahappa.musicplayer.repo.TrackRepository;
import com.pahappa.musicplayer.service.MetadataService;
import com.pahappa.musicplayer.service.YtDlpDownloader;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

/**
 * Verifies the download actually gets dispatched after a submit.
 *
 * <p>This exists because of a real regression: {@code submit} was made
 * non-transactional so it would not pin a pooled connection across the slow
 * metadata call, and the {@code AFTER_COMMIT} listener then silently stopped
 * firing - the track sat in {@code QUEUED} forever with no error anywhere. The
 * symptom is invisible unless you assert the download <em>runs</em>, which is
 * what this does.
 *
 * <p>yt-dlp is mocked out, so this exercises the dispatch chain, not the network.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DownloadDispatchIntegrationTest {

    @TempDir
    static Path libraryRoot;

    @DynamicPropertySource
    static void isolateLibrary(DynamicPropertyRegistry registry) {
        String dbPath = libraryRoot.resolve("db").toString().replace('\\', '/');
        registry.add("app.storage.root", () -> libraryRoot.toString());
        registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + dbPath);
        // Keep the pool well clear so a stuck dispatch cannot be masked.
        registry.add("app.download.max-concurrent", () -> "2");
    }

    @MockitoBean
    private MetadataService metadataService;

    @MockitoBean
    private YtDlpDownloader downloader;

    @Autowired
    private TrackRepository repository;

    @LocalServerPort
    private int port;

    private RestClient client;

    @BeforeEach
    void stubExternalTools() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();

        when(metadataService.fetch(anyString(), any(Duration.class)))
                .thenReturn(new MetadataService.TrackMetadata(
                        "dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", null, 213, null));
        when(metadataService.fetch(anyString()))
                .thenReturn(new MetadataService.TrackMetadata(
                        "dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", null, 213, null));

        when(downloader.download(any(Track.class), anyString(), any())).thenReturn(
                new YtDlpDownloader.DownloadResult(libraryRoot.resolve("audio").resolve("out.m4a"), 1024L));
    }

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("a submit outside any transaction still starts the download")
    void submitDispatchesDownload() throws Exception {
        String body = """
                {"url":"https://youtu.be/dQw4w9WgXcQ","format":"m4a"}
                """;

        var response = client
                .post()
                .uri("/api/v1/downloads")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).contains("\"status\":\"QUEUED\"");

        UUID id = extractId(response.getBody());
        Track track = awaitTerminalState(id);

        // The regression showed up as a track frozen in QUEUED.
        assertThat(track.getStatus())
                .as("download should have run, not stayed QUEUED forever")
                .isNotEqualTo(TrackStatus.QUEUED);
    }

    @Test
    @DisplayName("a second submit of a known link does not start a second download")
    void submitIsIdempotent() throws Exception {
        String body = """
                {"url":"https://youtu.be/dQw4w9WgXcQ","format":"m4a"}
                """;

        client.post().uri("/api/v1/downloads").contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().toEntity(String.class);

        var second = client.post().uri("/api/v1/downloads").contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().toEntity(String.class);

        assertThat(second.getStatusCode())
                .as("a known link is 200 OK, not a new 202")
                .isEqualTo(HttpStatus.OK);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("an unplayable link is rejected before any job is created")
    void rejectsForeignHost() {
        String body = """
                {"url":"https://vimeo.com/12345"}
                """;

        // RestClient throws on 4xx by default; suppress that so the error
        // envelope itself can be asserted.
        var response = client.post().uri("/api/v1/downloads").contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(status -> true, (request, res) -> { })
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("invalid_link");
        assertThat(repository.count()).isZero();
    }

    /** Polls until the worker reaches a terminal state, or the test gives up. */
    private Track awaitTerminalState(UUID id) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        Track track = null;
        while (System.currentTimeMillis() < deadline) {
            track = repository.findById(id).orElseThrow();
            if (track.getStatus().isTerminal()) {
                return track;
            }
            Thread.sleep(100);
        }
        return track;
    }

    private static UUID extractId(String json) {
        String marker = "\"id\":\"";
        int start = json.indexOf(marker) + marker.length();
        return UUID.fromString(json.substring(start, json.indexOf('"', start)));
    }
}
