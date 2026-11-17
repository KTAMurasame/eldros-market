package com.eldros.market.core.effect;

import com.eldros.market.core.storage.StorageException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * Append-only record of economy provider outcomes, independent of the database (docs/03 §7.1). Written the moment
 * a provider call returns, and fsynced, so that after a crash a confirmed withdraw/deposit can be recovered without
 * calling the provider again. Each line carries a CRC; a torn or damaged line reads as "no record" (→ quarantine).
 */
public final class EffectLog implements AutoCloseable {

    public enum Outcome { CONFIRMED, FAILED, UNKNOWN }

    private final Path file;
    private FileChannel channel;

    public EffectLog(Path file) {
        this.file = file;
        this.channel = open(file);
    }

    private static FileChannel open(Path file) {
        try {
            return FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new StorageException("cannot open effect log " + file, e);
        }
    }

    /** Appends and fsyncs one outcome. Runs on the effect thread in production (D7). */
    public synchronized void append(String opId, Outcome outcome) {
        try {
            channel.write(ByteBuffer.wrap(line(opId, outcome).getBytes(StandardCharsets.UTF_8)));
            channel.force(true);
        } catch (IOException e) {
            throw new StorageException("cannot write effect log", e);
        }
    }

    /** Outcome per op id; the last valid line for an op wins. A missing file is empty. */
    public static Map<String, Outcome> read(Path file) {
        Map<String, Outcome> out = new HashMap<>();
        if (!Files.exists(file)) {
            return out;
        }
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] p = raw.split("\\|");
                if (p.length == 3 && crc(p[0] + "|" + p[1]).equals(p[2])) {
                    try {
                        out.put(p[0], Outcome.valueOf(p[1]));
                    } catch (IllegalArgumentException ignored) {
                        // unknown outcome name: treat as no record
                    }
                }
            }
        } catch (IOException e) {
            throw new StorageException("cannot read effect log " + file, e);
        }
        return out;
    }

    /**
     * Rewrites the log keeping only {@code keep} (ops whose journal entry is not terminal yet). Written to a temp
     * file and moved over the original, so a crash during compaction leaves either the old or the new file.
     */
    public synchronized void compact(Set<String> keep) {
        Map<String, Outcome> current = read(file);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            StringBuilder sb = new StringBuilder();
            current.forEach((op, outcome) -> {
                if (keep.contains(op)) {
                    sb.append(line(op, outcome));
                }
            });
            try (FileChannel out = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                out.write(ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.UTF_8)));
                out.force(true);
            }
            channel.close();
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            channel = open(file);
        } catch (IOException e) {
            throw new StorageException("cannot compact effect log", e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            channel.close();
        } catch (IOException e) {
            throw new StorageException("cannot close effect log", e);
        }
    }

    private static String line(String opId, Outcome outcome) {
        String body = opId + "|" + outcome.name();
        return body + "|" + crc(body) + "\n";
    }

    private static String crc(String body) {
        CRC32 c = new CRC32();
        c.update(body.getBytes(StandardCharsets.UTF_8));
        return String.format("%08x", c.getValue());
    }
}
