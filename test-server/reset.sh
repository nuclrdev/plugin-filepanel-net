#!/usr/bin/env bash
# Throw the test server away and start a fresh one: every file back to how the
# image built it. The host key stays the same, so known_hosts needs no change.
set -euo pipefail
cd "$(dirname "$0")"
docker rm -f nuclr-test-server >/dev/null 2>&1 || true
exec ./up.sh
