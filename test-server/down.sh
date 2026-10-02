#!/usr/bin/env bash
# Stop and remove the test server. Its keys in .keys/ are kept for next time.
set -euo pipefail
docker rm -f nuclr-test-server >/dev/null 2>&1 && echo "Test server removed." || echo "The test server was not running."
