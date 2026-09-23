package com.mal.ledger.report;

import com.mal.ledger.domain.Transaction;
import com.mal.ledger.engine.EngineError;

import java.time.LocalDate;
import java.util.List;

/**
 * What the run printed for one processing day: closing ledger balances, fee
 * assessments, auth states and errors.
 */
public record DayReport(
        int day,
        LocalDate date,
        List<AccountDayView> accounts,
        List<EngineError> errors,
        List<Transaction> postedToday,
        List<Transaction> systemGenerated,
        int recomputePasses,
        boolean restatedHistory,
        boolean converged) {
}
