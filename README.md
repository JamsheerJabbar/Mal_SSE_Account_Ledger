# MAL Account Ledger

In-memory, append-only account ledger for the problem in [`Notes.md`](Notes.md): run an
event stream day by day across a 6-day window and print, for each day, the closing ledger
balance, the fee assessments, the auth states and the errors.

No persistence, no database, no UI. Java 21, Maven, zero third-party production
dependencies (the JSON codec is in `com.mal.ledger.io.Json`).

Structural decisions and their trade-offs are in [`ARCHITECTURE.md`](ARCHITECTURE.md); the
ambiguous rules and the choice made for each are in [`AMBIGUITIES.md`](AMBIGUITIES.md);
which of Notes.md's own acceptance criteria turned out wrong, and why, is in
[`REJECTED.md`](REJECTED.md); every bare numeric constant and why that value and not half
it is in [`NUMBERS.md`](NUMBERS.md).

## Quick start

```bash
mvn compile exec:java@generate-streams                          # (re)write event-streams/*.json
mvn compile exec:java@run-streams                                # run all five, print the day reports
mvn compile exec:java@run-streams -Dstream=iteration-1-baseline  # run just one
mvn test                                                          # 85 tests, 3 known failures (see below)
mvn test -Dtest='!KnownLimitationsTest'                           # the other 82 - a clean BUILD SUCCESS
mvn test -Dtest=Iteration2RetroCascadeTest                        # one iteration on its own

mvn package -DskipTests                                          # build target/mal-account-ledger.jar
java -jar target/mal-account-ledger.jar run event-streams        # run the jar directly
```

Test output is hidden by default; add `-Dledger.redirectOutput=false` to see it, or override
the stream directory with `-Dledger.streams.dir=...`.

## Known failing tests

`mvn test` ends in `BUILD FAILURE` on purpose: `KnownLimitationsTest` has three tests that
fail, deliberately, to keep three real gaps in the design visible rather than quietly
accepted. 82 of the 85 tests are green; run `mvn test -Dtest='!KnownLimitationsTest'` for
a clean build when you want confirmation that nothing *else* broke.

**1. A settlement is not re-checked against a balance an intervening fee has since made
insufficient.** `REJECTED.md` R5 deliberately disabled that check - an approved hold is a
guarantee, and re-checking the current balance at settlement time would punish the
customer for something that happened after their approval was already confirmed. But R5's
own "what would change my mind" asked for exactly this case: an overdraft fee lands on the
ledger *between* approval and settlement, and the hold settles anyway - not against the
balance it had when approved, but against one a fee has since made unable to cover it,
immediately earning a second fee of its own. Three of the test's four checks describe that
actual, chosen behaviour and pass; the fourth checks for the textbook-safe refusal and
fails, because that refusal is the one thing R5 turned off.

**2. A 4th, entirely legitimate correction is refused for depth alone.**
`maxRecalculationCycles` (§2.12) exists to bound scale - it counts touches to a day, not
whether each touch was a good idea. A charge, a refund posted for the wrong amount,
reversing that mistake, then posting the *correct* refund is four ordinary, individually
correct steps - and the fourth one, the fix that actually gets the amount right, is
refused for being the 4th touch, with nothing wrong with the transaction itself.

**3. Capitalized interest permanently understates what the account actually earned, as a
direct, silent consequence of gap 2.** With the correct refund refused, day 2 stays 150
AED short of where it should be, and every day's interest computed from it is short too -
1.80 AED capitalized instead of the 2.22 AED the same account earns once the legitimate
correction is allowed through (verified by re-running the identical scenario with the
limit raised to 4). No error, no flag - the shortfall doesn't appear anywhere in the report.

See `ARCHITECTURE.md` §5 and `KnownLimitationsTest`'s own doc comments for the full
reasoning behind each.

## The five iterations

Each file in `event-streams/` is a complete, independently executable iteration: its own
accounts, its own config, its own instructions and its own expectations. Nothing is shared.

| File | What it pins down |
|---|---|
| `iteration-1-baseline.json` | The stream written out in Notes.md, verbatim, with the day-by-day figures it states. |
| `iteration-2-retro-cascade.json` | An 8-day window: a 2000 AED debit back-dated into day 3 arrives on **day 7**, after interest was already credited. Four fees raised, interest reversed and re-credited; day 8 reverses the debit itself, but not the four fees it caused. |
| `iteration-3-auth-holds.json` | Auth and hold lifecycle in isolation — approvals at positive and at exactly zero headroom, and every settlement rejection path. |
| `iteration-4-precision.json` | Two currencies at two precisions, installment remainders, and balances small enough that the daily accrual rounds to zero every day. |
| `iteration-5-mixed-stress.json` | AED and BHD side by side, a hold live across a restatement, and a permanent fee that costs a later hold its approval. |

## Editing a stream

The JSON files are the source of truth — the test suite reads them at run time, so an edit
changes what runs with no recompilation. `//` and `/* */` comments are allowed.

```jsonc
{
  "id": "my-stream",                       // must match the file name
  "config": { "windowDays": 6, "capitalizationDay": 6, "weekStartDate": "2026-01-01" },
  "accounts": [ { "id": "X1", "currency": "AED" } ],
  "events": [
    { "label": "A", "day": 1, "valueDay": 1, "account": "X1", "type": "CREDIT", "amount": "1000" },
    { "label": "B", "day": 5, "valueDay": 2, "account": "X1", "type": "DEBIT",  "amount": "1500" },
    { "label": "C", "day": 6, "account": "X1", "type": "REVERSAL", "target": "B", "valueDay": 2 }
  ],
  "expectations": { "netOverdraftFees": { "X1": "0.00" } }   // optional; only what is present is asserted
}
```

Event types: `CREDIT`, `DEBIT`, `INSTALLMENT_CREDIT` (`"installments": 3`), `AUTH`
(`"authId"`), `SETTLEMENT` (`"authId"`), `REVERSAL` (`"target"`). `valueDay` defaults to
`day`; set it lower to back-date.

Drop a new `.json` file into `event-streams/` and `AllEventStreamsTest` picks it up as a new
test case automatically — no Java change. The five shipped files are also generated from
`EventStreamFactory`, so if you hand-edit one, either update the factory to match or delete
the `filesMatchTheGenerator` check.

## Design

Two structures do the work:

- **The journal** is append-only. Nothing is updated or deleted; a correction is a new
  `REVERSAL` / `*_REVERSAL` record referencing the record it undoes — with one deliberate
  exception: an overdraft fee is never corrected at all. Once charged it is permanent
  history, even if the transaction that caused it is later reversed (see
  [`REJECTED.md`](REJECTED.md) R2).
- **Daily accounts are a derived projection**, rebuilt from the journal on every
  reconciliation. They can never drift, and `AllEventStreamsTest` asserts they reconcile
  to the journal sum for every stream.

Reconciliation runs as a **fixed-point loop**: rebuild every day, assess the overdraft fee,
reconcile the interest credit, repeat until a pass appends nothing. That is the "two three
loops of calculation over a reversal on the 7th or 8th day" the notes warn about, made
explicit — the pass count is printed per day and asserted in iteration 2.

The loop terminates because **overdraft assessment is strictly monotone**: a fee, once
charged, is never removed, so a pass can only ever add fee records against a finite number
of days — it cannot oscillate. It repeats beyond a single pass only because interest
capitalization is itself ledger movement on the capitalization day, which can tip that one
day negative and trigger its own fee.

Holds follow the same "no correction, no history" spirit: `Auth.isActive()` is a pure
approval-status check with no date of any kind attached. A hold counts from the moment it
is approved until it settles or is rejected — nothing else. Because daily accounts are
fully rebuilt on every reconciliation, this means a day's *displayed* holds reflect
whichever holds are currently approved as of the report being read, not what was actually
true on that day historically (`ARCHITECTURE.md` §2.6) — closing balances and interest are
unaffected either way, since holds only ever subtract from available balance.

**A day can only be disturbed so many times.** `maxRecomputePasses` (default 16) bounds
one reconciliation call; a separate, independent counter, `maxRecalculationCycles`
(default **3**), bounds a day's whole life. A day's own native close is cycle 1; every
later back-dated write that lands on it — a fresh credit or debit, a settlement, a
reversal — is one more. A write that would push a day past the limit is refused
*before* anything is appended (`RECALCULATION_LIMIT_EXCEEDED`), and every admitted cycle
is logged with the record it produced, the account, the value date and the cycle number
(`LedgerEngine.recalculationCycles()`). This is user-initiated writes only — the
system's own fee and interest corrections are never gated, so a day that keeps tipping
in and out of overdraft across several closes cannot itself exhaust the budget. See
`ARCHITECTURE.md` §2.12 and `RecalculationCycleLimitTest`.

## Resolved ambiguities

Every choice Notes.md flags is a config flag in `LedgerConfig`, and `AmbiguityChoicesTest`
runs the baseline **both ways** so the cost of each reading is visible.

| Flag | Chosen | Why | Other reading gives |
|---|---|---|---|
| `accrualRounding` | `DOWN` | Notes.md requires day 4 (465.00 × 0.0004 = 0.186) to accrue **0.18**. Also never credits interest the balance has not earned. | `HALF_UP` → 0.78 total, not 0.76 |
| `discardAccrualRemainder` | `false` | The capitalized total is exactly the sum of the stored daily accruals, so the credit always reconciles to its own audit trail. | `true` → 0.77, which the trail no longer explains |
| `capitalizationExcludesOwnDay` | `true` | The credit lands on day 6, so including day 6's own accrual is circular. Confirmed by the notes: ACC001 totals 0.76 (days 1–5, net of the permanent day-2/day-4 fees), ACC002 totals 0.004 (day 5 only). | `false` → 0.92 and 0.008, contradicting both stated figures |
| `overdraftAssessmentExcludesOwnDayFee` | `true` | "Day 2 … before any fee assessed = −370". Now that fees are permanent (see below), this only changes what a day's `assessmentBalance` *displays* once it already carries a fee — it no longer changes any outcome, since nothing reverses a fee either way. | `false` → the same closing balances; only the displayed assessment basis for an already-fee-bearing day differs |
| `overdraftFeeOnDaysWithoutMovement` | `false` | **Notes.md contradicts itself here** — see below. | `true` → day 5 closes at −230 and three fees are raised, permanently 25 AED apart from the `false` reading (fees no longer converge back to the same end state either way) |
| `installmentRounding` | `DOWN` | Every slice must be ≤ total/n; the remainder is posted as a `BALANCING_ADJUSTMENT` tagged `BUFFER_PAY`. | `HALF_UP` → 1.000 BHD / 7 would post 0.143 × 7 = 1.001, over the total |
| overdraft fees reversible? | **No, permanent** | Once charged, a fee stands even if the transaction that caused it is later reversed — transactions are immutable, and a fee is a fact about the ledger as it stood, not a projection that tracks later corrections. See `REJECTED.md` R2. | Reversible fees keep the restated ledger internally consistent with its own current history, at the cost of a second record type and a non-monotone assessment rule |
| holds carry a date constraint? | **No** | A hold counts purely on being approved — no value-date window, no lingering-until-settlement window for a settled hold. | Bounding a hold by its own value date (or a settled hold's settlement date) keeps a day's displayed history faithful to what was actually true that day, at the cost of two more date comparisons |

### The one contradiction in Notes.md

The acceptance text says the overdraft "carries on to day4 **and day 5**" — three fees. The
worked figures say day 5 closes at **−205** and day 6 raises exactly two reversals, "for day2
and day4" — two fees. Both cannot hold: a day-5 fee would make day 5 close at −230.

We follow the figures (a day with no ledger movement of its own is not separately assessed;
it carries the prior balance forward) and ship the other reading behind
`overdraftFeeOnDaysWithoutMovement: true`, tested in `AmbiguityChoicesTest`. With fees now
permanent, the two readings no longer converge to the same end state either: the extra
day-5 fee under the `true` reading is a real, lasting 25 AED the `false` reading never
charges.

## Notes.md acceptance criteria — the verdicts

`AcceptanceCriteriaTest` has one test per criterion.

| Criterion | Verdict | Why |
|---|---|---|
| Day 2 is −370, so **exactly one** fee is assessed | **Wrong** | The −370 is right. The conclusion is not: the fee takes day 2 to −395, day 3's +400 leaves only 5.00, and day 4 closes at −180 and is charged again. Two fees. |
| The day-4 settlement of authA should be accepted | **Right** | The ledger stood at 650 (≥ 185) when it arrived and 185 is within the 200 hold. The back-dated debit that later drags day 4 negative had not been instructed yet and cannot retroactively refuse it. |
| A settlement quoting an unknown auth id must be refused | **Right** | Accepting it would let a settlement instruction alone move money with no prior authorization. Refused, recorded, no funds move. Sibling cases — rejected auth, already-settled auth, settlement over the hold — are in iteration 3. |
| Holds reduce available balance but not ledger balance | **Right as a rule, moot in the baseline** | The rule holds (iteration 3 proves it on an approved hold), but authB never becomes a hold: available balance was −205 when authorization was requested. |
| After E9 all balances and fees return to pre-E7 values | **Wrong** | Transactions are immutable: E9 reverses E7 itself, but does not get to erase the fees E7 caused along the way. Day 6 closes at 415.00, not the pre-E7 465.00 — the two fees stand permanently (see `REJECTED.md` R2). E8's refusal is *not* undone either: it was correct on the information available that day. |
| The three BHD installments should be 3.334 | **Wrong** | 3.334 × 3 = 10.002, more than was instructed. Slices round down to 3.333; the 0.001 remainder is posted as a `BUFFER_PAY` balancing entry. (The notes' own "0.333" is a typo for 3.333 — 10/3 is 3.333, not 0.333.) |
| If rounded daily accruals do not sum to the capitalized total, discard the remainder | **Wrong** | The capitalized total is the rounded sum of the stored daily accruals. Nothing is discarded, so the credit always reconciles to the accrual history it came from. |

## Layout

```
pom.xml                            Maven build
Notes.md                           the problem statement (source of truth for the rules)
ARCHITECTURE.md                    structural decisions and their trade-offs
AMBIGUITIES.md                     every ambiguous rule, in prose, and the choice made
REJECTED.md                        Notes.md's own acceptance criteria, verified one by one
event-streams/                     the five iterations, editable JSON
src/main/java/com/mal/ledger/
  domain/       Account, Transaction, Auth, DailyAccount, Currency, Money
  engine/       LedgerEngine (the whole ledger), LedgerConfig, ErrorCode
  events/       LedgerEvent, EventStream, EventStreamFactory (generates the streams)
  io/           Json (own codec), EventStreamStore (read/write the JSON)
  report/       DayReport, ReportPrinter
  runner/       StreamRunner (walks a stream day by day), Main (CLI)
src/test/java/com/mal/ledger/
  Iteration1..5*Test             one class per iteration, independently runnable
  AllEventStreamsTest            parameterized over every file in event-streams/
  AcceptanceCriteriaTest         the Notes.md verdicts above
  AmbiguityChoicesTest           each choice run both ways
  WrittenAmbiguitiesTest         tests for the two ambiguities written in AMBIGUITIES.md
  RecalculationCycleLimitTest    the day-2/5/7/8 cascade, and the 4th cycle it refuses
  EventStreamEditingTest         round-trip and edit-takes-effect
  KnownLimitationsTest           3 tests, 3 known deliberate failures - see "Known failing tests"
```
