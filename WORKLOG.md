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