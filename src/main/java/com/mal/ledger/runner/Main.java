package com.mal.ledger.runner;

import com.mal.ledger.events.EventStream;
import com.mal.ledger.events.EventStreamFactory;
import com.mal.ledger.io.EventStreamStore;
import com.mal.ledger.report.ReportPrinter;
import com.mal.ledger.report.StreamRunResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * <pre>
 *   generate [dir]            (re)write the event-stream JSON files
 *   run      [dir] [streamId] execute every stream (or one) and print the day report
 *   list     [dir]            list the streams found in the directory
 * </pre>
 */
public final class Main {

    public static void main(String[] args) {
        String command = args.length > 0 ? args[0] : "run";
        Path dir = args.length > 1 ? Path.of(args[1]) : EventStreamStore.defaultDirectory();

        switch (command) {
            case "generate" -> generate(dir);
            case "list" -> list(dir);
            case "run" -> run(dir, args.length > 2 && args[2] != null && !args[2].isBlank() ? args[2] : null);
            default -> {
                System.err.println("Unknown command: " + command);
                System.err.println("Usage: generate [dir] | run [dir] [streamId] | list [dir]");
                System.exit(2);
            }
        }
    }

    private static void generate(Path dir) {
        List<EventStream> streams = EventStreamFactory.generateAll();
        EventStreamStore.writeAll(streams, dir);
        System.out.printf("Wrote %d event streams to %s%n", streams.size(), dir.toAbsolutePath());
        for (EventStream s : streams) {
            System.out.printf("  %-28s %2d events, %d account(s), %d assertion(s)%n",
                    s.id() + ".json", s.events().size(), s.accounts().size(),
                    s.expectations().assertionCount());
        }
    }

    private static void list(Path dir) {
        for (EventStream s : load(dir)) {
            System.out.printf("  %-28s %s%n", s.id(), s.name());
        }
    }

    private static void run(Path dir, String streamId) {
        List<EventStream> streams = load(dir);
        ReportPrinter printer = new ReportPrinter(System.out);
        StreamRunner runner = new StreamRunner();
        boolean matched = false;
        for (EventStream stream : streams) {
            if (streamId != null && !stream.id().equals(streamId)) continue;
            matched = true;
            StreamRunResult result = runner.run(stream);
            printer.print(result);
        }
        if (!matched) {
            System.err.println("No stream matched " + streamId + " in " + dir.toAbsolutePath());
            System.exit(1);
        }
    }

    private static List<EventStream> load(Path dir) {
        if (!Files.isDirectory(dir)) {
            System.err.printf("No event-stream directory at %s - run 'generate' first.%n", dir.toAbsolutePath());
            System.exit(1);
        }
        List<EventStream> streams = EventStreamStore.readAll(dir);
        if (streams.isEmpty()) {
            System.err.printf("No .json event streams in %s - run 'generate' first.%n", dir.toAbsolutePath());
            System.exit(1);
        }
        return streams;
    }
}
