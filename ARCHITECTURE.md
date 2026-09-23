# ARCHITECTURE.md

How the ledger is built, why it is built that way, and what each choice costs. The
problem statement and worked figures live in [`Notes.md`](Notes.md); the individual
rounding/assessment choices are argued in [`AMBIGUITIES.md`](AMBIGUITIES.md) and the
refused criteria in [`REJECTED.md`](REJECTED.md). This document covers the structure
underneath all of them.

## 1. Shape of the system

```
event-streams/*.json ──► EventStreamStore ──► EventStream ──► StreamRunner ──► LedgerEngine
   (source of truth)        (io/Json)         (events/)        (runner/)        (engine/)
                                                                   │                 │
                                                                   ▼                 ▼
                                                             DayReport  ◄──  journal + daily projections
                                                            ReportPrinter
```

| Package | Responsibility |
|---|---|
| `domain/` | Value types and records: `Account`, `Transaction`, `Auth`, `DailyAccount`, `Currency`, `Money`. No business rules beyond rounding. |
| `engine/` | `LedgerEngine` - the whole ledger: the write path, balances, authorizations, settlements, reversals, overdraft assessment, interest. `LedgerConfig` holds every tunable rule. |
| `events/` | The instruction model (`LedgerEvent`, `EventStream`, `Expectations`) and `EventStreamFactory`, which generates the five shipped streams. |
| `io/` | A small hand-written JSON codec and the stream reader/writer. |
| `runner/` | `StreamRunner` walks a stream day by day; `Main` is the CLI. |
| `report/` | Immutable per-day snapshots (`DayReport`, `AccountDayView`) and the printer. |

Dependencies point one way: `runner → engine → domain`, `io → events → domain`. The
engine knows nothing about files, JSON or printing.

## 2. Core decisions

### 2.1 Append-only journal + derived daily projection

**Decision.** There is exactly one source of truth: `LedgerEngine.journal`, a list that is
only ever appended to through one private method, `append(...)`. Every other structure -
the per-account and per-value-date indexes, and the `DailyAccount` rows - is derived from
it. Daily accounts are not incrementally patched; `rebuildProjections` recomputes every
day from the week start on every reconciliation pass.

**Why.** Notes.md makes append-only non-negotiable, and back-dated events (a day-5 debit
valued day 2, a day-6 reversal valued day 2) mean any day's figures can change after the
fact. Rebuilding from the journal makes drift impossible: if the journal is right, the
projection is right. `AllEventStreamsTest` asserts that every stream's projections
reconcile to the journal sum.

**Trade-off.** Cost is O(days × transactions) per pass, and a pass can repeat several
times. For a 6-8 day in-memory window with tens of records this is irrelevant; for a real
ledger with years of history it would not be, and you would snapshot closed periods and
only rebuild from the earliest affected value date. Chosen correctness and simplicity over
performance that the problem does not need.

### 2.2 Corrections are new records, never edits - except overdraft fees, which are never corrected at all

**Decision.** A customer reversal is a `REVERSAL` record that mirrors each record the
target event produced (`byEventLabel`), with `reference` pointing at the original.
System corrections are typed: `INTEREST_CAPITALIZATION_REVERSAL` undoes an interest credit.
A record can be reversed once (`ALREADY_REVERSED`).

The one deliberate exception is `OVERDRAFT_FEE`: there is no `OVERDRAFT_FEE_REVERSAL` type
at all. Once `rebuildProjections` charges a day a fee, that fee stands regardless of what
later recomputation shows - even a back-dated reversal that undoes the very transaction
that caused it. See REJECTED.md R2.

**Why.** Everywhere else, reverse-and-recredit keeps the ledger internally consistent with
its own restated history: the interest an account holds always equals what its current
accrual history says it should. Overdraft fees deliberately do not follow that rule - a
fee is a fact about the ledger *as it stood when it was charged*, not a projection that
should track every later correction. Notes.md's own criterion settles it: "transactions
are immutable, non deletable, so whichever [record is] only reversed will be affected,
and subsequent interest [is] calculated, but overdraft will remain as is."

**Trade-off.** This makes overdraft assessment strictly monotone (a fee is only ever
added, never removed), which is simpler and guarantees termination on its own - no
`OVERDRAFT_FEE_REVERSAL` branch to trigger an extra pass. The cost is that the ledger can
permanently charge for an overdraft that, on the restated history, never really
happened: in the baseline stream, reversing the debit that caused two fees does not
recover the 50 AED they cost, and in iteration 5 that permanent 25 AED is still missing
three days later, refusing a hold (authC) that would otherwise have cleared. Interest
does not have this problem, because interest is *credited*, not charged - restating it
upward costs the ledger nothing it can dispute; restating a fee away would mean handing
back money already assessed as owed.

Reversal is also by event label, not by transaction id, so reversing an installment
credit reverses all its slices and the `BUFFER_PAY` remainder together. That is the right
unit for a customer instruction, but partial reversal of one slice is not expressible.

### 2.3 Restating interest: reverse-and-recredit, not patch

**Decision.** When a late event changes an earlier day's accrual after interest was
already capitalized, `reconcileCapitalization` reverses the posted total in full and
credits the newly computed total, rather than posting a delta.

**Why.** Notes.md's own resolution ("reverse old interest amount and add new interest").
Each credit is then independently explainable against the accrual history that produced
it; a delta entry would only be explainable against the difference of two histories.

**Trade-off.** More records in the journal (two per restatement instead of one), and
readers must net `INTEREST_CAPITALIZATION` against its reversals to get the current figure
(`capitalizedInterest()` does this).

### 2.4 Reconciliation as a fixed-point loop

**Decision.** `reconcile()` repeats *rebuild projections → assess overdraft → reconcile
interest* until a pass appends nothing, capped at `maxRecomputePasses` (16). Non-
convergence is reported as `RECOMPUTE_DID_NOT_CONVERGE` rather than looping forever.
The pass count is surfaced in every day report and asserted in iteration 2.

**Why.** Notes.md calls out the cascade explicitly: a late reversal changes a balance,
which triggers a new fee, which changes the next day's balance and the interest, which can
trigger another fee further on. Rather than hand-code that sequence, the loop runs until
the ledger is self-consistent, and makes the "two three loops" visible instead of hidden.

**Why it terminates.** Overdraft assessment is strictly monotone: a fee, once charged, is
never removed (§2.2), so a pass can only ever *add* fee records, never take one away,
against a finite number of days - it cannot oscillate. `overdraftAssessmentExcludesOwnDayFee`
still decides whether a day is judged on its balance before or after its own just-created
fee, but no longer affects whether the loop terminates, only what a day's displayed
`assessmentBalance` reads once it already carries one (`AmbiguityChoicesTest
.assessmentExcludesOwnDayFee`). The loop still repeats beyond a single pass only because
interest capitalization is itself ledger movement on the capitalization day, which can
tip that one day negative and trigger its fee. The cap is a safety net, not the
termination argument.

**Trade-off.** None left on the overdraft side - permanence makes this unconditionally
monotone. The interest side keeps the earlier caveat: correctness there depends on
`reconcileCapitalization` converging to a fixed total, which it does (net capitalized
interest always equals the current accrual history), but a future rule that made the
accrual window itself depend on the capitalized amount would reintroduce oscillation; the
cap would then turn it into an error rather than a hang.

### 2.5 Two reconciliation points per day

**Decision.** `StreamRunner` applies each day's events in declaration order (ACC001 then
ACC002). After any event whose value date is earlier than its posting date it runs
`reconcileIntraDay`, which rebuilds and assesses fees only for days *before* today. At the
end of the day `closeDay` assesses through today and, from the capitalization day on,
reconciles interest.

**Why.** Authorizations are decided against the balance at that moment. In the baseline,
E7 (debit valued day 2) lands on day 5 before E8 (authB); authB must see the day-2 fee and
the resulting -205 available balance to be refused. Without the intra-day pass it would be
decided against a stale ledger. Today is not assessed intra-day because its movement is
not complete yet.

**Trade-off.** Decisions are order-dependent within a day. That is realistic (it is how
a real processing queue behaves) but it means reordering events in a stream file can
change outcomes. Decisions already taken are not revisited: a refused auth stays refused
even if a later reversal would have made it affordable.

### 2.6 Authorizations are state, not ledger records - and carry no date constraint

**Decision.** `Auth` is a mutable object in `auths` (status `APPROVED` → `SETTLED`, or
`REJECTED`), outside the journal. Only a settlement produces a ledger record. Holds are
subtracted in `availableBalanceAsOf` and never in `ledgerBalanceAsOf`.

`Auth.isActive()` is a pure status check - `status == APPROVED`, nothing else. There used
to be a `holdValueDate`-gated `isActiveOn(LocalDate)`, so a hold only counted from its own
value date and a settled hold kept counting until its settlement date. Both are gone: a
hold counts for every day of the report the moment it is approved, and stops counting the
moment it settles or is rejected - full stop, no date window at all.

**Why.** "Settlement need to only check if auth request is in approved state, no need of
date constraint" - approval status is the only thing that should decide whether a hold is
live. A hold's own value date and a settled hold's lingering window were both extra
machinery the rule never asked for.

**Trade-off.** Because `rebuildProjections` always recomputes every day from the week
start on every reconciliation (§2.1), and `activeHolds` now takes no date at all, every
historical row in *any* day's report reflects whichever holds are *currently* approved as
of the report being built - not what was actually true on that historical day. Concretely:
day 2's row can show a 300 AED hold within a day-3 report before that hold was even
raised (it wasn't live until day 2 closed), and the same row can drop back to 0 within a
later report once that hold settles - the displayed history of a day's holds silently
changes depending on which day's report you are reading. This was confirmed as the
intended reading (not a bug) rather than the narrower alternative of keeping holds bounded
by their approval day: closing balances and interest are never affected either way, since
holds only ever subtract from *available* balance, never from the ledger or the accrual
they are computed on.

**Settlement rules.** An approved, unsettled auth settles for any amount up to its hold,
regardless of the current ledger balance - the approval was the guarantee (see REJECTED.md
R5; the old check is left commented out in `settle()`). Unknown, rejected, already-settled
and over-hold settlements are refused and move no funds (R6).

### 2.7 Errors are data, not exceptions

**Decision.** Every refusal appends an `EngineError` (day, event label, account,
`ErrorCode`, message) and returns without writing to the journal. Nothing is thrown for a
business rejection.

**Why.** The required output is a per-day report *including errors*. Recording them keeps
a refused instruction visible and auditable while guaranteeing no funds move.

**Trade-off.** Callers must check `errors()`; a bug that should be fatal can be reported
as an ordinary rejection. Tests assert on error codes to compensate.

### 2.8 Money: `BigDecimal`, precision carried by the currency

**Decision.** All amounts are `BigDecimal`. `Currency` carries its precision (AED 2,
BHD 3) and default overdraft fee. Every stored amount passes through `Money.store`
(HALF_UP by default) inside `append`, so rounding happens once, at write time, at the
account's precision. Accruals pass through `Money.accrue` with their own rounding mode
(DOWN). Stream files keep every decimal as a string so the scale survives JSON.

**Why.** Notes.md: "precision and currency should be stored and correlatively calculated
during store". A single write path means no amount can bypass rounding.

**Trade-off.** Store rounding and accrual rounding intentionally differ, which is a
subtlety readers must know; `Money` and `LedgerConfig` document it.

### 2.9 Every ambiguity is a config flag

**Decision.** Each reading Notes.md leaves open is a named field on `LedgerConfig`
(`accrualRounding`, `installmentRounding`, `capitalizationExcludesOwnDay`,
`overdraftAssessmentExcludesOwnDayFee`, `overdraftFeeOnDaysWithoutMovement`,
`discardAccrualRemainder`, fees, window and capitalization day), with the chosen reading
as the default. `AmbiguityChoicesTest` runs the baseline both ways.

**Why.** The spec contradicts itself in places (day-5 fee vs the -205 figure). Encoding
the choice as a flag makes it reviewable and reversible without a redesign, and the tests
show exactly what the other reading would produce.

**Trade-off.** More branches in the engine and a combinatorial config space - not every
combination is tested, only each flag against the default.

### 2.10 Event streams as editable data

**Decision.** The five iterations are JSON files in `event-streams/` read at run time,
each self-contained (accounts, config, events, optional expectations).
`AllEventStreamsTest` picks up any new file automatically. The files are also generated by
`EventStreamFactory`, and `filesMatchTheGenerator` checks they have not drifted.

**Why.** Lets scenarios be added or tweaked without recompiling, and keeps each iteration
independently runnable.

**Trade-off.** Two representations of the same streams (Java factory and JSON) that must
be kept in step; hand edits to a JSON file fail the drift check unless the factory is
updated too.

### 2.11 Zero production dependencies

**Decision.** Java 21 standard library only; JSON is handled by the ~330-line
`io/Json` codec. JUnit 5 is the only (test-scoped) dependency. Built with Maven.

**Why.** The problem is small and self-contained; a reviewer can run it with a JDK and
Maven and nothing else, and there is no library behaviour to reason about around decimal
parsing.

**Trade-off.** A custom parser is code to maintain and is less forgiving than Jackson
(it supports only what the stream files use, plus `//` and `/* */` comments).

## 3. Deliberately out of scope

- Persistence, concurrency, multi-threading - the engine is single-threaded and
  in-memory by requirement.
- Multi-week windows and period closing - one window, one capitalization day.
- Currency conversion - each account is single-currency; nothing crosses accounts.
- Performance - linear scans everywhere (`ledgerBalanceAsOf`, `activeHolds`,
  `isAlreadyReversed`). Fine for the window; the first thing to change at scale.

## 4. Resolved: overdraft fees are permanent, holds carry no date constraint

This section tracked an open decision while the working tree was mid-change; both calls
have since been made explicitly and are now committed (§2.2, §2.6). Kept here as the
record of how the decision was reached, since both were genuine forks with a defensible
case either way.

Two readings were on the table:

- **Reversible fees, date-scoped holds** (the original design, matching the Notes.md
  walkthrough's "create overdraft reversal for day2 and day4"). Internally consistent -
  the restated ledger always matches what its own current history says it should - at
  the cost of a second record type and a non-monotone assessment rule.
- **Permanent fees, date-blind holds** (the design now committed). A fee is a fact about
  the ledger *as it stood when it was charged* and is never revisited; a hold counts
  purely on its approval status, with no value-date window of its own. Simpler
  (unconditionally monotone assessment, one fewer record type, one fewer date
  comparison), and matches Notes.md's later, more explicit criteria - but it means the
  ledger can permanently charge for an overdraft the restated history no longer shows
  (REJECTED.md R2), and a day's displayed holds can retroactively change depending on
  which later day's report is asking (§2.6).

Landing the second reading touched four places: `TransactionType` (drop
`OVERDRAFT_FEE_REVERSAL`), `LedgerEngine.rebuildProjections` (drop the branch that
created it), `Auth` (`isActiveOn(date)` → date-blind `isActive()`), and its two call
sites (`LedgerEngine.activeHolds`, `StreamRunner.buildAccountView`) - plus every test and
stream expectation that had baked in the old numbers (`AcceptanceCriteriaTest`,
`AmbiguityChoicesTest`, iterations 1, 2, 3 and 5, and the JSON files regenerated from
`EventStreamFactory`).
