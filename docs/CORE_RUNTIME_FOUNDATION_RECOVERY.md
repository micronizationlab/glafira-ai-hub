# Core Runtime foundation compile recovery

## Scope and provenance

Main at `3d2aa8dbb9ed9ad42b76ef856d957133a4fd0b12` contains
`GlafiraCoreRuntimeOwner` but not its task store, executor, grant-store or Bridge
contract dependencies. The Android Kotlin compiler consequently fails before tests
can run.

This change restores the concrete transitive dependency closure needed by that
existing owner. The restored implementations are copied from the existing feature
snapshot `17ffa3703d983b8ec9b1fe1253449deaa867f849`
(`fix/android-bridge-settings-ui`); this is not a merge of that entire branch.
`GlafiraCoreContracts.kt` already matches that snapshot and is not changed.

The closure contains:

- Persistent encrypted SQLite task storage, task/state contracts, recovery seams,
  owner approval storage, authorizer and composite grant source.
- The typed executor, WorkManager drain/scheduling, maintenance gate, runtime budget,
  Dry Run planner and durable preview storage.
- Bridge protocol, credential/admission ports, legacy encrypted credential adapter,
  replay/authorization types and the local project/conversation dispatcher required
  by the owner's existing handler registrations.
- Planner/orchestrator and node-capability types used by the task store. No Desktop
  module, shell/build tool, network listener or new provider integration is added.

The existing owner public API is preserved. Its legacy credential adapter is retained
because main has no new pairing issuer/listener; importing the new pairing credential
store alone would create incompatible credential readers and writers. Pairing issuer,
credential store and transport must be reviewed and integrated together later.

## Execution boundary

Restoring compilation must not implicitly enable autonomous actions. The production
owner now creates its executor through `GlafiraRuntimeFoundation`, which fixes the
mode to `DRY_RUN` and supplies the task store's atomic preview persistence port.
Even an authorized queued action therefore produces a preview instead of invoking
its registered handler. Scheduling remains disabled by default. Desktop claims are
also blocked at this same rollout boundary, preventing a second execution route
from bypassing Dry Run; a paused owner cannot claim work either.

This change is a compile recovery foundation, not a completed autonomous runtime,
Desktop rollout or user-visible feature activation. There is no LIVE switch.

## Verification

Run with Android SDK 35, Build Tools 34.0.0, JDK 17 or newer and Gradle 8.9:

```shell
gradle :app:testDebugUnitTest :app:assembleDebug
```

Validated on the recovery branch: all 66 JVM tests pass and `assembleDebug`
produces `app/build/outputs/apk/debug/app-debug.apk`.

The focused tests cover the imported executor, state policy, owner authorization,
Dry Run redaction and runtime budgets. The foundation regression exercises the same
factory used by `GlafiraCoreRuntimeOwner` for each authorization status. It checks
that a preview is persisted, the task waits for review, a subsequent dequeue is idle,
and the handler is never invoked. A separate test checks that Desktop claims are
not permitted by the recovered foundation policy.

Android Keystore, SQLite process-death recovery, real WorkManager scheduling,
pairing, transport and physical-device behavior require Android instrumentation or
end-to-end validation. JVM test success must not be presented as proof of these paths.

## Integration order

1. Review and merge this main-based compile recovery on its own; rerun the Android
   unit suite and debug assembly against the resulting main commit.
2. Review independent main-based storage/CI fixes against that exact commit. Keep
   unrelated security and workflow changes in their own pull requests.
3. Review queue, delivery and other fixes on their actual feature-branch base; do not
   cherry-pick patches whose required APIs are absent on main.
4. Before integrating the broader Runtime Foundation/Bridge feature, compare its
   versions of all restored files with this recovery commit. Reconcile owner API,
   schema, preview mode, credential format and queue changes explicitly, with a
   dependency-ordered integration branch and combined tests.
5. Only a separately approved and tested rollout may replace the Dry Run boundary
   with live adapters and Desktop leasing.

## Remaining work

This dependency restoration deliberately retains the source snapshot's queue,
retry, backup, distributed ownership and result-delivery behavior. In particular,
main's existing Bridge handler summary does not deliver the actual result, and the
restored foundation does not establish exactly-once external effects. These require
separate reviewed changes, fault-injection tests and transport acknowledgement
contracts before live execution is enabled.
