package com.eldros.market.spike;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.logging.Logger;

/** Appends spike results to plugins/EldrosSpike/report-<mc>.txt and mirrors them to the console. */
final class Report {

    private final Path file;
    private final Logger logger;

    Report(Path dataFolder, String mcVersion, Logger logger) {
        this.file = dataFolder.resolve("report-" + mcVersion + ".txt");
        this.logger = logger;
    }

    synchronized void line(String text) {
        logger.info("[SPIKE] " + text);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Instant.now() + " " + text + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.severe("[SPIKE] could not write report: " + e);
        }
    }

    void section(String title) {
        line("==== " + title + " ====");
    }
}
