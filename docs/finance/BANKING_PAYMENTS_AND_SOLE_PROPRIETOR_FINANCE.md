# GLAFIRA Banking, Payments and Sole-Proprietor Finance
**Owner-approved capability concept — 2026-11-21**

See [Issue #374](https://github.com/micronizationlab/glafira-ai-hub/issues/374) for the full threat model and acceptance criteria. This document captures the product intent and mandatory safety boundaries.

## Primary interaction model: maximum permitted autonomy through the owner's existing smartphone bank app

The owner's existing, installed banking application on the smartphone is the primary bank interface. GLAFIRA should complete as much of each banking workflow as is technically and legally permitted, minimizing owner involvement. The owner should not have to manually perform routine navigation, searches, data preparation, reconciliation, or downloads when a supported and permitted mechanism can do them.

### Autonomy ladder

1. **Autonomous preparation and bookkeeping:** GLAFIRA can classify the request, select the configured bank, open the existing bank app, navigate through permitted mechanisms, search transactions, prepare payment/invoice details, reconcile records, organize statements and prepare tax workpapers.
2. **Permitted deterministic app actions:** GLAFIRA may perform routine navigation and data entry only through bank-supported deep links/intents, documented integration surfaces, official APIs, or another method explicitly permitted by the bank and applicable platform/store policies. It must verify screen/context and field values before proceeding.
3. **Owner-only authentication:** if the bank requires login, PIN, biometric, OTP, device approval or step-up authentication, the owner completes that step directly in the bank's trusted UI. GLAFIRA does not read, intercept, store, relay or ask the owner to disclose secrets.
4. **Owner-only final money authorization:** GLAFIRA may prepare the payment and navigate to the final review screen when permitted. The owner must independently review the recipient, destination account, amount, currency, date and purpose, then personally perform the bank's final payment confirmation. GLAFIRA must not trigger the final authorization or transfer.
5. **Autonomous post-action verification:** after owner confirmation, GLAFIRA checks the resulting status where permitted, records transaction evidence, updates the ledger and reconciles the result. If status is uncertain, it checks bank status before any retry and never blindly repeats a payment.

The default goal is **maximum safe autonomy, not maximum manual interaction**. Owner participation is limited to steps that the bank/platform requires or that authorize a consequential financial action. If the bank prohibits automation or exposes no permitted integration surface, GLAFIRA must use a manual handoff for that step and explain why; it must not bypass the restriction.

## Android / Google Play platform constraint

GLAFIRA is a general-purpose personal assistant, not an accessibility tool whose core purpose is assisting people with disabilities. Therefore, it must not rely on Android AccessibilityService as a general autonomous cross-app agent. Current Google Play policy prohibits Accessibility API use to power apps that autonomously initiate, plan and execute actions/decisions, except for qualifying accessibility tools; non-accessibility uses also have prominent disclosure, affirmative consent and declaration requirements. Prefer narrower supported APIs, documented bank integrations, deep links/intents and explicit owner handoff. Re-check policy before implementation and release.

References:
- [Google Play — Use of the AccessibilityService API](https://support.google.com/googleplay/android-developer/answer/10964491)
- [Google Play — Permissions and APIs that access sensitive information](https://support.google.com/googleplay/android-developer/answer/16909972)
- [Android Developers — AccessibilityService actions](https://developer.android.com/guide/topics/ui/accessibility/views/service)

## Intended owner workflow

1. Owner says, for example, "Find the incoming payment from this customer" or "Prepare a payment to this supplier".
2. GLAFIRA opens the configured bank app and carries out all permitted navigation/search/data preparation itself.
3. Owner intervenes only if the bank requests authentication, a security challenge, or direct confirmation.
4. GLAFIRA retrieves statements and transaction details through permitted capabilities, stores source provenance, categorizes and reconciles the ledger.
5. GLAFIRA prepares invoices using verified ИП requisites, creates a preview and records delivery/status evidence where permitted.
6. For a payment, GLAFIRA fills/prepares the details and presents a final review. Owner verifies and personally confirms in the bank app. GLAFIRA then verifies and records the result.
7. At year end, GLAFIRA prepares a reconciled tax workpaper/export with source statements, categorized totals, missing-document warnings and accountant-review status. It does not silently file or certify a declaration.

## Hard boundaries

- Existing bank app on the owner's smartphone is the primary interface.
- Maximize autonomous routine work only through bank/platform-permitted mechanisms; do not use AccessibilityService as a general autonomous agent.
- Credentials, PIN, CVV and one-time codes remain inside the bank's trusted interface. GLAFIRA never requests, captures, stores or forwards them.
- The owner performs every final payment confirmation. No unattended transfers or automated final authorization.
- No MFA/CAPTCHA/anti-bot/device-binding bypass, hidden payments or blind retries.
- Default scope is read-only. Payment preparation is distinct from payment authorization.
- Every prepared payment is bound to immutable details and current owner generation. Any changed amount/beneficiary/account/currency/date requires a fresh owner review.
- Ambiguous timeouts are reconciled against bank status before any retry.
- Financial records are encrypted, locally controlled, auditable and excluded from generic model context by default.
- Tax calculations are jurisdiction/year-specific, source-grounded and marked draft pending owner/accountant review.

## Architecture building blocks

Bank Capability Registry; Smartphone Bank-App Handoff/Assist Adapter; owner-present Session Broker; supported deep-link/intent/API adapters; read-only Transaction/Statement Service; verified ИП Profile and Invoice Service; Payment Draft + Owner Review Gate; Reconciliation/Tax Workpaper Service; immutable Audit/Trace; Android/Desktop/Web coordination adapters.

Every bank adapter needs a capability matrix: supported tasks, permitted automation surfaces, owner-intervention points, supported export routes, policy/terms evidence, failure behavior and tested bank-app versions. Unsupported or unverified actions must fail closed and fall back to manual handoff.

No bank adapter or money-moving runtime is enabled merely by writing this specification. Core ownership fencing, durable audit and bank-specific terms/testing are prerequisites.
