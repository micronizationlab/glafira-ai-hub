# CI reliability and integration notes

## Scope and evidence

This change is based on PR #50 tip `17ffa37`. It changes CI helpers and the
existing Android/Desktop workflow only. It does not merge the feature stack or
declare that main compiles. Main's missing Core Runtime dependencies require a
separate correction and full build.

The preceding PR #50 run `44553464393` passed JVM tests, then exhausted the
20-minute software-emulator boot loop. Its cleanup could block on ADB or wait
forever for the emulator. The earlier missing-ADB PATH defect was already fixed
in `17ffa37` and is not the explanation for this later boot failure.

The workflow now uses GitHub's supported Ubuntu 24.04 runner with explicit KVM
availability/access checks, `emulator -accel-check`, and `-accel on`. SwiftShader
handles graphics; CPU virtualization remains accelerated. There is no software
CPU fallback. A missing hypervisor fails the job with an actionable reason.

Every finite ADB operation is bounded. Boot readiness and package-manager
readiness have separate deadlines; either failure prevents instrumentation.
Streaming logcat has an owned PID and teardown stops it. Diagnostics, guest
shutdown, process termination and reaping cannot wait indefinitely. Cleanup
preserves the original command failure. The job-level timeout is a final bound.

The APK job still requires **both** JVM and instrumented jobs to pass. Each
required suite must produce parseable JUnit XML with at least one executed test
and no failures/errors. Missing results and skipped-only runs fail verification.
HTML, XML and emulator logs are uploaded on failure as well as success. No
concurrency group cancels or supersedes another validation run.

Sources for runner/tool behavior:

- [GitHub-hosted Linux runner hardware acceleration](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)
- [Android Emulator acceleration](https://developer.android.com/studio/run/emulator-acceleration)
- [Android Emulator command-line options](https://developer.android.com/studio/run/emulator-commandline)

## Safe PR sequence

Snapshot: open PRs at the start of this audit. Recheck SHAs before integration.

| Order | PR | Head | Target branch |
| --- | --- | --- | --- |
| 1 | #30 | `5521baf` | `main` |
| 2 | #31 | `945f47d` | `core/autonomous-runtime-24x7` |
| 3 | #32 | `7727ff4` | `core/ar1-persistent-task-queue` |
| 4 | #35 | `2006ab9` | `core/ar2-unified-task-executor` |
| 5 | #37 | `eb2206f` | `backup/core-aware-implementation` |
| 6 | #47 | `5fba326` | `runtime/ar3-workmanager-integration` |
| 7 | #48 | `923da2a` | `integration/core-gateway-desktop` |
| 8 | #50 | `17ffa37` | `runtime/p3-autonomous-runtime-foundation` |

Do not treat this as one automatically mergeable chain. Git ancestry checks
found two unpropagated base updates:

- #31 includes #30's older `e11c99b`, not current `5521baf`. The missing commit
  changes **only CI** and says to keep PR validation to unit tests and not
  assemble APK until Bridge acceptance. Reconcile this workflow policy with
  the later #50 workflow before integration; do not silently restore or remove
  gates based on whichever workflow file wins a merge.
- #37 includes #35's older `73f4ead`, not current `2006ab9`. The missing commit
  changes **only CI**, adding emulator failure diagnostics. Preserve that
  diagnostic requirement when reconciling workflows.

The other adjacent tips in the table are ancestors of their downstream tip.
Sibling proposals #33/#34/#36 are not prerequisites merely because their
numbers are earlier. Older Bridge/security/iOS PRs form separate chains and
need their own compatibility/security review.

Open this CI fix against `fix/android-bridge-settings-ui` while that is its
tested base, or cherry-pick **only this focused commit** onto a coordinated
integration candidate. Do not open a whole PR #50 feature delta against main
as a CI-only fix. Resolve workflow policy with the owner, integrate approved
dependencies bottom-up, and run the complete required workflow on each final
merge candidate. A check from a different head or base is not proof that the
new candidate passes. Do not merge the parallel branches blindly.

## Verification and acceptance

Host regression checks:

```sh
bash -n .github/scripts/run-android-instrumentation.sh
python3 -m unittest discover -s .github/scripts/tests -v
```

The host suite uses a fake SDK to verify boot/shutdown deadlines, early
emulator exit, unavailable acceleration/package manager, retained Gradle exit
status, missing/skipped reports, and process cleanup. These tests validate the
CI harness; they do not substitute for Android or Desktop application tests.

Remote acceptance requires the workflow on the actual proposed commit:

1. Android and Desktop Gradle suites succeed with separate XML reports.
2. KVM and emulator acceleration diagnostics show usable acceleration.
3. Actual device instrumentation executes and passes, with XML and logs saved.
4. Only then does the APK job assemble and upload its debug-key-signed APK.

The local audit host has no `/dev/kvm`; it cannot establish emulator success.
Until a hosted workflow completes, emulator and APK acceptance remain pending.
