# REJECTED.md

Source: the "Acceptance and Rejections" list in `Notes.md`. Each item below is quoted
from there; verdicts and reasoning are mine, checked against the current code, not
assumed from the note's own aside.

## Refused criteria

### R1 — "Day 2 closing ledger balance calculated at end of day5 before any fee assessed = -370aed - exactly one overdraft fee to be assessed, i.e. on Day 2"
**Verdict:** Incorrect.
**Why:** -370 is right as the assessment basis for day 2, but stopping the story there is not. The fee that -370 produces drags day 3 down too (day 3's own +400 only clears it to 5.00), and day 4 closes at -180 on its own merits and is assessed again - two fees, not one. Three fees (adding day 5) is also not supported: the walkthrough's own Day 6 line says the reversal is "for day2 and day4" only, and day 5's stated closing balance of -205 only holds if day 5 carries the balance forward without a fee of its own - a third fee would take day 5 to -230.
**What I implemented instead:** exactly two overdraft fees, assessed on day 2 and day 4. A day is only assessed if it has ledger movement of its own that day, or `overdraftFeeOnDaysWithoutMovement` is turned on in `LedgerConfig` for the three-fee reading.
**What would change my mind:** if day 5's stated closing balance were corrected to -230, or the "day2 and day4" reversal line were corrected to name day 5 too, I'd flip the default to assess every day, not just days with their own movement.

### R2 — "After E9, all balances and fees return to pre E7 values"
**Verdict:** Incorrect.
**Why:** transactions are append-only and immutable. E9 reverses E7 itself; it doesn't get to also erase every downstream consequence E7 caused along the way, including overdraft fees that were legitimately assessed against the ledger as it actually stood before E9 arrived. Reversing E7 restates the balances E7 touched - it doesn't get to rewrite history to say the overdraft never happened. This also settles a contradiction inside Notes.md itself: the worked walkthrough's Day 6 line ("create overdraft reversal for day2 and day4") described the earlier, reversible-fee design; this later criterion is the one that stuck.
**What I implemented instead:** `TransactionType.OVERDRAFT_FEE_REVERSAL` no longer exists, and `LedgerEngine.rebuildProjections` never removes a fee once charged - a day that later recomputes back to solvent keeps its fee regardless. Concretely, in the baseline stream, day 6 settles at 415.00 (not the pre-E7 465.00): the two fees E7 caused on day 2 and day 4 stand permanently, 50 AED total, even after E9 undoes the debit itself. `AcceptanceCriteriaTest.reversalDoesNotRestorePreE7State` asserts exactly this.
**What would change my mind:** nothing - this is now the committed design (`ARCHITECTURE.md` §2.2).

### R3 — "The three BHD installments should be 3.334"
**Verdict:** Incorrect.
**Why:** 3.334 x 3 = 10.002 - two fils more than the 10.000 BHD actually credited. Every slice must be `<= total/n` so a split can never pay out more than it took in. 10/3 rounds down to 3.333 (Notes.md's own figure reads "0.333", almost certainly a dropped leading digit - 0.333 x 3 + 0.001 sums to 1.000, not the stated 10.000 closing balance).
**What I implemented instead:** each slice stored at 3.333 (rounded DOWN), with the 0.001 remainder posted as its own record, tagged `BUFFER_PAY`, so the three slices plus the buffer reconstitute exactly 10.000.
**What would change my mind:** a currency or regulation that requires installments to round up instead - that's a one-line override (`installmentRounding` in `LedgerConfig`), not a redesign.

### R4 — "If rounded daily accruals do not sum to capitalized total, remainder is discarded"
**Verdict:** Incorrect.
**Why:** discarding the remainder makes the capitalized credit an un-auditable number - nothing in the printed daily accrual history would add up to what the account was actually credited. Rounding it into the total instead is the only version where the daily figures and the credit reconcile.
**What I implemented instead:** the capitalized total is the sum of the already-rounded daily accruals (`LedgerEngine.reconcileCapitalization`, `discardAccrualRemainder=false` by default). Nothing earned is ever dropped.
**What would change my mind:** nothing - I can't construct a case where discarding real accrued interest makes the accrual history reconcile to the credit it's supposed to explain.

### R5 — "Settlement validation - if auth id present in db, and settlement amount <= closing balance of the day" / "Rejected ledger balance constraint, since approved auth should always execute"
**Verdict:** Incorrect (the ledger-balance half of the rule).
**Why:** once a hold is approved, the funds it covers were already confirmed available at approval time. Re-checking the *current* ledger balance at settlement time punishes the customer for something that happened after their auth was already guaranteed, and makes the guarantee an approval gives meaningless.
**What I implemented instead:** the ledger-balance comparison in `LedgerEngine.settle()` is disabled - left in the source, commented out, with its `ErrorCode.INSUFFICIENT_LEDGER_BALANCE` now unused. A settlement against an approved, unsettled auth executes whenever the amount is within the hold, regardless of the account's current ledger balance.
**What would change my mind:** a demonstrated case where an intervening overdraft fee or reversal, between approval and settlement, pushes the account further negative than the fee structure assumes when the blind settlement is let through - that would argue for a bounded re-check rather than none at all. No stream in this repo currently exercises that sequence.

### R6 — "Any settlement with AuthId not present in ledger should be rejected and funds must not leave accounts"
**Verdict:** Incorrect, if "should be accepted due to security concern" means the settlement should be allowed to post.
**Why:** letting a settlement move money against an auth id the ledger never issued is exactly the vulnerability the original rule exists to close - a settlement instruction alone would become sufficient authority to release funds with no prior authorization at all. A security concern argues for making the attempt visible and auditable, not for letting it succeed.
**What I implemented instead:** the settlement is refused with a dedicated `UNKNOWN_AUTH` error, recorded in the engine's error list and printed in that day's report - so the attempt is tracked, not silently dropped - while zero ledger record is created and zero funds move.
**What would change my mind:** if "accepted" means "recorded, not silently dropped" rather than "posted as a real settlement," there's no actual disagreement - the current behavior already satisfies that reading, and this note is about phrasing, not behavior.

## Accepted criteria (verified, not assumed)

### A1 — "Day 4 settlement of AuthA should be accepted"
**Verdict:** Correct.
**Basis:** authA was approved on day 2 for 200 AED and was never rejected or settled before E5 arrives. E5 settles 185, which is `<= 200`. `LedgerEngine.settle()` checks exactly two things before releasing funds - the auth is approved-and-unsettled, and the amount is within the hold - and E5 satisfies both. The auth's own approval is what authorizes the release, not the ledger balance at settlement time (see R5).

### A2 — "holds reduce available balance but not ledger balance"
**Verdict:** Correct.
**Basis:** `activeHoldsOn()` is only ever subtracted inside `availableBalanceAsOf()`; `ledgerBalanceAsOf()` sums real transactions and never looks at auths at all - the two are structurally independent. `Iteration3AuthHoldsTest.holdsDoNotTouchLedger` exercises this directly: an approved 500 AED hold leaves the ledger balance at 500.00 while available balance drops to 0.00.

## Approaches abandoned mid-build

### D1 — removing overdraft-fee reversal, hold value-date awareness, and the settlement ledger-balance check in one commit
**Why abandoned:** the commit ("no reversal for overdraft fee") deleted the `OVERDRAFT_FEE_REVERSAL` enum constant but left the one call site that constructed it with the old signature untouched, so the project stopped compiling. It also reverted `Auth.isActiveOn(LocalDate)` to a date-blind `isActive()`, which made every hold read as active from day 1 regardless of its own value date or settlement state - that broke the existing test suite (holds showing active before they were even raised).
**Cost:** discovered while migrating the build from Gradle to Maven - the broken compile blocked verifying the migration at all. Restored the enum constant, its call site, and the date-aware `Auth.isActiveOn` to get back to a compiling, 75/75-green build, and left only the settlement ledger-balance check disabled, since Notes.md's own updated criteria (R5) call for that one specifically. What I'd do differently: change one behavior at a time, or keep the engine compiling at each step (mark the code path with a comment instead of deleting the type it depends on), so a single compile break doesn't hide three separate design decisions behind one error.
