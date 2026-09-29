package com.pahappa.musicplayer.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Thin, well-behaved wrapper around {@link ProcessBuilder} for the external
 * tools this service drives.
 *
 * <p>Two details matter and are easy to get wrong by hand:
 * <ul>
 *   <li>stdout and stderr must be drained <em>concurrently</em>, otherwise a
 *       full pipe buffer deadlocks the child process.</li>
 *   <li>stderr is kept as a bounded tail rather than accumulated whole, since
 *       yt-dlp is chatty and a runaway log should not exhaust the heap.</li>
 * </ul>
 *
 * <p>Live processes are tracked so an in-flight download can be cancelled.
 */
@Component
public class ProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcessRunner.class);
    private static final int STDERR_TAIL_LINES = 60;

    private final Map<String, Process> liveProcesses = new ConcurrentHashMap<>();

    /** Result of a finished child process. */
    public record Result(int exitCode, String stdout, String stderrTail) {

        public boolean success() {
            return exitCode == 0;
        }

        /** Last meaningful stderr line, useful as a user-facing error. */
        public Optional<String> lastErrorLine() {
            return stderrTail.lines()
                    .map(String::strip)
                    .filter(line -> !line.isBlank())
                    .filter(line -> !line.startsWith("WARNING"))
                    .reduce((first, second) -> second);
        }
    }

    /** Runs a command to completion, streaming each stdout line to the listener. */
    public Result run(List<String> command, Duration timeout, Consumer<String> stdoutListener) {
        return run(command, timeout, null, stdoutListener);
    }

    /**
     * Runs a command, registering it under {@code registryKey} while it lives so
     * {@link #cancel(String)} can terminate it.
     */
    public Result run(
            List<String> command, Duration timeout, String registryKey, Consumer<String> stdoutListener) {

        log.debug("exec: {}", String.join(" ", command));

        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException ex) {
            throw new ProcessLaunchException("Could not start " + command.get(0), ex);
        }

        if (registryKey != null) {
            liveProcesses.put(registryKey, process);
        }

        // Drain stderr on its own thread; the calling thread owns stdout. The
        // tail must be fed here: without it every error message degrades to a
        // bare "exited with code 1" because the diagnostic never gets read.
        BoundedTail stderrTail = new BoundedTail(STDERR_TAIL_LINES);
        Thread stderrPump = new Thread(
                () -> readLines(process.getErrorStream(), stderrTail::add), "stderr-pump");
        stderrPump.setDaemon(true);
        stderrPump.start();

        StringBuilder stdout = new StringBuilder();
        try {
            readLines(process.getInputStream(), line -> {
                stdout.append(line).append('\n');
                if (stdoutListener != null) {
                    stdoutListener.accept(line);
                }
            });

            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new ProcessTimeoutException(
                        "Timed out after " + timeout.toMinutes() + " minute(s)");
            }
            // Let the stderr pump drain before reading its buffer.
            stderrPump.join(TimeUnit.SECONDS.toMillis(2));

            return new Result(process.exitValue(), stdout.toString(), stderrTail.render());
        } catch (InterruptedException ex) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new ProcessCancelledException("Interrupted while waiting for " + command.get(0));
        } finally {
            if (registryKey != null) {
                liveProcesses.remove(registryKey, process);
            }
        }
    }

    /** Terminates a running process. Returns false when nothing was registered. */
    public boolean cancel(String registryKey) {
        Process process = liveProcesses.get(registryKey);
        if (process == null || !process.isAlive()) {
            return false;
        }
        log.info("Cancelling process for {}", registryKey);
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException ex) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        return true;
    }

    public boolean isRunning(String registryKey) {
        Process process = liveProcesses.get(registryKey);
        return process != null && process.isAlive();
    }

    private static void readLines(java.io.InputStream stream, Consumer<String> lineConsumer) {
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineConsumer.accept(line);
            }
        } catch (IOException ex) {
            log.debug("Stream closed while reading child output: {}", ex.getMessage());
        }
    }

    /** Fixed-capacity ring of recent lines, safe for concurrent writes. */
    private static final class BoundedTail {

        private final int capacity;
        private final Deque<String> lines = new ArrayDeque<>();

        BoundedTail(int capacity) {
            this.capacity = capacity;
        }

        synchronized void add(String line) {
            if (line == null) {
                return;
            }
            lines.addLast(line);
            while (lines.size() > capacity) {
                lines.removeFirst();
            }
        }

        synchronized String render() {
            return String.join(System.lineSeparator(), new ArrayList<>(lines));
        }
    }

    /** Thrown when the executable itself is missing or not runnable. */
    public static class ProcessLaunchException extends RuntimeException {
        public ProcessLaunchException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class ProcessTimeoutException extends RuntimeException {
        public ProcessTimeoutException(String message) {
            super(message);
        }
    }

    public static class ProcessCancelledException extends RuntimeException {
        public ProcessCancelledException(String message) {
            super(message);
        }
    }
}
