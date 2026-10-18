#!/usr/bin/env sh
set -e
cd "$(dirname "$0")/.."
sh scripts/build.sh
echo "===== Self tests =====";  java -cp out com.spos.editor.SelfTests
echo "===== Stress test =====";  java -cp out com.spos.editor.StressTest
