#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ -n "${FL_JAVA_HOME:-}" ]]; then export JAVA_HOME="$FL_JAVA_HOME"; fi
if [[ -n "${FL_ANDROID_HOME:-}" ]]; then export ANDROID_HOME="$FL_ANDROID_HOME"; fi
if [[ -n "${FL_GRADLE_HOME:-}" ]]; then export GRADLE_USER_HOME="$FL_GRADLE_HOME"; fi
if [[ -n "${FL_GRADLE_EXECUTABLE:-}" ]]; then
  exec "$FL_GRADLE_EXECUTABLE" --no-daemon "$@"
fi
exec ./gradlew --no-daemon "$@"
