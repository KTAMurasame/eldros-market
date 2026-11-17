package com.eldros.market.core.effect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;

import static com.eldros.market.core.effect.EffectLog.Outcome.CONFIRMED;
import static com.eldros.market.core.effect.EffectLog.Outcome.FAILED;
import static com.eldros.market.core.effect.EffectLog.Outcome.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;

class EffectLogTest {

    @TempDir
    Path dir;

    @Test
    void appendedOutcomesAreReadBackAfterReopening() {
        Path file = dir.resolve("effects.log");
        try (EffectLog log = new EffectLog(file)) {
            log.append("op-1", CONFIRMED);
            log.append("op-2", FAILED);
            log.append("op-3", UNKNOWN);
        }

        assertThat(EffectLog.read(file)).containsExactlyInAnyOrderEntriesOf(Map.of("op-1", CONFIRMED, "op-2", FAILED, "op-3", UNKNOWN));
    }

    // V-W-4 / D-W-4: a crash in the middle of a write leaves a torn last line; it must read as "no record".
    @Test
    void aTornLastLineCountsAsNoRecord() throws IOException {
        Path file = dir.resolve("effects.log");
        try (EffectLog log = new EffectLog(file)) {
            log.append("op-1", CONFIRMED);
        }
        Files.writeString(file, "op-2|CONFIRM", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        assertThat(EffectLog.read(file)).containsOnlyKeys("op-1");
    }

    // Break: a damaged line is trusted and a provider call that never succeeded is treated as CONFIRMED.
    @Test
    void aLineWithAWrongChecksumIsIgnored() throws IOException {
        Path file = dir.resolve("effects.log");
        Files.writeString(file, "op-9|CONFIRMED|00000000\n", StandardCharsets.UTF_8);

        assertThat(EffectLog.read(file)).isEmpty();
    }

    @Test
    void aMissingFileReadsAsEmpty() {
        assertThat(EffectLog.read(dir.resolve("absent.log"))).isEmpty();
    }

    // Compaction must never lose an entry that is still needed, even across a reopen.
    @Test
    void compactionKeepsOnlyTheRequestedOperations() {
        Path file = dir.resolve("effects.log");
        try (EffectLog log = new EffectLog(file)) {
            log.append("op-1", CONFIRMED);
            log.append("op-2", FAILED);
            log.compact(Set.of("op-2"));
            log.append("op-3", CONFIRMED);
        }

        assertThat(EffectLog.read(file)).containsExactlyInAnyOrderEntriesOf(Map.of("op-2", FAILED, "op-3", CONFIRMED));
    }
}
