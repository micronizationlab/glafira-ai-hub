# GLAFIRA Mobile Personal Data Bridge

**Status:** Proposed architecture, tracked by issue #273.  
**Platforms:** Android and iOS.  
**Principle:** GLAFIRA acts on the owner's phone data only through explicit, revocable, least-privilege OS capabilities. It never bypasses the OS sandbox or extracts another provider's password vault.

## 1. Product behavior

The mobile app exposes a local capability layer to GLAFIRA. Natural-language intent becomes a typed operation plan, checked against permission grants and owning-Core policy, executed by a native adapter, and returned as normalized data with provenance.

Supported intents:
- "Кадровик, импортируй мои контакты" — request contacts access, import permitted fields, deduplicate, and create a source-attributed contact index.
- "Найди последнее фото с визиткой" — search only the authorized photo scope; resolve "last" from system asset timestamps; report the selected asset and date.
- "Возьми данные с визитки и добавь к контакту Ваня Юрист" — OCR, normalize, search exact and fuzzy matches, produce a field-level patch, clarify ambiguous matches, and obtain confirmation before writing.
- "Открой сайт с моим сохранённым паролем" — invoke supported OS-mediated credential/autofill UI; never reveal the secret to the model.

## 2. Native platform adapters

### Android
- Contacts: Contacts Provider (READ_CONTACTS for reading, WRITE_CONTACTS for direct writes) or Contacts intents for user-mediated selection/editing where broad access is unnecessary.
- Photos: Android Photo Picker for explicit one-off selection; scoped MediaStore access only for a separately explained, user-authorized searchable library. Respect selected/limited media access and OS-version behavior.
- Credentials: Android Credential Manager and Autofill Framework for OS-mediated selection/autofill. Android Keystore protects GLAFIRA-owned keys and secrets; it cannot read Google Password Manager or another app's vault.
- Biometrics: BiometricPrompt/device credential gates sensitive local operations.

### iOS
- Contacts: Contacts framework; request only required authorization and honor limited contact access. Use ContactsUI pickers when user selection is sufficient.
- Photos: PhotosPicker for explicit selection; Photos framework authorization for broader library search, honoring limited-library selection.
- Credentials: AuthenticationServices / Password AutoFill and credential-provider flows where supported. Keychain stores GLAFIRA-owned secrets; it does not grant general access to iCloud Keychain entries owned by other apps.
- Biometrics: LocalAuthentication gates GLAFIRA's protected operations; biometric templates never leave the OS.

## 3. Permission Broker

A native local Permission Broker is the only component allowed to invoke sensitive platform APIs. It exposes a narrow typed interface, not raw platform handles.

Initial scopes:

| Scope | Meaning | Default |
|---|---|---|
| contacts.read | Read permitted contacts and requested fields | Deny |
| contacts.write | Apply an approved contact patch | Deny |
| photos.select | Read assets selected in the system picker | Deny |
| photos.search | Search the broader permitted photo library | Deny |
| credentials.use | Invoke OS-mediated credential/autofill flow | Deny |
| credentials.manage | Manage GLAFIRA-owned credentials only | Deny |

Every grant records device and owner identity, scope, purpose, grant time, expiry/renewal policy, OS authorization state, and revocation state. Both OS authorization and GLAFIRA authorization are required. Revocation blocks future operations; running work re-checks before sensitive writes.

Permission UX explains why a capability is needed at the moment it is used. Read and write access are separate. A one-off picker action never silently implies broad background access.

## 4. Contact import and safe updates

Normalize contacts into platform-independent records:
- stable local record ID and platform source ID;
- structured name, organization, title, phones, emails, postal addresses, URLs and notes (only authorized fields);
- labels such as work/mobile/home;
- source device, source record, import timestamp and source field;
- field-level provenance, confidence and last-verified timestamp.

Import is idempotent. Re-import updates the local index without duplicating people. Matching uses exact phone/email and normalized names plus organization context. Fuzzy similarity may rank candidates but cannot authorize a merge.

A proposed mutation is a ContactPatch containing target source ID, expected pre-change version/hash, per-field add/replace/remove operations, evidence references, confidence and idempotency key. Show a before/after preview. For an ambiguous target, ask the owner to choose. For a write, require approval bound to the exact patch hash. Apply atomically where supported, read back to verify, and save a reversible change record. Never delete or destructively merge by default.

## 5. Photo search and business-card extraction

Two distinct modes:
1. **Selected asset:** owner chooses an image through the system picker. Access is limited to that asset or selection.
2. **Authorized library search:** owner separately enables a searchable photo scope. The app may index asset metadata and OCR text locally, with pause/delete controls.

"Last photo" means the newest accessible asset by system-provided creation timestamp, not the last image viewed. If access is limited, say "latest among accessible photos."

Pipeline: asset selection → local metadata → OCR → business-card/document classification → field extraction → normalization → contact candidate search → patch preview → owner approval → native write → read-back verification.

OCR text and QR/barcode payloads are untrusted data. They cannot issue instructions, grant permissions, invoke tools, or change policy. Face identification and identity inference from images are out of scope. Keep originals unchanged; retain only minimum derived data and apply retention/deletion policy.

On-device OCR is preferred. If a remote model is required, disclose what content will be transmitted and to which configured processor, and require an owner grant before upload.

## 6. Credential boundary

There is no supported general-purpose API for GLAFIRA to enumerate/export all passwords from Google Password Manager, iCloud Keychain, or third-party managers. Never bypass this boundary.

Allowed:
- invoke system credential selection/autofill/passkey flows for a specific relying party or app;
- use a credential-provider extension only when the user explicitly installs/enables GLAFIRA and the platform permits the operation;
- store GLAFIRA's own integration tokens and secrets in Keystore/Keychain, protected by hardware-backed keys and local authentication where available;
- pass opaque credential handles to a native broker for narrowly scoped operations without returning plaintext secrets to the language model.

Forbidden:
- password-vault scraping, accessibility scraping, clipboard harvesting, hidden autofill capture, or extracting another app's private storage;
- sending passwords, refresh tokens, recovery codes or private keys to model prompts, analytics, crash reports, logs, backups, contact records, OCR text or Git;
- logging credential field values, including debug logs.

## 7. Cross-platform contract and task lifecycle

Define versioned Kotlin/Swift contracts with shared JSON golden fixtures. Initial operation types:
- contacts.import
- contacts.search
- contacts.patch.preview
- contacts.patch.apply
- photos.search.latest
- photos.ocr.extract
- credentials.invoke_autofill

Results contain normalized data, source references, timestamps, confidence, permission scope used, and operation ID. They never contain raw passwords or platform capability objects.

Each operation is a durable task owned by exactly one Core and fenced by owner generation. Process death must not lose intent or silently repeat a write. Use idempotency keys, durable result outbox and exact ACK. Before resuming after restart/reconnect, re-check OS permissions, grant expiry, source record version and pending approval. A revoked or stale grant fails closed.

## 8. Audit, privacy and retention

Audit events include request ID, operation type, scope, decision, approval binding, adapter result code, changed field names (not secret values), and verification status. Minimize personal data in logs. Encrypt local caches at rest. Sync is opt-in and project-scoped. Provide inspect/export/correct/delete controls for derived contact indexes and OCR artifacts. Backup excludes device-bound keys and raw credentials.

## 9. Acceptance tests

- Android and iOS permission denial, limited access, later grant, revocation and process death.
- Import all permitted contacts and prove stable idempotency/no duplicate growth.
- Matching: exact email, exact phone, duplicate names, transliteration, missing fields, ambiguous "Ваня Юрист".
- Patch preview hash binding; reject stale source version; confirm once; verify read-back; retry without duplicate values; rollback where supported.
- Photo picker vs broader library search; latest accessible photo; timezone/date ordering; empty library; limited/revoked access.
- OCR with noisy/rotated images, multilingual text, malicious embedded instructions and QR payloads.
- Verify no face identification and no remote upload without explicit disclosure/approval.
- Credential flow uses system UI; assert secrets absent from model payloads, logs, analytics, crash reports, exports and backups.
- Shared Kotlin/Swift fixtures and contract conformance.
- Physical Android and iPhone acceptance before release.

## 10. Delivery sequence

1. Audit Android/iOS modules and permission declarations; map current Core/Bridge contracts.
2. Add shared typed contracts and golden fixtures before adapters.
3. Implement Android read-only contacts and selected-photo paths.
4. Implement iOS equivalents in parallel.
5. Add OCR/business-card extraction and provenance-aware contact matching.
6. Add approved, reversible contact patch application.
7. Add broader photo search as a separate opt-in grant.
8. Add Credential Manager/Autofill and Password AutoFill flows without vault enumeration.
9. Add audit, revocation, retention, backup exclusions and security tests.
10. Run exact-head CI and physical-device acceptance; compilation alone is not completion.

## References
- Android Contacts Provider: https://developer.android.com/identity/providers/contacts-provider
- Android Credential Manager: https://developer.android.com/identity/credential-manager
- Android Photo Picker: https://developer.android.com/training/data-storage/shared/photopicker
- Apple Contacts: https://developer.apple.com/documentation/contacts
- Apple PhotosPicker: https://developer.apple.com/documentation/photosui/photospicker
- Apple AuthenticationServices: https://developer.apple.com/documentation/authenticationservices
- Apple Password AutoFill: https://developer.apple.com/documentation/security/password-autofill
- Apple LocalAuthentication: https://developer.apple.com/documentation/localauthentication
