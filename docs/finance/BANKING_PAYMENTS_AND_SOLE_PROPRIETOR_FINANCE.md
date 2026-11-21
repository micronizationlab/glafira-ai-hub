# GLAFIRA Banking, Payments and Sole-Proprietor Finance
**Owner-requested capability concept — 2026-11-21**

See the linked architecture issue for the full threat model and acceptance criteria. This document captures the product intent and mandatory safety boundaries.

## Primary interaction model: the existing bank app on the owner's smartphone

The owner's existing, installed banking application on the smartphone is the primary bank interface. GLAFIRA should assist through that app on the owner's device, rather than requiring the owner to create a separate GLAFIRA bank login or route bank credentials through GLAFIRA.

1. Owner asks GLAFIRA to perform a banking task and identifies the bank/account if needed.
2. GLAFIRA brings the existing bank app to the foreground and assists with navigation and data entry only to the extent permitted by the bank, Android/iOS platform rules and the app's supported accessibility/deep-link capabilities.
3. The owner personally enters any credentials, PINs, OTPs and other authentication factors directly in the bank's trusted UI. GLAFIRA must not read, intercept, store, relay or ask the owner to disclose them.
4. GLAFIRA may locate transactions, prepare payment details, and assist with statement retrieval. Read-only tasks should not require payment authority.
5. Before any money movement, GLAFIRA presents the complete transaction details. The owner checks the details and personally performs the final bank-native confirmation in the bank app. This is the required confirmation point; GLAFIRA must not press/automate the final authorization control or execute a transfer without the owner's direct action.
6. GLAFIRA verifies the resulting bank status where the app exposes it, then records the outcome and source evidence. If status is uncertain, it must reconcile before suggesting any retry.

Official bank APIs may be added as optional supplementary integrations only after bank-specific verification; they do not replace the smartphone-app-first owner workflow unless the owner explicitly chooses that mode. If a bank prohibits UI automation or does not expose a permitted integration path, GLAFIRA must stop at guidance/manual handoff rather than bypassing the restriction.

## Intended owner workflow

1. Owner tells GLAFIRA to open the existing bank app on the smartphone.
2. Owner signs in and completes MFA directly in the bank's trusted UI.
3. GLAFIRA helps search transactions and retrieve statements using permitted app capabilities; owner handles any bank-native confirmation or export step that requires direct interaction.
4. GLAFIRA categorizes and reconciles transactions, preserving source documents and uncertainty.
5. GLAFIRA prepares invoices using verified ИП requisites and previews them for approval.
6. For payments, GLAFIRA prepares the details and guides the owner to the bank's confirmation screen. The owner independently verifies recipient, account, amount, currency and purpose, then personally confirms in the bank app. No unattended transfers and no automated final confirmation.
7. At year end, GLAFIRA prepares a reconciled tax workpaper/export with source statements, categorized totals, missing-document warnings and accountant-review status. It does not silently file or certify a declaration.

## Hard boundaries

- Existing bank app on the owner's smartphone is the primary interface; no separate GLAFIRA custody of bank credentials.
- Official APIs/delegated consent are optional supplementary paths only after bank-specific verification. UI assistance is allowed only where the bank permits it and the owner is present.
- Credentials, PIN, CVV and one-time codes remain inside the bank's own trusted interface. GLAFIRA never requests, captures, stores or forwards them.
- The owner, not GLAFIRA, performs every final payment confirmation in the bank app. No automation of the final authorization action.
- No MFA/CAPTCHA/anti-bot/device-binding bypass, hidden payments, unattended transfers or blind retries.
- Default scope is read-only. Payment preparation is distinct from payment authorization.
- Every prepared payment is bound to immutable details and current owner generation. Any changed amount/beneficiary/account/currency/date requires a fresh owner review.
- Ambiguous timeouts are reconciled against bank status before any retry.
- Financial records are encrypted, locally controlled, auditable and excluded from generic model context by default.
- Tax calculations are jurisdiction/year-specific, source-grounded and marked draft pending owner/accountant review.

## Architecture building blocks

Smartphone Bank-App Handoff/Assist Adapter; owner-present Session Broker; read-only Transaction/Statement Service; verified ИП Profile and Invoice Service; Payment Draft + Owner Review Gate; Reconciliation/Tax Workpaper Service; immutable Audit/Trace; Android/Desktop/Web coordination adapters.

The Android integration must use only permitted platform/app affordances. It must clearly distinguish navigation/data-entry assistance from owner-only sensitive entry and final confirmation. It must fail closed when app state, target fields or transaction details cannot be verified.

No bank adapter or money-moving runtime is enabled merely by writing this specification. Core ownership fencing, durable audit and bank-specific terms/testing are prerequisites.
