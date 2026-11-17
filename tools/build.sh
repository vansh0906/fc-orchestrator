#!/usr/bin/env bash
# Compile everything with the JDK alone (no build tool or dependencies needed) and run the unit tests.
set -euo pipefail
cd "$(dirname "$0")/.."
rm -rf out
javac -Xlint:all -Werror -d out $(find src -name '*.java')
java -cp out fc.Tests
