package com.pahappa.musicplayer.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Progress parsing is the contract with yt-dlp's stdout. Getting {@code NA}
 * handling wrong shows up as a stuck 0% bar, so it is worth pinning down.
 */
class YtDlpDownloaderTest {

    @Test
    @DisplayName("computes percent from downloaded and total")
    void parsesCompleteLine() {
        YtDlpDownloader.TransferProgress progress =
                YtDlpDownloader.parseProgressLine("downloading|1048576|4194304|NA|524288.5|6");

        assertThat(progress).isNotNull();
        assertThat(progress.percent()).isEqualTo(25.0);
        assertThat(progress.downloadedBytes()).isEqualTo(1048576L);
        assertThat(progress.totalBytes()).isEqualTo(4194304L);
        assertThat(progress.speedBytesPerSecond()).isEqualTo(524288.5);
        assertThat(progress.etaSeconds()).isEqualTo(6L);
    }

    @Test
    @DisplayName("falls back to the size estimate when total is unknown")
    void fallsBackToEstimate() {
        // yt-dlp reports NA for total_bytes on chunked transfers; the estimate
        // is the only usable denominator in that case.
        YtDlpDownloader.TransferProgress progress =
                YtDlpDownloader.parseProgressLine("downloading|1000|NA|4000|500|3");

        assertThat(progress.totalBytes()).isEqualTo(4000L);
        assertThat(progress.percent()).isEqualTo(25.0);
    }

    @Test
    @DisplayName("treats a finished status as 100% even without byte counts")
    void finishedMeansComplete() {
        YtDlpDownloader.TransferProgress progress =
                YtDlpDownloader.parseProgressLine("finished|NA|NA|NA|NA|NA");

        assertThat(progress.percent()).isEqualTo(100.0);
        assertThat(progress.downloadedBytes()).isNull();
        assertThat(progress.totalBytes()).isNull();
    }

    @Test
    @DisplayName("never reports a negative ETA or a null-object progress")
    void handlesAllUnknownFields() {
        YtDlpDownloader.TransferProgress progress =
                YtDlpDownloader.parseProgressLine("downloading|NA|NA|NA|NA|NA");

        assertThat(progress).isNotNull();
        assertThat(progress.percent()).isEqualTo(0.0);
        assertThat(progress.speedBytesPerSecond()).isNull();
        assertThat(progress.etaSeconds()).isNull();
    }

    @Test
    @DisplayName("caps percent at 100 when counts overshoot the advertised total")
    void clampsPercent() {
        YtDlpDownloader.TransferProgress progress =
                YtDlpDownloader.parseProgressLine("downloading|5000|4000|NA|100|0");

        assertThat(progress.percent()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("ignores unrelated stdout noise")
    void ignoresGarbage() {
        assertThat(YtDlpDownloader.parseProgressLine("")).isNull();
        assertThat(YtDlpDownloader.parseProgressLine("downloading|100")).isNull();
        assertThat(YtDlpDownloader.parseProgressLine("[info] Downloading format")).isNull();
    }

    @Test
    @DisplayName("tolerates a short eta field")
    void toleratesMissingTrailingFields() {
        YtDlpDownloader.TransferProgress progress =
                YtDlpDownloader.parseProgressLine("downloading|1000|4000|NA|500");

        assertThat(progress.percent()).isEqualTo(25.0);
        assertThat(progress.etaSeconds()).isNull();
    }
}
