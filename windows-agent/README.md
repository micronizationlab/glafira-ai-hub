# GLAFIRA Windows Agent — W1 foundation

This is the beginning of a separate Windows background-worker product. It is not a Desktop clone.

## Included in this increment

- .NET 8 Worker Service project that can run under the Windows Service Control Manager.
- Minimal service lifecycle and bounded graceful-shutdown timeout.
- Typed task envelope/result contracts with payload references and integrity hashes.
- Explicit handler registry that rejects unknown task types and duplicate registrations.
- In-memory service status surface.

## Deliberately not enabled yet

- No host endpoint, network listener or automatic connection.
- No Agent enrollment or credentials.
- No task polling, leasing or execution.
- No shell, arbitrary process launch, unrestricted filesystem or browser control.
- No secrets are stored or transferred.

The service reports `AwaitingOwnerEnrollment` and does no work until owner-approved enrollment, device identity, capability scopes, revocation and the canonical C1 lease protocol are implemented and tested.

## Next increments

1. Owner-confirmed enrollment against the Windows Desktop host.
2. Device-bound protected credential storage and revocation.
3. Authenticated heartbeat/reconnect with backoff.
4. Lease/fencing-aware task claim and typed handler execution.
5. Pause/resume, resource limits, install/upgrade/uninstall and ten-node fault testing.
