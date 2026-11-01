# Data protection and migration plan

Your Google Sheet is the master record. Nothing in this project writes to it.

## Rules
1. App sync is OFF: `SheetsSyncConfig.ENABLED = false`. On start the app also cancels any sync work queued by an older build.
2. The old Apps Script (`backend/Code.gs`) must not be deployed.
3. The Sheet URL and token are no longer in the source. They would be passed as build settings (`sheetsEndpoint`, `sheetsToken`) only after approval.
4. The token that was in the old source is now in your GitHub history. If the repo is or was public, treat it as exposed and replace it.

## Steps (each needs your OK before the next)
1. Make a copy: Sheets > File > Make a copy. Also File > Download > Excel (.xlsx) and keep it.
2. Run `backend/copy-test/ReadOnlyReport.gs` (`previewReport`) on the COPY. Send me the output.
3. I produce a reconciliation report: duplicate member IDs, payments without a member, possible double payments, Month vs Paid On differences, Total Paid mismatches. You decide what is real.
4. I write the import (read-only from your exported data into the phone database): one monthly invoice per member per month from the join month, each existing payment applied to the month written in its Month column. You review the result against the Sheet.
5. Only after you approve: a separate, new write path to the Sheet, tested on the copy first.

## Database
`hisab.db` goes from version 1 to 2 with `MIGRATION_1_2`: adds `members.monthlyFeePaise` (default 0) and the new tables `invoices` and `allocations`. No existing table is dropped, rewritten or cleared. `MigrationSqlTest` runs this SQL on a v1-shaped database and checks the rows survive.
