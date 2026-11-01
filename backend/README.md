# backend/

- `Code.gs` - your ORIGINAL Apps Script, left exactly as it was. **Do not deploy it.** It overwrites Payments column D (Month) with the paid date's month and Members column E (Monthly Fee) with the plan price, and it can write formulas into Members F:I. It is outbound-only (no import).
- `copy-test/ReadOnlyReport.gs` - read-only inspection script. Paste it into a COPY of your sheet only. It changes nothing and prints a report of duplicates, orphan payments and mismatches.

