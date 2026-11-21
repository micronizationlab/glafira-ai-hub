# GLAFIRA Banking, Payments and Sole-Proprietor Finance
**Owner-requested capability concept — 2026-11-21**

See the linked architecture issue for the full threat model and acceptance criteria. This document captures the product intent and mandatory safety boundaries.

## Intended owner workflow

1. Owner tells GLAFIRA to open a supported bank.
2. GLAFIRA explains the bank connector and required scope; owner personally signs in and completes MFA in the bank's trusted UI.
3. GLAFIRA searches transactions and downloads statements within the granted read-only scope.
4. GLAFIRA categorizes and reconciles transactions, preserving source documents and uncertainty.
5. GLAFIRA prepares invoices using verified ИП requisites and previews them for approval.
6. For payments, GLAFIRA prepares a draft and displays all final details. The owner explicitly approves that exact transaction and completes bank-native step-up authentication. No unattended transfers.
7. At year end, GLAFIRA prepares a reconciled tax workpaper/export with source statements, categorized totals, missing-document warnings and accountant-review status. It does not silently file or certify a declaration.

## Hard boundaries

- Official bank APIs/delegated consent first; UI-assisted control only where the bank permits and the owner is present.
- Credentials, PIN, CVV and one-time codes remain inside the bank's own trusted interface. GLAFIRA never requests, captures, stores or forwards them.
- No MFA/CAPTCHA/anti-bot bypass, hidden payments, unattended transfers or blind retries.
- Default scope is read-only. Payment initiation and execution are separate, explicitly enabled scopes.
- Every payment approval is fresh, one-time and bound to immutable details and current owner generation. A changed amount/beneficiary/account/currency/date invalidates approval.
- Ambiguous timeouts are reconciled against bank status before any retry.
- Financial records are encrypted, locally controlled, auditable and excluded from generic model context by default.
- Tax calculations are jurisdiction/year-specific, source-grounded and marked draft pending owner/accountant review.

## Architecture building blocks

Bank Connector Registry; owner-present Session Broker; read-only Transaction/Statement Service; verified ИП Profile and Invoice Service; Payment Draft + Owner Approval Gate; Reconciliation/Tax Workpaper Service; immutable Audit/Trace; Android/Desktop/Web adapters.

No bank adapter or money-moving runtime is enabled merely by writing this specification. Core ownership fencing, durable audit and bank-specific terms/testing are prerequisites.
