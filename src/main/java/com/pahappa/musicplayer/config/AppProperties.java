package com.pahappa.musicplayer.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Root of the {@code app.*} configuration tree.
 *
 * <p>Every field has a usable default so the app boots with zero configuration
 * on a developer machine; deployments override via {@code application.yml} or
 * environment variables.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Storage storage, Tools tools, Download download, Streaming streaming, Cors cors) {

    /**
     * How long an audio response may take before the container gives up on it.
     *
     * <p>Defaults to 10 minutes rather than the servlet container's 30 seconds.
     * Audio is streamed through {@code StreamingResponseBody}, which is
     * asynchronous, and the container timeout applies to the whole exchange - so
     * a 30s default silently truncates any track that takes longer to send than
     * that, which on a phone hotspot is most of them.
     */
    public record Streaming(Duration asyncTimeout) {
        public Streaming {
            if (asyncTimeout == null) {
                asyncTimeout = Duration.ofMinutes(10);
            }
        }
    }

    public AppProperties {
        storage = storage == null ? new Storage(null, null) : storage;
        tools = tools == null ? new Tools(null, null, null) : tools;
        download = download == null ? new Download(2, Duration.ofMinutes(30), 3) : download;
        streaming = streaming == null ? new Streaming(null) : streaming;
        cors = cors == null ? new Cors(List.of("*"), List.of("GET", "POST", "DELETE", "OPTIONS")) : cors;
    }

    /** Where audio files, thumbnails and the embedded database live. */
    public record Storage(String root, String database) {
        public Storage {
            root = (root == null || root.isBlank()) ? "data" : root;
            // H2 2.x refuses a file path that is only implicitly relative.
            database = (database == null || database.isBlank()) ? "./data/library" : database;
        }

        /** Resolved absolute path of the library root. */
        public String resolvedRoot() {
            return java.nio.file.Paths.get(root).toAbsolutePath().normalize().toString();
        }
    }

    /**
     * Locations of the external binaries we shell out to.
     *
     * <p>Leaving these {@code null} makes the resolvers probe {@code PATH} and
     * the usual install directories, which is what you want on a workstation.
     */
    public record Tools(String ytDlpPath, String ffmpegPath, String pythonExecutable) {
    }

    /** Throughput limits for the download pipeline. */
    public record Download(int maxConcurrent, Duration timeout, int retries) {
        public Download {
            if (maxConcurrent <= 0) {
                maxConcurrent = 2;
            }
            if (timeout == null) {
                timeout = Duration.ofMinutes(30);
            }
            if (retries < 0) {
                retries = 0;
            }
        }
    }

    /** CORS is only needed for browser-based clients; native apps ignore it. */
    public record Cors(List<String> allowedOrigins, List<String> allowedMethods) {
    }
}
