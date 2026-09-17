#!/bin/bash
GRADLE_BIN="$(dirname "$0")/gradle-bin/bin/gradle"
exec "$GRADLE_BIN" "$@"
