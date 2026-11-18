# GLAFIRA Bridge replay protection

`GlafiraBridgeReplayGuard` provides a durable SQLite ledger for one-time Bridge idempotency claims.

## Required request-processing order

1. Validate the Bridge envelope and time window.
2. Resolve the device from the trusted credential store.
3. Verify the HMAC signature.
4. Verify device scopes and Core authorization policy.
5. Claim the authenticated device + idempotency key in the replay ledger.
6. Only after `ACCEPTED`, begin the requested operation.

A duplicate claim must be rejected. Storage errors and capacity exhaustion must fail closed; they must never be treated as permission to execute.

## Semantics and limitations

The claim is committed in a SQLite transaction before the caller performs the side effect. This gives at-most-once admission, not exactly-once execution: if the process crashes after claiming but before completing the operation, a retry is rejected even though the operation may not have happened. A future operation journal should correlate the claim with durable execution state and allow safe recovery without replaying external side effects.

Expired claims are pruned transactionally on insertion. The ledger is bounded; when it reaches capacity, new claims are rejected until expired entries are removed. Device ID and idempotency key are stored only as a SHA-256 digest.

This helper is not yet wired into a network listener or operation executor. It must be invoked by the trusted host after authentication and before any side effect. Multi-device enrollment, server-side replay storage and cross-host coordination remain separate concerns.
