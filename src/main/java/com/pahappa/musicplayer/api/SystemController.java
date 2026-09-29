package com.pahappa.musicplayer.api;

import com.pahappa.musicplayer.domain.TrackStatus;
import com.pahappa.musicplayer.service.DownloadService;
import com.pahappa.musicplayer.service.FileStorageService;
import com.pahappa.musicplayer.service.ToolLocator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operational endpoints.
 *
 * <p>{@code /health} is a liveness probe. {@code /diagnostics} is the one that
 * actually saves support time: when an m4a download works but mp3 does not,
 * the answer is almost always the ffmpeg line.
 */
@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    private final ToolLocator tools;
    private final FileStorageService storage;
    private final DownloadService downloadService;

    public SystemController(
            ToolLocator tools, FileStorageService storage, DownloadService downloadService) {
        this.tools = tools;
        this.storage = storage;
        this.downloadService = downloadService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP");
    }

    @GetMapping("/diagnostics")
    public ResponseEntity<Map<String, Object>> diagnostics() {
        ToolLocator.Diagnostics toolsInfo = tools.diagnostics();

        Map<String, Object> counts = new LinkedHashMap<>();
        for (TrackStatus status : TrackStatus.values()) {
            counts.put(status.name().toLowerCase(java.util.Locale.ROOT), downloadService.countByStatus(status));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ytDlp", toolsInfo.ytDlp());
        body.put("ffmpegDirectory", toolsInfo.ffmpegDirectory());
        body.put("hasFfmpeg", toolsInfo.hasFfmpeg());
        body.put("hasFfprobe", toolsInfo.hasFfprobe());
        // mp3 also needs ffprobe for post-processing, so this is the only flag
        // that actually predicts whether an mp3 download will succeed.
        body.put("mp3Available", toolsInfo.mp3Available());
        body.put("platform", toolsInfo.windows() ? "windows" : "posix");
        body.put("libraryRoot", storage.root().toString());
        body.put("tracks", counts);

        return ResponseEntity.ok(body);
    }
}
