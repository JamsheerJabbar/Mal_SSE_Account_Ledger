package com.mal.ledger.engine;

import com.mal.ledger.domain.Transaction;

import java.util.List;

/**
 * Outcome of one reconciliation. {@code passes} is the number of full ledger
 * rebuilds it took to reach a fixed point - the "two three loops of calculation"
 * Notes.md warns about when a day-7 reversal restates day 2.
 */
public record RecomputeResult(int passes, boolean converged, List<Transaction> generated) {

    public static RecomputeResult none() {
        return new RecomputeResult(0, true, List.of());
    }
}
