package com.mal.ledger.events;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Editable, data-driven assertions carried inside the stream file. Every field is
 * optional - only what is present gets asserted, so a stream can be edited to
 * tighten or loosen its checks without touching Java.
 */
public final class Expectations {

    /** One restated ledger row inside a snapshot. Null fields are not asserted. */
    public record Row(
            int day,
            BigDecimal closing,
            BigDecimal closingExInterest,
            BigDecimal available,
            BigDecimal holds,
            BigDecimal accrual,
            BigDecimal assessmentBalance,
            Boolean overdraft) {
    }

    /** The ledger of one account, as it reads after day {@code afterDay} has closed. */
    public record Snapshot(int afterDay, String accountId, List<Row> rows) {
    }

    public record ErrorExpectation(String eventLabel, String code) {
    }

    /** Asserts the exact set of amounts posted on a day for a given transaction type. */
    public record TransactionExpectation(String accountId, int day, String type, List<BigDecimal> amounts) {
    }

    private final List<Snapshot> snapshots = new ArrayList<>();
    private final Map<String, BigDecimal> finalInterest = new LinkedHashMap<>();
    private final Map<String, String> authStatus = new LinkedHashMap<>();
    private final Map<String, BigDecimal> netOverdraftFees = new LinkedHashMap<>();
    private final Map<String, Integer> overdraftFeeRecordCount = new LinkedHashMap<>();
    private final List<ErrorExpectation> errors = new ArrayList<>();
    private final List<TransactionExpectation> transactions = new ArrayList<>();
    private final Map<Integer, Integer> minRecomputePasses = new LinkedHashMap<>();

    public List<Snapshot> snapshots() { return snapshots; }
    public Map<String, BigDecimal> finalInterest() { return finalInterest; }
    public Map<String, String> authStatus() { return authStatus; }
    public Map<String, BigDecimal> netOverdraftFees() { return netOverdraftFees; }
    public Map<String, Integer> overdraftFeeRecordCount() { return overdraftFeeRecordCount; }
    public List<ErrorExpectation> errors() { return errors; }
    public List<TransactionExpectation> transactions() { return transactions; }
    public Map<Integer, Integer> minRecomputePasses() { return minRecomputePasses; }

    public boolean isEmpty() {
        return snapshots.isEmpty() && finalInterest.isEmpty() && authStatus.isEmpty()
                && netOverdraftFees.isEmpty() && overdraftFeeRecordCount.isEmpty()
                && errors.isEmpty() && transactions.isEmpty() && minRecomputePasses.isEmpty();
    }

    public int assertionCount() {
        int n = finalInterest.size() + authStatus.size() + netOverdraftFees.size()
                + overdraftFeeRecordCount.size() + errors.size() + transactions.size()
                + minRecomputePasses.size();
        for (Snapshot s : snapshots) {
            for (Row r : s.rows()) {
                if (r.closing() != null) n++;
                if (r.closingExInterest() != null) n++;
                if (r.available() != null) n++;
                if (r.holds() != null) n++;
                if (r.accrual() != null) n++;
                if (r.assessmentBalance() != null) n++;
                if (r.overdraft() != null) n++;
            }
        }
        return n;
    }
}
