#!/usr/bin/env sh
set -e
cd "$(dirname "$0")/.."
rm -rf out && mkdir out
javac --release 17 -encoding UTF-8 -d out src/com/spos/editor/*.java
echo "Build OK. Classes are in the out folder."
