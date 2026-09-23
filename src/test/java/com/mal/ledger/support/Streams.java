package com.mal.ledger.support;

import com.mal.ledger.events.EventStream;
import com.mal.ledger.io.EventStreamStore;
import com.mal.ledger.report.ReportPrinter;
import com.mal.ledger.report.StreamRunResult;
import com.mal.ledger.runner.StreamRunner;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loads the editable stream files and executes them.
 *
 * <p>The tests read from {@code event-streams/} (override with
 * {@code -Dledger.streams.dir=...}), not from the factory, so editing a JSON file is
 * enough to change what the suite runs - no recompilation, no Java changes.
 */
public final class Streams {

    private Streams() {
    }

    public static Path directory() {
        Path dir = EventStreamStore.defaultDirectory();
        assertTrue(Files.isDirectory(dir),
                "Event stream directory not found at " + dir.toAbsolutePath()
                        + " - run `gradle generateStreams` first.");
        return dir;
    }

    /** Every stream file in the directory, including any the user has added by hand. */
    public static List<EventStream> all() {
        return EventStreamStore.readAll(directory());
    }

    public static EventStream load(String id) {
        return EventStreamStore.read(directory().resolve(id + ".json"));
    }

    public static StreamRunResult run(EventStream stream) {
        return new StreamRunner().run(stream);
    }

    /** Load and execute one stream by file id. */
    public static StreamRunResult run(String id) {
        return run(load(id));
    }

    /** Runs a stream and returns the printed day report, so the printer is exercised too. */
    public static String report(StreamRunResult result) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        new ReportPrinter(new PrintStream(buffer, true, StandardCharsets.UTF_8)).print(result);
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
