package com.mal.ledger.engine;

import java.time.LocalDate;

/**
 * A rejected or refused instruction. Recorded, never thrown, so a stream keeps
 * running and the day report can print it.
 */
public record EngineError(
        int day,
        LocalDate date,
        String eventLabel,
        String accountId,
        ErrorCode code,
        String message) {

    @Override
    public String toString() {
        return "[day %d] %s %s %s - %s".formatted(day, eventLabel, accountId, code, message);
    }
}
