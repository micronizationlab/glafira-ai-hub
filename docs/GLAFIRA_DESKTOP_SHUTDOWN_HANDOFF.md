# GLAFIRA Desktop Core — shutdown safety handoff

Last updated: 2026-11-19

## Why this branch exists

The owner authorized GLAFIRA to work independently in isolated branches while Kostya is inactive. This branch is stacked on `codex/p0-desktop-queue-persistence` and must be reviewed with PR #62 before integration.

## Finding

In `DesktopTaskRuntime.close()`, the previous implementation:
1. requested graceful executor shutdown;
2. waited five seconds;
3. called `shutdownNow()`;
4. waited another five seconds but ignored the returned termination flag;
5. returned normally even if a task handler was still alive.

`DesktopBridgeTaskWorker.close()` clears its HMAC secret after `runtime.close()` returns. A caller may also release the Desktop process/instance lock after close. Returning normally while a handler still executes can therefore make a live worker appear safely shut down and permit another process to start against shared state or credentials.

## Change in this branch

- Preserve the graceful drain, then cooperative interrupt sequence.
- Check the executor termination result after both bounded waits.
- If a handler ignores interruption and remains active, throw an explicit `IllegalStateException`; do not report clean shutdown.
- A repeated close attempt remains an error while workers are alive, and succeeds once the executor is actually terminated.
- `DesktopBridgeTaskWorker.close()` naturally does not reach HMAC-secret zeroization when runtime close fails. Callers must keep the instance lock and credentials until the worker has really stopped.
- Add a deterministic regression test with a handler that deliberately ignores interruption until a latch is released. It proves close fails closed, the task remains visible as active, and the handler can finish and persist its result after the failed close attempt.

## Validation required

This branch has not yet passed CI. Required checks:
- Desktop JVM test suite, including the new non-cooperative shutdown test.
- Existing Android JVM, emulator, CI harness and APK build checks inherited through the PR stack.
- Review the CLI owner/instance-lock lifecycle to confirm callers do not release the lock when `close()` throws.
- Add a process-level test once the actual CLI lock owner is identified.

## Integration boundaries

- Do not merge directly to `main`; this PR is based on PR #62's Desktop durable queue branch.
- Do not claim exactly-once execution. A handler that ignores interruption may still complete an external effect; the durable task outcome must be reconciled after process restart.
- This change does not solve the separate issue that interrupted tasks are currently classified as `FAILED/WORKER_INTERRUPTED` instead of an explicit outcome-unknown state. That requires a separate protocol and UI migration.
- It does not implement independent Desktop Core ownership arbitration or offline authority. Do not bypass Android's owner-authorized preflight to make a remote worker appear autonomous.
