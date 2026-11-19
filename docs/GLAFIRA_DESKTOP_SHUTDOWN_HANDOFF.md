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
- The CLI previously nested `worker.use { }` inside `session.use { }`; Kotlin's outer `use` would release the OS queue lock even when worker close threw. Replace this with `closeDesktopWorkerAndSession(worker, session)`, which releases the session only after worker close succeeds. A regression test verifies the lock remains held after failed worker close.
- The CLI also had an outer `finally` that unconditionally zeroed the worker HMAC secret. Guard this cleanup: when worker construction fails, release the session and clear the secret; when worker shutdown succeeds, clear the secret; when shutdown fails, retain both the session lock and the secret while the process/handler remains alive.
- Add a deterministic regression test with a handler that deliberately ignores interruption until a latch is released. It proves close fails closed, the task remains visible as active, and the handler can finish and persist its result after the failed close attempt.

## Validation status

- Run #988 passed both JVM suites after the lock-retention test was moved into its correct test class: Android 140 executed, 0 skipped; Desktop 52 executed, 0 skipped. Its API 35 emulator job passed; APK assembly was still running at last check.
- Runs #990–#992 exposed a Kotlin syntax error in the subsequent CLI cleanup restructuring. The block was rewritten; run #993 then passed both JVM suites: Android 140 executed, 0 skipped; Desktop 52 executed, 0 skipped. Run #994 is validating the newest head after a small cleanup.
- Run #993's API 35 emulator job is still in progress. APK assembly for the newest head remains outstanding.

Required checks:
- Latest Desktop JVM test suite, including the new non-cooperative shutdown and queue-lock retention tests.
- Existing Android JVM, emulator, CI harness and APK build checks inherited through the PR stack.
- Review the CLI owner/instance-lock lifecycle to confirm callers do not release the lock when `close()` throws.
- The CLI lock-retention test now launches a separate JVM to prove the OS `tasks.lock` cannot be acquired after worker close fails, and becomes available only after explicit safe session release. A full end-to-end test that combines a real non-cooperative handler with the OS lock remains a useful final acceptance case.

## Integration boundaries

- Do not merge directly to `main`; this PR is based on PR #62's Desktop durable queue branch.
- Do not claim exactly-once execution. A handler that ignores interruption may still complete an external effect; the durable task outcome must be reconciled after process restart.
- This change does not solve the separate issue that interrupted tasks are currently classified as `FAILED/WORKER_INTERRUPTED` instead of an explicit outcome-unknown state. That requires a separate protocol and UI migration.
- It does not implement independent Desktop Core ownership arbitration or offline authority. Do not bypass Android's owner-authorized preflight to make a remote worker appear autonomous.
