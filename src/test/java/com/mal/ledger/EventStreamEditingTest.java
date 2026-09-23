package com.mal.ledger;

import com.mal.ledger.domain.AuthStatus;
import com.mal.ledger.domain.Transaction;
import com.mal.ledger.events.EventStream;
import com.mal.ledger.events.EventStreamFactory;
import com.mal.ledger.io.EventStreamStore;
import com.mal.ledger.io.Json;
import com.mal.ledger.report.StreamRunResult;
import com.mal.ledger.support.StreamAssertions;
import com.mal.ledger.support.Streams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The streams are meant to be edited by hand. These tests prove that the JSON file is
 * the source of truth - the generator writes it, the loader reads it back unchanged, and
 * an edit to the file changes what the suite runs without touching any Java.
 *
 * <p>{@code gradle test --tests '*EventStreamEditingTest'}
 */
@DisplayName("Editing event streams")
class EventStreamEditingTest {

    @Test
    @DisplayName("generator output and the files on disk agree - regenerating changes nothing")
    void filesMatchTheGenerator(@TempDir Path tmp) throws IOException {
        EventStreamStore.writeAll(EventStreamFactory.generateAll(), tmp);
        for (EventStream generated : EventStreamFactory.generateAll()) {
            Path fresh = tmp.resolve(generated.id() + ".json");
            Path shipped = Streams.directory().resolve(generated.id() + ".json");
            assertEquals(Files.readString(shipped, StandardCharsets.UTF_8),
                    Files.readString(fresh, StandardCharsets.UTF_8),
                    generated.id() + " on disk has drifted from the generator - "
                            + "re-run `gradle generateStreams` or keep the hand edit and update the factory");
        }
    }

    @Test
    @DisplayName("a stream survives a write/read round trip with identical behaviour")
    void roundTripsThroughJson(@TempDir Path tmp) {
        for (EventStream original : EventStreamFactory.generateAll()) {
            Path file = tmp.resolve(original.id() + ".json");
            EventStreamStore.write(original, file);
            EventStream reloaded = EventStreamStore.read(file);

            assertEquals(original.events().size(), reloaded.events().size(), original.id());
            assertEquals(original.config().windowDays(), reloaded.config().windowDays());
            assertEquals(original.config().accrualRounding(), reloaded.config().accrualRounding());

            List<String> before = Streams.run(original).engine().journal().stream()
                    .map(Transaction::toString).toList();
            List<String> after = Streams.run(reloaded).engine().journal().stream()
                    .map(Transaction::toString).toList();
            assertEquals(before, after, original.id() + " behaves differently after a round trip");
        }
    }

    @Test
    @DisplayName("editing an amount in the JSON changes the run - no recompilation needed")
    void editingAnAmountChangesTheOutcome(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("edited.json");
        Path source = Streams.directory().resolve("iteration-1-baseline.json");

        // Edit E7 - the back-dated day-2 debit - from 620 down to 100. Plain text edit,
        // exactly what someone would do in an editor.
        String edited = Files.readString(source, StandardCharsets.UTF_8)
                .replace("\"amount\": \"620\"", "\"amount\": \"100\"");
        assertNotEquals(Files.readString(source, StandardCharsets.UTF_8), edited,
                "the edit must actually have matched something");
        Files.writeString(file, edited, StandardCharsets.UTF_8);

        StreamRunResult result = Streams.run(EventStreamStore.read(file));
        assertEquals(0, new BigDecimal("150.00").compareTo(
                        result.day(5).accounts().get(0).history().get(1).closing()),
                "day 2 now closes at 250 - 100 = 150, comfortably positive");
        assertEquals(0, result.engine().overdraftFeeRecordCount("ACC001"),
                "no day goes negative any more, so no fee is ever assessed");
        assertTrue(result.errors().stream().noneMatch(e -> e.eventLabel().equals("E8")),
                "and authB is now approved, because the available balance covers it");
        assertEquals(AuthStatus.APPROVED, result.engine().auths().get("authB").status());
    }

    @Test
    @DisplayName("a hand-written stream with no expectations still runs and holds the invariants")
    void aNewStreamNeedsNoJavaChange(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("my-scratch-stream.json");
        Files.writeString(file, """
                {
                  // Comments are allowed, so a stream can explain itself.
                  "id": "my-scratch-stream",
                  "name": "Hand written",
                  "config": { "windowDays": 3, "capitalizationDay": 3, "weekStartDate": "2026-03-01" },
                  "accounts": [ { "id": "X1", "currency": "AED" } ],
                  "events": [
                    { "label": "A", "day": 1, "account": "X1", "type": "CREDIT", "amount": "1000" },
                    { "label": "B", "day": 2, "account": "X1", "type": "DEBIT", "amount": "1500" }
                  ],
                  "expectations": {
                    "netOverdraftFees": { "X1": "-50.00" },
                    "finalInterest": { "X1": "0.40" }
                  }
                }
                """, StandardCharsets.UTF_8);

        StreamRunResult result = Streams.run(EventStreamStore.read(file));
        StreamAssertions.verify(result);
        assertEquals(0, new BigDecimal("-525.00").compareTo(
                        result.engine().dailyAccount("X1", 2).closingBalance()),
                "1000 - 1500 = -500, plus the 25.00 fee");
        assertEquals(0, new BigDecimal("0.40").compareTo(result.engine().capitalizedInterest("X1")),
                "only day 1 accrues; day 3 is the capitalization day and is excluded");
        assertEquals(2, result.engine().overdraftFeeRecordCount("X1"),
                "day 3 is charged too: the interest credit gives it ledger movement of its "
                        + "own, and it lands on a balance that is still 525 overdrawn");
    }

    @Test
    @DisplayName("every shipped stream file is valid JSON with the keys the loader needs")
    void everyFileIsWellFormed() throws IOException {
        try (var files = Files.list(Streams.directory())) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                Map<String, Object> root = Json.parseObject(
                        Files.readString(file, StandardCharsets.UTF_8));
                assertTrue(root.containsKey("id"), file + " has no id");
                assertTrue(root.containsKey("accounts"), file + " has no accounts");
                assertTrue(root.containsKey("events"), file + " has no events");
                assertEquals(file.getFileName().toString(), Json.str(root, "id") + ".json",
                        "the file name must match the stream id so it can be run by name");
            }
        }
    }
}
