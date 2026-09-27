#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/privacy_scan.py --history
python3 -m unittest discover -s server -p 'test_*.py'
python3 -m compileall -q server
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDemo
if command -v gitleaks >/dev/null 2>&1; then
  gitleaks dir . --redact
  gitleaks git . --redact --log-opts=--all
else
  printf 'Gitleaks is not installed; run it before any public release.\n' >&2
  exit 2
fi
