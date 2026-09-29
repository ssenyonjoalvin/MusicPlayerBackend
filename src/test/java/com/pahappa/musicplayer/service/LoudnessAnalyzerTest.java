package com.pahappa.musicplayer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class LoudnessAnalyzerTest {

    /** Verbatim tail of `ffmpeg -af ebur128=peak=true` on a real library track. */
    private static final String SUMMARY = """
            [Parsed_ebur128_0 @ 000001ff8a0e7a40] Summary:

              Integrated loudness:
                I:          -9.6 LUFS
                Threshold: -19.6 LUFS

              Loudness range:
                LRA:         3.8 LU
                Threshold: -29.6 LUFS
                LRA low:   -11.9 LUFS
                LRA high:   -8.1 LUFS

              True peak:
                Peak:        1.9 dBFS
            [out#0/null @ 000001ff895c14c0] video:0KiB audio:38860KiB subtitle:0KiB
            """;

    @Test
    void readsIntegratedLoudnessAndTruePeak() {
        Optional<LoudnessAnalyzer.Loudness> parsed = LoudnessAnalyzer.parseSummary(SUMMARY);
        assertTrue(parsed.isPresent());
        assertEquals(-9.6, parsed.get().integratedLufs(), 1e-9);
        assertEquals(1.9, parsed.get().truePeakDb(), 1e-9);
    }

    @Test
    void thresholdLinesAreNotMistakenForLoudness() {
        // "Threshold: -19.6 LUFS" sits right under "I:" and must not win.
        assertEquals(-9.6, LoudnessAnalyzer.parseSummary(SUMMARY).orElseThrow().integratedLufs(), 1e-9);
    }

    @Test
    void silenceIsNotALevel() {
        String silent = """
                  Integrated loudness:
                    I:         -70.0 LUFS
                  True peak:
                    Peak:       -inf dBFS
                """;
        assertTrue(LoudnessAnalyzer.parseSummary(silent).isEmpty());
    }

    @Test
    void missingPeakStillYieldsLoudness() {
        LoudnessAnalyzer.Loudness parsed =
                LoudnessAnalyzer.parseSummary("    I:         -14.2 LUFS\n").orElseThrow();
        assertEquals(-14.2, parsed.integratedLufs(), 1e-9);
        assertNull(parsed.truePeakDb());
    }

    @Test
    void garbageYieldsNothing() {
        assertTrue(LoudnessAnalyzer.parseSummary("ffmpeg: command not found").isEmpty());
        assertTrue(LoudnessAnalyzer.parseSummary(null).isEmpty());
    }
}
