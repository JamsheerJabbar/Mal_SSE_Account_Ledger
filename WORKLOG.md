22nd Sept
10pm - 12am problem reading and notes on requirements and criteria

23rd Sept
3am - 5am designing entities, data structures, functions, criteria acceptance and rejections
5am started dev with initial draft and test suite
6am pushed initial code with AI generated messy engine and test suite, taking a break and will re evaluate the code with current test cases.
12:30pm, picked up from code review and bug fixing
4:30pm, switched build from Gradle to Maven (pom.xml). Found the code didn't compile -
OVERDRAFT_FEE_REVERSAL had been dropped from TransactionType and the LedgerEngine call site,
and Auth.isActiveOn(date) had been reverted to a date-blind isActive() that showed holds as
active on every day, not just from their value date. Restored both, 75/75 tests green.
5:30pm, wrote ARCHITECTURE.md - the structural decisions and what each one costs: append-only
journal with daily accounts rebuilt as a derived projection, reverse-and-recredit for restated
interest, the fixed-point reconciliation loop (and why it terminates), intra-day vs end-of-day
reconciliation, auths kept as state outside the journal, errors recorded as data, BigDecimal with
precision carried by the currency, every ambiguity as a LedgerConfig flag, JSON streams as the
source of truth, zero production dependencies.
Working tree currently does not compile again - TransactionType has dropped
OVERDRAFT_FEE_REVERSAL and Auth is back to a date-blind isActive(), but LedgerEngine and
StreamRunner still reference the old names. This is the "overdraft fees are permanent" direction
from the updated Notes.md; left as-is and written up as the open decision in ARCHITECTURE.md
section 4, with the remaining engine/test changes listed.
Confirmed both calls explicitly: fees permanent, holds fully date-blind (no value-date
window, no lingering-until-settlement window - confirmed even the "shows current auth
state on a historical day's row" consequence is intended, not a bug). Landed the engine
side (LedgerEngine, Auth, TransactionType, StreamRunner), found and fixed a real bug along
the way (closingExInterest was checking live end-of-run state instead of the day's own
point-in-time figure - AccountDayView.LedgerRow now carries it directly), then rewrote
every affected expectation by hand: EventStreamFactory (iterations 1, 2, 3, 5; 4 untouched,
no auths/no fee ever reverses there), regenerated the JSON, and rewrote
AcceptanceCriteriaTest (After-E9 flips from Right to Wrong), AmbiguityChoicesTest (two of
the six ambiguities changed premise entirely - assessment-order no longer self-corrects
anything, and the quiet-day reading no longer converges to the same end state), and the
iteration 1/2/5 test classes. Biggest surprise: iteration 5's authC flips from APPROVED to
REJECTED - the permanent day-1 fee eats 25 AED out of the day-5 running balance, so a hold
that used to clear at exactly zero headroom no longer does. 80/80 green. Updated
ARCHITECTURE.md section 4 to "resolved" (folded into 2.2 and 2.6), REJECTED.md R2,
AMBIGUITIES.md (the before/after-own-fee entry is now moot; added the holds one), and
README's tables and numbers.

6.15pm Architecture, trade offs production readiness doc preparationAdded the recalculation cycle limit: a day's native close is cycle 1, each later
back-dated write that lands on it is another, and a 4th (maxRecalculationCycles, default
3) is refused before anything is appended - matching the day2/day5/day7/day8 cascade
example directly. New RecalculationCycle record (transaction id, account id, value date,
cycle number), gated in the four write paths (simple/installmentCredit/settle/reverse),
scoped to user-initiated writes only - the engine's own fee/interest corrections don't
count, so an oscillating overdraft can't itself burn the budget. Wired into LedgerConfig
and the JSON store like every other tunable. 82/82 green, no existing stream comes close
to the default limit. Documented in ARCHITECTURE.md as 2.12 (appended at the end rather
than renumbering, to avoid breaking the existing section cross-references).
