#!/usr/bin/env bash
set -Eeuo pipefail
./gradlew :app:connectedDebugAndroidTest --no-daemon --stacktrace
