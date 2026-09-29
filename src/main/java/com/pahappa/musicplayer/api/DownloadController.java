package com.pahappa.musicplayer.api;

import com.pahappa.musicplayer.api.dto.CreateDownloadRequest;
import com.pahappa.musicplayer.api.dto.TrackResponse;
import com.pahappa.musicplayer.domain.AudioFormat;
import com.pahappa.musicplayer.service.DownloadService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Download job endpoints: submit, poll, retry, cancel.
 *
 * <p>Submitting is asynchronous by design. The response is returned as soon as
 * the row exists, so the client can render a real title immediately and poll
 * {@code GET /api/v1/downloads/{id}} for transfer progress.
 */
@RestController
@RequestMapping("/api/v1/downloads")
public class DownloadController {

    private final DownloadService downloadService;

    public DownloadController(DownloadService downloadService) {
        this.downloadService = downloadService;
    }

    /**
     * Accepts a share link.
     *
     * <p>Returns {@code 202 Accepted} for a newly queued track and {@code 200 OK}
     * when the link was already known, which lets a client tell "I started
     * this" from "this was already in your library".
     */
    @PostMapping
    public ResponseEntity<TrackResponse> submit(@Valid @RequestBody CreateDownloadRequest request) {
        DownloadService.Submission submission = downloadService.submit(request.url(), request.resolvedFormat());
        TrackResponse body = TrackResponse.from(submission.track());
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/downloads/{id}")
                .buildAndExpand(body.id())
                .toUri();

        return ResponseEntity.status(submission.started() ? 202 : 200)
                .location(location)
                .body(body);
    }

    /** Live status and transfer progress for one job. */
    @GetMapping("/{id}")
    public TrackResponse status(@PathVariable UUID id) {
        return downloadService.get(id);
    }

    /**
     * Re-queues a failed track, optionally switching format - for example
     * {@code POST /retry?format=m4a} after an mp3 attempt failed for want of
     * ffmpeg.
     */
    @PostMapping("/{id}/retry")
    public ResponseEntity<TrackResponse> retry(
            @PathVariable UUID id,
            @RequestParam(required = false) String format) {
        AudioFormat resolved = (format == null || format.isBlank()) ? null : AudioFormat.from(format);
        return ResponseEntity.accepted().body(downloadService.retry(id, resolved));
    }

    /** Requests cancellation of an in-flight download. */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable UUID id) {
        boolean cancelled = downloadService.cancel(id);
        // Always 200: "no process was running" is a valid answer, not a failure,
        // and the client re-reads status to see the resulting state.
        return ResponseEntity.ok(Map.of("cancelled", cancelled, "id", id.toString()));
    }
}
