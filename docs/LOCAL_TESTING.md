# Local development checks

Run commands from the repository root with the project-supported JDK and Android SDK installed.

## Unit tests

Run Android JVM unit tests and the Desktop Bridge test suite:

```bash
gradle testDebugUnitTest :desktop-bridge:test
```

This is the same Gradle test command used by the repository's Android/Desktop CI workflow.

## Android emulator tests

Start an Android emulator compatible with the configured project SDK, then run:

```bash
gradle connectedDebugAndroidTest
```

The emulator suite exercises Android instrumentation paths. A successful local unit-test run does not replace emulator or physical-device Bridge acceptance.

## Before opening or updating a pull request

- Run the unit-test command above.
- Run emulator tests when the change touches Android persistence, lifecycle, pairing or Bridge behavior.
- Inspect the test report and device/logcat output when a test fails.
- State clearly which checks were run; do not describe unrun checks as passing.
- Do not merge a draft or security-sensitive change without the required owner review.
