package com.pahappa.musicplayer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A single audio item in the local library.
 *
 * <p>The row doubles as the download job record: while {@link #status} is not
 * {@link TrackStatus#READY} the progress fields are live and readable by the
 * client, so a mobile app only needs one endpoint to render "downloading 43%".
 */
@Entity
@Table(
        name = "tracks",
        indexes = {
            @Index(name = "idx_tracks_created_at", columnList = "created_at"),
            @Index(name = "idx_tracks_status", columnList = "status")
        })
public class Track {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** YouTube's 11-character video id; unique so a re-pasted link is a no-op. */
    @Column(name = "youtube_video_id", nullable = false, unique = true, length = 32)
    private String youtubeVideoId;

    @Column(name = "source_url", nullable = false, length = 512)
    private String sourceUrl;

    @Column(name = "title", nullable = false, length = 512)
    private String title;

    @Column(name = "artist", length = 512)
    private String artist;

    @Column(name = "album", length = 512)
    private String album;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "thumbnail_url", length = 1024)
    private String thumbnailUrl;

    /**
     * Library-relative path of the locally cached artwork, or null if it was
     * never fetched. Kept separate from {@link #thumbnailUrl} so the upstream
     * URL survives eviction of the cached copy.
     */
    @Column(name = "cover_path", length = 1024)
    private String coverPath;

    /**
     * Persisted as the enum <em>name</em>, never its ordinal.
     *
     * <p>Ordinal storage is positional, so adding, removing or reordering a
     * constant silently reinterprets every existing row. That is not
     * hypothetical: a status column once held ordinal 4 for a value that no
     * longer exists, and every read of that table threw
     * {@code ArrayIndexOutOfBoundsException}. A crash is the lucky outcome - the
     * unlucky one is a row that quietly changes meaning.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "audio_format", nullable = false, length = 8)
    private AudioFormat audioFormat = AudioFormat.M4A;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TrackStatus status = TrackStatus.QUEUED;

    @Column(name = "progress_percent")
    private double progressPercent;

    @Column(name = "downloaded_bytes")
    private Long downloadedBytes;

    @Column(name = "total_bytes")
    private Long totalBytes;

    @Column(name = "speed_bytes_per_second")
    private Double speedBytesPerSecond;

    @Column(name = "eta_seconds")
    private Long etaSeconds;

    /** Path of the finished file, relative to the storage root. */
    @Column(name = "file_path", length = 1024)
    private String filePath;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "error_message", length = 2048)
    private String errorMessage;

    /**
     * EBU R128 integrated loudness of the finished file, in LUFS; null until
     * measured. Clients use it to play every track at the same volume.
     */
    @Column(name = "loudness_lufs")
    private Double loudnessLufs;

    /** True peak in dBTP, which caps how far a quiet track may be boosted. */
    @Column(name = "true_peak_db")
    private Double truePeakDb;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Clears per-attempt state so a retry starts from a clean slate. */
    public void resetForRetry() {
        status = TrackStatus.QUEUED;
        progressPercent = 0d;
        downloadedBytes = null;
        totalBytes = null;
        speedBytesPerSecond = null;
        etaSeconds = null;
        errorMessage = null;
    }

    public void markDownloading() {
        status = TrackStatus.DOWNLOADING;
        errorMessage = null;
        progressPercent = 0d;
    }

    public void markReady(String filePath, long fileSizeBytes) {
        status = TrackStatus.READY;
        this.filePath = filePath;
        this.fileSizeBytes = fileSizeBytes;
        this.progressPercent = 100d;
        this.downloadedBytes = fileSizeBytes;
        this.totalBytes = fileSizeBytes;
        this.speedBytesPerSecond = null;
        this.etaSeconds = 0L;
        this.errorMessage = null;
        // A (re)downloaded file is a new file; the old measurement no longer applies.
        this.loudnessLufs = null;
        this.truePeakDb = null;
    }

    public void markFailed(String message) {
        status = TrackStatus.FAILED;
        this.errorMessage = truncate(message, 2048);
        this.speedBytesPerSecond = null;
        this.etaSeconds = null;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getYoutubeVideoId() {
        return youtubeVideoId;
    }

    public void setYoutubeVideoId(String youtubeVideoId) {
        this.youtubeVideoId = youtubeVideoId;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getArtist() {
        return artist;
    }

    public void setArtist(String artist) {
        this.artist = artist;
    }

    public String getAlbum() {
        return album;
    }

    public void setAlbum(String album) {
        this.album = album;
    }

    public Integer getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Integer durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public void setThumbnailUrl(String thumbnailUrl) {
        this.thumbnailUrl = thumbnailUrl;
    }

    public String getCoverPath() {
        return coverPath;
    }

    public void setCoverPath(String coverPath) {
        this.coverPath = coverPath;
    }

    public AudioFormat getAudioFormat() {
        return audioFormat;
    }

    public void setAudioFormat(AudioFormat audioFormat) {
        this.audioFormat = audioFormat;
    }

    public TrackStatus getStatus() {
        return status;
    }

    public void setStatus(TrackStatus status) {
        this.status = status;
    }

    public double getProgressPercent() {
        return progressPercent;
    }

    public void setProgressPercent(double progressPercent) {
        this.progressPercent = progressPercent;
    }

    public Long getDownloadedBytes() {
        return downloadedBytes;
    }

    public void setDownloadedBytes(Long downloadedBytes) {
        this.downloadedBytes = downloadedBytes;
    }

    public Long getTotalBytes() {
        return totalBytes;
    }

    public void setTotalBytes(Long totalBytes) {
        this.totalBytes = totalBytes;
    }

    public Double getSpeedBytesPerSecond() {
        return speedBytesPerSecond;
    }

    public void setSpeedBytesPerSecond(Double speedBytesPerSecond) {
        this.speedBytesPerSecond = speedBytesPerSecond;
    }

    public Long getEtaSeconds() {
        return etaSeconds;
    }

    public void setEtaSeconds(Long etaSeconds) {
        this.etaSeconds = etaSeconds;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public Long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public void setFileSizeBytes(Long fileSizeBytes) {
        this.fileSizeBytes = fileSizeBytes;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Double getLoudnessLufs() {
        return loudnessLufs;
    }

    public void setLoudnessLufs(Double loudnessLufs) {
        this.loudnessLufs = loudnessLufs;
    }

    public Double getTruePeakDb() {
        return truePeakDb;
    }

    public void setTruePeakDb(Double truePeakDb) {
        this.truePeakDb = truePeakDb;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
