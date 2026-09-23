package com.mal.ledger.engine;

import com.mal.ledger.domain.Account;
import com.mal.ledger.domain.Auth;
import com.mal.ledger.domain.AuthStatus;
import com.mal.ledger.domain.Currency;
import com.mal.ledger.domain.DailyAccount;
import com.mal.ledger.domain.Money;
import com.mal.ledger.domain.Transaction;
import com.mal.ledger.domain.TransactionType;
import com.mal.ledger.events.AccountSpec;
import com.mal.ledger.events.LedgerEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * In-memory, append-only account ledger. No persistence, no database, no UI.
 *
 * <p>Invariants:
 * <ul>
 *   <li>{@code journal} is append only. Nothing is ever updated or removed; a correction
 *       is a new REVERSAL / *_REVERSAL record referencing the record it undoes.</li>
 *   <li>Daily accounts are a <em>derived projection</em>, rebuilt from the journal on every
 *       reconciliation, so they can never drift from the records.</li>
 *   <li>Precision and currency travel with the account; every stored amount is rounded
 *       correlatively at store time.</li>
 * </ul>
 */
public final class LedgerEngine {

    private final LedgerConfig config;

    // ---- in-memory data --------------------------------------------------
    /** Account details hash grouped by id. */
    private final Map<String, Account> accounts = new LinkedHashMap<>();
    /** The append-only journal. */
    private final List<Transaction> journal = new ArrayList<>();
    /** Transactions grouped by account id. */
    private final Map<String, List<Transaction>> byAccount = new LinkedHashMap<>();
    /** Transactions grouped by value date. */
    private final NavigableMap<LocalDate, List<Transaction>> byValueDate = new TreeMap<>();
    /** Transactions grouped by the event that produced them. */
    private final Map<String, List<Transaction>> byEventLabel = new LinkedHashMap<>();
    /** Auth hash grouped by id. */
    private final Map<String, Auth> auths = new LinkedHashMap<>();
    /** Daily accounts hash grouped by account then date. */
    private final Map<String, NavigableMap<LocalDate, DailyAccount>> dailyAccounts = new LinkedHashMap<>();

    private final List<EngineError> errors = new ArrayList<>();
    private int transactionSeq = 0;

    public LedgerEngine(LedgerConfig config) {
        this.config = config;
    }

    public LedgerConfig config() {
        return config;
    }

    // =====================================================================
    // Account setup
    // =====================================================================

    public Account openAccount(AccountSpec spec) {
        Account account = new Account(spec.id(), spec.currency(), config.weekStartDate(),
                config.overdraftFeeFor(spec.currency()));
        accounts.put(account.id(), account);
        byAccount.put(account.id(), new ArrayList<>());
        dailyAccounts.put(account.id(), new TreeMap<>());
        return account;
    }

    public Account account(String id) {
        return accounts.get(id);
    }

    public Map<String, Account> accounts() {
        return Collections.unmodifiableMap(accounts);
    }

    public Map<String, Auth> auths() {
        return Collections.unmodifiableMap(auths);
    }

    public List<Transaction> journal() {
        return Collections.unmodifiableList(journal);
    }

    public List<EngineError> errors() {
        return Collections.unmodifiableList(errors);
    }

    public DailyAccount dailyAccount(String accountId, LocalDate date) {
        NavigableMap<LocalDate, DailyAccount> map = dailyAccounts.get(accountId);
        return map == null ? null : map.get(date);
    }

    public DailyAccount dailyAccount(String accountId, int day) {
        return dailyAccount(accountId, config.dateOfDay(day));
    }

    public NavigableMap<LocalDate, DailyAccount> dailyAccounts(String accountId) {
        return dailyAccounts.getOrDefault(accountId, new TreeMap<>());
    }

    // =====================================================================
    // Append to transactions
    // =====================================================================

    /**
     * The single write path into the ledger. Rounds to the account's currency
     * precision at store time and updates every in-memory index.
     */
    private Transaction append(String accountId, TransactionType type, BigDecimal rawSigned,
                               LocalDate valueDate, LocalDate postedDate,
                               String sourceLabel, String reference, String tag) {
        Account account = accounts.get(accountId);
        BigDecimal stored = Money.store(rawSigned, account.currency(), config.storeRounding());
        Transaction txn = new Transaction(
                "T" + (++transactionSeq), accountId, type, stored, valueDate, postedDate,
                sourceLabel, reference, tag);
        journal.add(txn);
        byAccount.get(accountId).add(txn);
        byValueDate.computeIfAbsent(valueDate, d -> new ArrayList<>()).add(txn);
        if (sourceLabel != null) {
            byEventLabel.computeIfAbsent(sourceLabel, l -> new ArrayList<>()).add(txn);
        }
        return txn;
    }

    private void reject(int day, String eventLabel, String accountId, ErrorCode code, String message) {
        errors.add(new EngineError(day, config.dateOfDay(day), eventLabel, accountId, code, message));
    }

    // =====================================================================
    // Balances
    // =====================================================================

    /** Sum of every record with value_date <= asOf. The ledger balance. */
    public BigDecimal ledgerBalanceAsOf(String accountId, LocalDate asOf) {
        Account account = accounts.get(accountId);
        BigDecimal sum = Money.zero(account.currency());
        for (Transaction t : byAccount.get(accountId)) {
            if (!t.valueDate().isAfter(asOf)) {
                sum = sum.add(t.signedAmount());
            }
        }
        return sum;
    }

    /** Sum of approved-and-unsettled holds in force on the given date. */
    public BigDecimal activeHoldsOn(String accountId, LocalDate date) {
        Account account = accounts.get(accountId);
        BigDecimal sum = Money.zero(account.currency());
        for (Auth auth : auths.values()) {
            if (auth.accountId().equals(accountId) && auth.isActive()) {
                sum = sum.add(auth.holdAmount());
            }
        }
        return sum;
    }

    /** Holds reduce available balance, never the ledger balance. */
    public BigDecimal availableBalanceAsOf(String accountId, LocalDate date) {
        return ledgerBalanceAsOf(accountId, date).subtract(activeHoldsOn(accountId, date));
    }

    private List<Transaction> transactionsOn(String accountId, LocalDate date) {
        List<Transaction> out = new ArrayList<>();
        for (Transaction t : byValueDate.getOrDefault(date, List.of())) {
            if (t.accountId().equals(accountId)) out.add(t);
        }
        return out;
    }

    // =====================================================================
    // Event application
    // =====================================================================

    /**
     * Applies one instruction. Returns true if the instruction produced a ledger
     * record with a back-dated value date, which means history must be reconciled
     * before the next instruction is decided.
     */
    public boolean apply(LedgerEvent event) {
        if (!accounts.containsKey(event.accountId())) {
            reject(event.postingDay(), event.label(), event.accountId(), ErrorCode.UNKNOWN_ACCOUNT,
                    "No such account");
            return false;
        }
        return switch (event.type()) {
            case CREDIT -> simple(event, TransactionType.CREDIT);
            case DEBIT -> simple(event, TransactionType.DEBIT);
            case INSTALLMENT_CREDIT -> installmentCredit(event);
            case AUTH -> { authorize(event); yield false; }
            case SETTLEMENT -> settle(event);
            case REVERSAL -> reverse(event);
        };
    }

    private boolean simple(LedgerEvent event, TransactionType type) {
        if (event.amount() == null || event.amount().signum() <= 0) {
            reject(event.postingDay(), event.label(), event.accountId(), ErrorCode.INVALID_AMOUNT,
                    "Amount must be positive");
            return false;
        }
        BigDecimal signed = event.amount().multiply(BigDecimal.valueOf(type.sign()));
        append(event.accountId(), type, signed, config.dateOfDay(event.valueDay()),
                config.dateOfDay(event.postingDay()), event.label(), null, null);
        return event.isBackdated();
    }

    /**
     * Splits a credit into N installments. Each slice is rounded DOWN so it is always
     * {@code <= total/N}; the remainder never vanishes - it is posted as a separate
     * BALANCING_ADJUSTMENT tagged BUFFER_PAY, so the installments plus the buffer
     * always reconstitute the exact total.
     */
    private boolean installmentCredit(LedgerEvent event) {
        Account account = accounts.get(event.accountId());
        Currency currency = account.currency();
        int n = Math.max(1, event.installments());
        BigDecimal total = Money.store(event.amount(), currency, config.storeRounding());
        BigDecimal slice = total.divide(BigDecimal.valueOf(n), currency.precision(), config.installmentRounding());
        LocalDate valueDate = config.dateOfDay(event.valueDay());
        LocalDate postedDate = config.dateOfDay(event.postingDay());
        for (int i = 0; i < n; i++) {
            append(event.accountId(), TransactionType.INSTALLMENT_CREDIT, slice, valueDate, postedDate,
                    event.label(), "installment " + (i + 1) + "/" + n, null);
        }
        BigDecimal remainder = total.subtract(slice.multiply(BigDecimal.valueOf(n)));
        if (remainder.signum() != 0) {
            append(event.accountId(), TransactionType.BALANCING_ADJUSTMENT, remainder, valueDate, postedDate,
                    event.label(), "installment remainder", "BUFFER_PAY");
        }
        return event.isBackdated();
    }

    /**
     * Authorization request. Approved only when
     * {@code ledger balance - active holds - requested >= 0}.
     */
    private void authorize(LedgerEvent event) {
        LocalDate decisionDate = config.dateOfDay(event.postingDay());
        LocalDate holdValueDate = config.dateOfDay(event.valueDay());
        if (auths.containsKey(event.authId())) {
            reject(event.postingDay(), event.label(), event.accountId(), ErrorCode.DUPLICATE_AUTH_ID,
                    "Auth id " + event.authId() + " already exists");
            return;
        }
        BigDecimal available = availableBalanceAsOf(event.accountId(), decisionDate);
        BigDecimal headroom = available.subtract(event.amount());
        boolean approved = headroom.signum() >= 0;
        Auth auth = new Auth(event.authId(), event.accountId(),
                Money.store(event.amount(), accounts.get(event.accountId()).currency(), config.storeRounding()),
                holdValueDate, decisionDate,
                approved ? AuthStatus.APPROVED : AuthStatus.REJECTED,
                approved ? "available " + available.toPlainString() + " covers hold"
                        : "available " + available.toPlainString() + " short by " + headroom.abs().toPlainString());
        auths.put(auth.id(), auth);
        if (!approved) {
            reject(event.postingDay(), event.label(), event.accountId(),
                    ErrorCode.INSUFFICIENT_AVAILABLE_BALANCE,
                    "Hold " + event.amount().toPlainString() + " refused: available balance "
                            + available.toPlainString());
        }
    }

    /**
     * Settlement validation. An unknown auth id is rejected outright and no funds
     * leave the account - a settlement instruction is not, on its own, authority to move money.
     */
    private boolean settle(LedgerEvent event) {
        LocalDate valueDate = config.dateOfDay(event.valueDay());
        LocalDate postedDate = config.dateOfDay(event.postingDay());
        Auth auth = auths.get(event.authId());

        if (auth == null) {
            reject(event.postingDay(), event.label(), event.accountId(), ErrorCode.UNKNOWN_AUTH,
                    "Settlement quotes unknown auth " + event.authId() + "; funds not released");
            return false;
        }
        if (auth.status() == AuthStatus.REJECTED) {
            reject(event.postingDay(), event.label(), event.accountId(), ErrorCode.AUTH_NOT_APPROVED,
                    "Auth " + auth.id() + " was never approved");
            return false;
        }
        if (auth.status() == AuthStatus.SETTLED) {
            reject(event.postingDay(), event.label(), event.accountId(), ErrorCode.AUTH_ALREADY_SETTLED,
                    "Auth " + auth.id() + " already settled for " + auth.settledAmount().toPlainString());
            return false;
        }
        if (event.amount().compareTo(auth.holdAmount()) > 0) {
            reject(event.postingDay(), event.label(), event.accountId(), ErrorCode.SETTLEMENT_EXCEEDS_AUTH,
                    "Settlement " + event.amount().toPlainString() + " exceeds hold "
                            + auth.holdAmount().toPlainString());
            return false;
        }
        BigDecimal ledger = ledgerBalanceAsOf(event.accountId(), postedDate);
        // if (event.amount().compareTo(ledger) > 0) {
        //     reject(event.postingDay(), event.label(), event.accountId(), ErrorCode.INSUFFICIENT_LEDGER_BALANCE,
        //             "Settlement " + event.amount().toPlainString() + " exceeds ledger balance "
        //                     + ledger.toPlainString());
        //     return false;
        // }
        append(event.accountId(), TransactionType.SETTLEMENT, event.amount().negate(), valueDate, postedDate,
                event.label(), auth.id(), null);
        auth.settle(Money.store(event.amount(), accounts.get(event.accountId()).currency(), config.storeRounding()),
                postedDate);
        return event.isBackdated();
    }

    /** Append-only correction: mirrors every record the target event produced. */
    private boolean reverse(LedgerEvent event) {
        List<Transaction> targets = byEventLabel.get(event.targetLabel());
        if (targets == null || targets.isEmpty()) {
            reject(event.postingDay(), event.label(), event.accountId(), ErrorCode.UNKNOWN_REVERSAL_TARGET,
                    "No ledger record produced by " + event.targetLabel());
            return false;
        }
        List<Transaction> toReverse = new ArrayList<>();
        for (Transaction t : targets) {
            if (isAlreadyReversed(t)) continue;
            toReverse.add(t);
        }
        if (toReverse.isEmpty()) {
            reject(event.postingDay(), event.label(), event.accountId(), ErrorCode.ALREADY_REVERSED,
                    event.targetLabel() + " has already been reversed");
            return false;
        }
        LocalDate postedDate = config.dateOfDay(event.postingDay());
        boolean backdated = false;
        for (Transaction t : toReverse) {
            LocalDate valueDate = config.dateOfDay(event.valueDay());
            append(t.accountId(), TransactionType.REVERSAL, t.signedAmount().negate(), valueDate, postedDate,
                    event.label(), t.id(), "reverses " + event.targetLabel());
            backdated |= valueDate.isBefore(postedDate);
        }
        return backdated;
    }

    private boolean isAlreadyReversed(Transaction target) {
        for (Transaction t : byAccount.get(target.accountId())) {
            if (t.type() == TransactionType.REVERSAL && target.id().equals(t.reference())) {
                return true;
            }
        }
        return false;
    }

    // =====================================================================
    // Daily accounts calc + reconciliation
    // =====================================================================

    /**
     * Intra-day reconciliation, used right after a back-dated record lands so the next
     * instruction is decided against a consistent ledger. Only days strictly before the
     * current one are fee-assessed - the current day is assessed when it closes.
     */
    public RecomputeResult reconcileIntraDay(int day) {
        return reconcile(config.dateOfDay(day), config.dateOfDay(day).minusDays(1), false);
    }

    /** End-of-day calculation: rebuild, assess fees through today, capitalize if due. */
    public RecomputeResult closeDay(int day) {
        LocalDate asOf = config.dateOfDay(day);
        RecomputeResult result = reconcile(asOf, asOf, day >= config.capitalizationDay());
        Account any = accounts.values().stream().findFirst().orElse(null);
        if (any != null && !result.converged()) {
            reject(day, "SYS", any.id(), ErrorCode.RECOMPUTE_DID_NOT_CONVERGE,
                    "Ledger did not settle within " + config.maxRecomputePasses() + " passes");
        }
        return result;
    }

    private RecomputeResult reconcile(LocalDate asOf, LocalDate assessThrough, boolean capitalize) {
        int journalMark = journal.size();
        int passes = 0;
        boolean changed;
        do {
            passes++;
            changed = rebuildProjections(asOf, assessThrough);
            if (capitalize) {
                changed |= reconcileCapitalization(asOf);
            }
        } while (changed && passes < config.maxRecomputePasses());
        List<Transaction> generated = new ArrayList<>(journal.subList(journalMark, journal.size()));
        return new RecomputeResult(passes, !changed, generated);
    }

    /**
     * Rebuilds every daily account from the journal and assesses the overdraft fee.
     *
     * <p>A day is judged on its <em>assessment balance</em>: the running balance including
     * every earlier day's fees but excluding its own. That makes the decision idempotent -
     * applying a fee can never flip the day back to solvent - so a single forward walk
     * settles the whole week and the cascade terminates.
     *
     * <p>Days with no ledger movement of their own are not separately assessed; they carry
     * the previous day's balance forward. See {@code overdraftFeeOnDaysWithoutMovement}.
     *
     * @return true if this pass appended any record (another pass is then needed)
     */
    private boolean rebuildProjections(LocalDate asOf, LocalDate assessThrough) {
        boolean changed = false;
        for (Account account : accounts.values()) {
            Currency currency = account.currency();
            NavigableMap<LocalDate, DailyAccount> days = dailyAccounts.get(account.id());
            BigDecimal running = Money.zero(currency);

            for (LocalDate d = config.weekStartDate(); !d.isAfter(asOf); d = d.plusDays(1)) {
                List<Transaction> txns = transactionsOn(account.id(), d);
                BigDecimal nonFee = Money.zero(currency);
                BigDecimal feeNet = Money.zero(currency);
                boolean hasMovement = false;
                Transaction liveFee = null;
                for (Transaction t : txns) {
                    if (t.type().isOverdraftFeeRecord()) {
                        feeNet = feeNet.add(t.signedAmount());
                        if (t.type() == TransactionType.OVERDRAFT_FEE) liveFee = t;
                    } else {
                        nonFee = nonFee.add(t.signedAmount());
                        hasMovement = true;
                    }
                }

                BigDecimal preFee = running.add(nonFee);
                BigDecimal assessmentBalance = config.overdraftAssessmentExcludesOwnDayFee()
                        ? preFee
                        : preFee.add(feeNet);
                boolean feeActive = feeNet.signum() < 0;
                boolean assessable = !d.isAfter(assessThrough)
                        && (hasMovement || config.overdraftFeeOnDaysWithoutMovement());

                if (assessable) {
                    BigDecimal fee = account.overdraftFee();
                    if (assessmentBalance.signum() < 0 && !feeActive) {
                        append(account.id(), TransactionType.OVERDRAFT_FEE, fee.negate(), d, asOf,
                                "SYS", "overdraft " + d, "OVERDRAFT");
                        feeNet = feeNet.subtract(fee);
                        feeActive = true;
                        changed = true;
                    } else if (assessmentBalance.signum() >= 0 && feeActive) {
                        append(account.id(), fee, d, asOf,
                                "SYS", liveFee == null ? "overdraft " + d : liveFee.id(), "OVERDRAFT_REVERSAL");
                        feeNet = feeNet.add(fee);
                        feeActive = false;
                        changed = true;
                    }
                }

                BigDecimal closing = preFee.add(feeNet);
                BigDecimal rawAccrual = closing.signum() > 0
                        ? closing.multiply(config.dailyInterestRate())
                        : BigDecimal.ZERO;
                BigDecimal accrual = closing.signum() > 0
                        ? Money.accrue(rawAccrual, currency, config.accrualRounding())
                        : Money.zero(currency);

                DailyAccount daily = days.computeIfAbsent(d,
                        key -> new DailyAccount(account.id(), currency, key));
                daily.setOpeningBalance(running);
                daily.setAssessmentBalance(assessmentBalance);
                daily.setClosingBalance(closing);
                daily.setOverdraftEnabled(feeActive);
                daily.setInterestAccrual(accrual);
                daily.setRawInterestAccrual(rawAccrual.setScale(12, RoundingMode.HALF_UP));
                BigDecimal holds = activeHoldsOn(account.id(), d);
                daily.setActiveHoldsTotal(holds);
                daily.setAvailableBalance(closing.subtract(holds));
                daily.replaceTransactions(transactionsOn(account.id(), d));

                running = closing;
            }
            account.setClosing(running, asOf);
        }
        return changed;
    }

    // =====================================================================
    // Interest accrual and capitalization
    // =====================================================================

    /** Sum of the stored daily accruals over the capitalization window. */
    public BigDecimal accruedInterest(String accountId) {
        Account account = accounts.get(accountId);
        LocalDate end = accrualWindowEnd();
        BigDecimal sum = Money.zero(account.currency());
        for (Map.Entry<LocalDate, DailyAccount> e : dailyAccounts.get(accountId).entrySet()) {
            if (!e.getKey().isAfter(end)) sum = sum.add(e.getValue().interestAccrual());
        }
        return sum;
    }

    private BigDecimal rawAccruedInterest(String accountId) {
        LocalDate end = accrualWindowEnd();
        BigDecimal sum = BigDecimal.ZERO;
        for (Map.Entry<LocalDate, DailyAccount> e : dailyAccounts.get(accountId).entrySet()) {
            if (!e.getKey().isAfter(end)) sum = sum.add(e.getValue().rawInterestAccrual());
        }
        return sum;
    }

    private LocalDate accrualWindowEnd() {
        LocalDate capDate = config.capitalizationDate();
        return config.capitalizationExcludesOwnDay() ? capDate.minusDays(1) : capDate;
    }

    /** Interest already capitalized (net of any capitalization reversals). */
    public BigDecimal capitalizedInterest(String accountId) {
        Account account = accounts.get(accountId);
        BigDecimal sum = Money.zero(account.currency());
        for (Transaction t : byAccount.get(accountId)) {
            if (t.type().isInterestRecord()) sum = sum.add(t.signedAmount());
        }
        return sum;
    }

    /**
     * Brings the capitalized interest in line with the (possibly restated) accrual
     * history. When a late reversal changes an earlier day's balance the answer is
     * <em>not</em> a patch entry: the old capitalization is reversed in full and the
     * newly computed total is credited, keeping the audit trail append-only.
     */
    private boolean reconcileCapitalization(LocalDate asOf) {
        LocalDate capDate = config.capitalizationDate();
        if (asOf.isBefore(capDate)) return false;
        boolean changed = false;
        for (Account account : accounts.values()) {
            Currency currency = account.currency();
            BigDecimal expected = config.discardAccrualRemainder()
                    // Rejected reading: recompute from raw daily figures and drop the remainder,
                    // so the stored dailies no longer reconcile to the capitalized total.
                    ? Money.accrue(rawAccruedInterest(account.id()), currency, RoundingMode.DOWN)
                    // Chosen reading: the capitalized total is exactly the sum of the stored
                    // daily accruals, rounded at store time. Nothing is discarded.
                    : Money.store(accruedInterest(account.id()), currency, config.storeRounding());
            BigDecimal posted = capitalizedInterest(account.id());
            if (expected.compareTo(posted) == 0) continue;

            if (posted.signum() != 0) {
                append(account.id(), TransactionType.INTEREST_CAPITALIZATION_REVERSAL, posted.negate(),
                        capDate, asOf, "SYS", "restated interest", "INTEREST_REVERSAL");
            }
            if (expected.signum() != 0) {
                append(account.id(), TransactionType.INTEREST_CAPITALIZATION, expected,
                        capDate, asOf, "SYS", "capitalized accrual", "INTEREST");
            }
            changed = true;
        }
        return changed;
    }

    // =====================================================================
    // Reporting helpers
    // =====================================================================

    public BigDecimal netOverdraftFees(String accountId) {
        Account account = accounts.get(accountId);
        BigDecimal sum = Money.zero(account.currency());
        for (Transaction t : byAccount.get(accountId)) {
            if (t.type().isOverdraftFeeRecord()) sum = sum.add(t.signedAmount());
        }
        return sum;
    }

    public long overdraftFeeRecordCount(String accountId) {
        return byAccount.get(accountId).stream()
                .filter(t -> t.type() == TransactionType.OVERDRAFT_FEE)
                .count();
    }

    public List<Transaction> transactionsFor(String accountId) {
        return Collections.unmodifiableList(byAccount.get(accountId));
    }

    public List<Transaction> transactionsPostedOn(LocalDate date) {
        List<Transaction> out = new ArrayList<>();
        for (Transaction t : journal) {
            if (t.postedDate().equals(date)) out.add(t);
        }
        return out;
    }

    public List<EngineError> errorsOn(int day) {
        List<EngineError> out = new ArrayList<>();
        for (EngineError e : errors) {
            if (e.day() == day) out.add(e);
        }
        return out;
    }
}
