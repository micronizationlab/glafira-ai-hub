# Bridge request admission pipeline

`GlafiraBridgeRequestAdmission` composes the trusted local components in a fixed order:

1. Load the request device's credential from Android Keystore-backed encrypted storage.
2. Validate the signed Bridge envelope, verify device identity and HMAC, and enforce Core tool scopes/approval policy.
3. For an admissible request (`ACCEPTED` or `PENDING_APPROVAL`), atomically claim the idempotency key in the SQLite replay ledger.
4. Return the policy decision only if the replay claim succeeds.

A missing paired-device record, duplicate key, replay-store capacity exhaustion or storage error fails closed. Approval requests are claimed too, preventing the same signed request from repeatedly entering the owner-review path.

## Important boundary

The request's device ID is only a lookup hint. Trust comes from the locally stored credential, and the Core gate compares it with the signed request identity. Never construct or populate the credential store from unauthenticated request data.

This admission pipeline still does not execute tools, persist an owner approval queue, issue approval grants, or provide a network transport. The future host must call this pipeline before dispatching any operation and must persist execution state separately to recover safely from crashes after an idempotency claim.
