package com.mal.ledger.report;

import com.mal.ledger.engine.EngineError;
import com.mal.ledger.engine.LedgerEngine;
import com.mal.ledger.events.EventStream;

import java.util.List;

/** Everything a single execution of one event stream produced. */
public record StreamRunResult(EventStream stream, LedgerEngine engine, List<DayReport> days) {

    public DayReport day(int day) {
        return days.stream().filter(d -> d.day() == day).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No report for day " + day));
    }

    public List<EngineError> errors() {
        return engine.errors();
    }

    public int lastDay() {
        return days.isEmpty() ? 0 : days.get(days.size() - 1).day();
    }
}
