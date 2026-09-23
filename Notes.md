Problem Statement:
Write a script or test suite that runs event stream for per day across 6 days and print for each day - closing ledger balance, fee assessments, auth states and errors.

In memory account ledger, no persistence, no db, no UI

Two accounts - both starting with 0 closing ledgers, ACC001 - UAE Dirhams, ACC002 - Bahrain Dinar

Non negotiable rules - 
Overdraft fee - calculated every day based on closing balance, if accounts of all value_date <= calculation date sums up negative. - 25AED, this will be added to accounts with value_date = calculated date
For days with positive balance, 0.04% interest on closing balance, added per day towards a separate variable, total accrual only calculated and credited at end of 6 days
AED is two decimal places, BHD is 3 decimal places.
Stored value of currency should be rounded
Daily accrual value should also be rounded before adding to the accrual tally. - has few ambiguities, for less than .0049 aed we round to 0, and that compounded over few days can actually nullify a possible interest, But need to make a choice in this constraint with rigorous testing.
Precision(rounding figure) and currency(AED/BHD) should be stored and correlatively calculated during store.
Ledger records are append only, no updation, no deletion, if any update or removal, are stored as new transactions - Update this transaction, reverse that transaction and readjust the accounts.
The ledger recalculation and accrual history recomputation is also to be considered with few ambiguities, can lead to cyclic transactions, everything can change if on day 7 a huge reversal occurs, it can reduce interest accruals on one day and ultimately recalculating will give an additional amount, which will need another debit - ambiguity - solve is reverse old interest amount and add new interest,
Other problem is if there happened to be a negative closing balance on some day of the previous weeks one of the 6 days, overdraft fee was to be added, which again reduces closing balance and again a recalculation. Two three loops of calculation over a reversal or updation of transaction on 7th or 8th day for 2nd or 3rd day accounts.
Hold transactions - money to be held for debit, pending on approval, value_date stored, when its approved, its added towards value_date
Authorization for Hold transactions - only approved if ledger available balance(current balance) - active holds >= 0 - ideally balance available enough to release held amount





Event Streams: - Sequential events first for Acc001 and then for Acc002

Day 1 - credit - ACC001 - 1200aed
Day 1 - debit - acc001 - 950 aed
Day 2 - auth - acc001 - authA - hold 200aed
Day 3 - credit - acc001 - 400aed
Day 4 - settlement - acc001- authA settles for 185aed - value_date: day4
Day 4 - settlement - acc001 - authZ settles for 180aed - value date: day4, no previous auths for Z
Day 5 - debit - acc001 - 620aed - value date - day 2
Day 5 - auth - acc001 - authB - hold 90aed - value date: day5
Day 6 - reversal - acc001 - reverse 7, value date - day 2
Day 5 - credit - acc002 - 10bhd, posted as 3 equal installments, value date: day5
AuthB is never settled in this window

ACC001
Day1 = closing balance = 250, active holds = 0, available balance = 250, interest accrual = 0.1
Day 2 = closing balance = 250, active holds = 200(approved), available balance = 50, interest accrual = 0.1
Day 3 = closing balance = 650, active holds = 200, available balance = 450, interest accrual = 0.26
Day 4 = closing balance = 465, active holds = 0, available balance = 465, interest accrual = 0.18
Day 5 = closing balance for day 2 = -370 -25 overdraft fee applied, day 3 - closing balance = 5, day 4 closing balance = -180 -25 overdraft fee applied, closing balance = -205, available balance = -205, authB disapproved
Interest accrual variation - day1 = 0.1, day2 = 0, day3 = 0, day4 = 0, day5 = 0
Day 6 = closing balance = 465, create overdraft reversal for day2 and day4
Interest accrual variation - day1 = 0.1, day2 = 0.1, day3 = 0.26, day4 = 0.18, day 5 = 0.18, = total interest = 0.82

ACC002
Day5 = closing balance = 10, transactions = 0.333, 0.333, 0.333 | 0.001 - tagged as buffer pay or balancing pay(some account related good tag to show remainder)
Interest accrued = 0.004, total interest = 0.004


Acceptance and Rejections:
Some criterias are wrong, record which and why?
Day 2 closing ledger balance calculated at end of day5 before any fee assessed = -370aed - exactly one overdraft fee to be assessed , i.e on Day 2 - false, carries on to day4 and day 5
Day 4 settlement of AuthA should be accepted - true because current ledger balance >= 185 and settlement amount <= auth amount
Any settlement with AuthId not present in ledger should be rejected and funds must not leave accounts - should be accepted due to security concern. (need to think of edge cases)
If authB is approved - holds reduce available balance but not ledger balance - actually true because hold approval date will be the value date, which is on or after day 6, but here approval is denied due to less insufficient available balance during auth call.
After E9, all balances and fees return to pre E7 values = true because no other approved transaction happens at E8, also all the overdraft fee should be reversed since accounts get recalculated and theres no point for overdraft fee.
The three BHD installments should be 3.334 = false, it should be 0.333, always <= total, and remainder should be added as an overhead on ledger accounts
If rounded daily accruals do not sum to capitalized total, remainder is discarded - should not be discarded, should be rounded off while calculating capitalized total.


Entities:
Account Details - id, currency, closing ledger amount, closing date, week_start_date
Transaction - amount, account id, type, value_date
Daily accounts - value_date, transactions, opening_balance, closing_balance, interest_accrual, overdraft_enabled(bool), available_balance, active_holds_total
Auth - id, hold amount, hold_value_date
Currency denomination hash = currency: denomination

In memory data:
Transactions grouped by account id
Transactions grouped by date hash
Account details hash grouped by id
Auth hash grouped by id
Daily accounts hash grouped by date



Functions:
append to transactions
Amount rounding wrt currency
Daily accounts calc(on recon = false) = credit - debit + if transactions present with value_date < daily account calc,  call reconciliation(with array of transactions, assessment date), if sum -ve for a day, overdraft enabled and create overdraft transaction
Reconciliation - for value_date in transactions, fetch daily account of that day, readjust account(with on recon true, this will avoid cyclic complexity), create overdraft if required and enable, if already enabled ignore, if enabled and overdraft not required, create an overdraft reversal, and move on to next day until the assessment date
Create overdraft transaction
Create Overdraft reversal
Interest accrual calculation - store to daily accounts
Interest credit transaction - end of day 6, check in daily accounts order by date from week_start to 6 days and sum, create a credit transaction for day 6.
Authorization request creation - if hold amount requested <= available balance, approve : reject
Settlement validation - if auth id present in db, and settlement amount <= closing balance of the day.
