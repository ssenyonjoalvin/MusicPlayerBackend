package com.pahappa.musicplayer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pahappa.musicplayer.config.AppProperties;
import com.pahappa.musicplayer.domain.AudioFormat;
import com.pahappa.musicplayer.exception.NotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Storage is the security boundary for a file-serving endpoint: stored paths are
 * resolved from the database, so a traversal bug would be a file-disclosure bug.
 */
class FileStorageServiceTest {

    private static FileStorageService storageAt(Path root) {
        AppProperties properties =
                new AppProperties(new AppProperties.Storage(root.toString(), null), null, null, null, null);
        return new FileStorageService(properties);
    }

    @Test
    @DisplayName("resolves a path that stays inside the library")
    void resolvesStoredFile(@TempDir Path root) throws IOException {
        Path audio = Files.createDirectories(root.resolve("audio"));
        Path track = Files.writeString(audio.resolve("abc.m4a"), "audio");

        FileStorageService storage = storageAt(root);

        assertThat(storage.resolveStored("audio/abc.m4a")).isEqualTo(track.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("refuses to escape the library root")
    void blocksPathTraversal(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("secret.txt"), "top secret");
        FileStorageService storage = storageAt(root);

        assertThatThrownBy(() -> storage.resolveStored("../secret.txt"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> storage.resolveStored("audio/../../secret.txt"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> storage.resolveStored("..\\secret.txt"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("reports a missing file as not found rather than a 500")
    void reportsMissingFile(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("audio"));
        FileStorageService storage = storageAt(root);

        assertThatThrownBy(() -> storage.resolveStored("audio/gone.m4a")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> storage.resolveStored(null)).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("round-trips a relative path")
    void relativiseRoundTrips(@TempDir Path root) {
        FileStorageService storage = storageAt(root);
        Path target = root.resolve("audio").resolve("abc.m4a").toAbsolutePath().normalize();

        assertThat(storage.relativise(target)).isEqualTo("audio/abc.m4a");
    }

    @Test
    @DisplayName("derives a readable attachment filename")
    void buildsDownloadName(@TempDir Path root) {
        FileStorageService storage = storageAt(root);

        assertThat(storage.buildDownloadName("Never Gonna Give You Up", "Rick Astley", AudioFormat.M4A))
                .isEqualTo("Rick Astley - Never Gonna Give You Up.m4a");
        assertThat(storage.buildDownloadName("Untitled", null, AudioFormat.MP3)).isEqualTo("Untitled.mp3");
    }

    @Test
    @DisplayName("does not repeat an artist the title already leads with")
    void avoidsDuplicatingArtist(@TempDir Path root) {
        FileStorageService storage = storageAt(root);

        // YouTube titles routinely embed the artist; "Rick Astley - Rick Astley - ..."
        // is what a naive join produces.
        assertThat(storage.buildDownloadName(
                        "Rick Astley - Never Gonna Give You Up (Official Video)", "Rick Astley", AudioFormat.M4A))
                .isEqualTo("Rick Astley - Never Gonna Give You Up (Official Video).m4a");
        assertThat(storage.buildDownloadName(
                        "rick astley - never gonna give you up", "Rick Astley", AudioFormat.M4A))
                .isEqualTo("rick astley - never gonna give you up.m4a");
    }

    @Test
    @DisplayName("strips characters that are illegal in Windows filenames")
    void sanitisesIllegalCharacters() {
        assertThat(FileStorageService.sanitiseFileName("AC/DC: Back in Black?"))
                .isEqualTo("ACDC Back in Black");
        assertThat(FileStorageService.sanitiseFileName("song<1>.flac")).isEqualTo("song1.flac");
    }

    @Test
    @DisplayName("never emits a name that is empty or ends in a dot")
    void sanitisesDegenerateNames() {
        assertThat(FileStorageService.sanitiseFileName("")).isEqualTo("track");
        assertThat(FileStorageService.sanitiseFileName("   ")).isEqualTo("track");
        assertThat(FileStorageService.sanitiseFileName(null)).isEqualTo("track");
        // A trailing dot is silently dropped by Windows, which would desync name and file.
        assertThat(FileStorageService.sanitiseFileName("Hello...")).isEqualTo("Hello");
        assertThat(FileStorageService.sanitiseFileName("...")).isEqualTo("track");
    }

    @Test
    @DisplayName("clamps very long titles to a filesystem-safe length")
    void clampsLongNames() {
        String sanitised = FileStorageService.sanitiseFileName("x".repeat(500));

        assertThat(sanitised).hasSizeLessThanOrEqualTo(120);
    }

    @Test
    @DisplayName("keeps a caller-derived id to a single safe path segment")
    void confinesIdsToOneSegment(@TempDir Path root) {
        FileStorageService storage = storageAt(root);

        // A hostile id must not be able to choose a directory.
        assertThat(storage.audioFile("../../escape", AudioFormat.M4A).getFileName().toString())
                .isEqualTo("escape.m4a");
        assertThat(storage.audioFile("../../escape", AudioFormat.M4A).getParent().getFileName().toString())
                .isEqualTo("audio");
    }

    @Test
    @DisplayName("purges a work directory including its scratch files")
    void purgesWorkDirectory(@TempDir Path root) throws IOException {
        FileStorageService storage = storageAt(root);
        Path work = storage.workDirectory("track-1");
        Files.writeString(work.resolve("track-1.m4a.part"), "partial");

        storage.purgeWorkDirectory("track-1");

        assertThat(Files.exists(work)).isFalse();
    }

    @Test
    @DisplayName("deleting an absent file is a no-op")
    void deleteIsIdempotent(@TempDir Path root) {
        FileStorageService storage = storageAt(root);

        storage.deleteQuietly(root.resolve("never-existed.m4a"));
        storage.deleteQuietly(null);
    }

    @Test
    @DisplayName("writes audio as utf-8 so non-latin titles survive")
    void handlesNonAsciiTitles(@TempDir Path root) throws IOException {
        FileStorageService storage = storageAt(root);
        Path audio = Files.createDirectories(root.resolve("audio"));
        Files.writeString(audio.resolve("a.m4a"), "x".repeat(8), StandardCharsets.UTF_8);

        assertThat(storage.buildDownloadName("夜に駆ける", "YOASOBI", AudioFormat.M4A))
                .isEqualTo("YOASOBI - 夜に駆ける.m4a");
    }
}
