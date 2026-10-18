#!/usr/bin/env sh
set -e
cd "$(dirname "$0")/.."
sh scripts/build.sh
exec java -cp out com.spos.editor.Server
