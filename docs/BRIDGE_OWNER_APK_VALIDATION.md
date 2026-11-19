# Bridge owner APK validation branch

This branch exists only to trigger the dedicated `GLAFIRA Bridge Owner Test APK` workflow from the reviewed Bridge-fix branch.

- Base: `glafira/bridge-keystore-signer-fix`
- No application source changes are intended here.
- The APK must be built from this branch's exact source snapshot, which inherits the Android Keystore signer fix and regression test.
- Do not merge this validation-only PR. Close it after collecting the artifact and retain the run/artifact link in the owner handoff.
