#!/usr/bin/env bash
#
# Start the Nuclr test server: a disposable Ubuntu machine on 127.0.0.1:2222.
#
#   ./up.sh       build (if needed) and start it; prints how to connect
#   ./reset.sh    throw it away and start a fresh one (same host key)
#   ./down.sh     stop and remove it
#
# Keys are generated once into .keys/ (git-ignored): the server's host keys, so
# its identity survives resets, and the tester's login key.
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash: keep container paths as written

cd "$(dirname "$0")"
NAME=nuclr-test-server
IMAGE=nuclr-test-server
PORT=${NUCLR_TEST_SERVER_PORT:-2222}

host_path() {   # a path Docker on Windows understands, unchanged elsewhere
	if command -v cygpath >/dev/null; then cygpath -m "$1"; else echo "$1"; fi
}

mkdir -p .keys
[[ -f .keys/ssh_host_ed25519_key ]] || ssh-keygen -q -t ed25519 -N "" -C nuclr-test-server -f .keys/ssh_host_ed25519_key
[[ -f .keys/ssh_host_rsa_key ]] || ssh-keygen -q -t rsa -b 3072 -N "" -C nuclr-test-server -f .keys/ssh_host_rsa_key
[[ -f .keys/tester_ed25519 ]] || ssh-keygen -q -t ed25519 -N "" -C tester@nuclr-test-server -f .keys/tester_ed25519
chmod 600 .keys/tester_ed25519 2>/dev/null || true

if [[ -n "$(docker ps -q -f "name=^${NAME}$")" ]]; then
	echo "The test server is already running."
else
	docker rm -f "$NAME" >/dev/null 2>&1 || true
	echo ">> Building the image ..."
	docker build -q -t "$IMAGE" . >/dev/null
	echo ">> Starting the test server ..."
	# Published on loopback only: nothing outside this machine can reach it.
	docker run -d --name "$NAME" -p "127.0.0.1:${PORT}:22" \
		-v "$(host_path "$PWD/.keys"):/keys:ro" "$IMAGE" >/dev/null
fi

echo -n ">> Waiting for SSH "
for _ in $(seq 1 60); do
	if ssh -i .keys/tester_ed25519 -p "$PORT" -o BatchMode=yes -o ConnectTimeout=2 \
			-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR \
			tester@127.0.0.1 true 2>/dev/null; then
		echo "- ready."
		break
	fi
	echo -n "."
	sleep 1
done || true
ssh -i .keys/tester_ed25519 -p "$PORT" -o BatchMode=yes -o StrictHostKeyChecking=no \
	-o UserKnownHostsFile=/dev/null -o LogLevel=ERROR tester@127.0.0.1 true \
	|| { echo; echo "ERROR: SSH did not come up. Logs: docker logs $NAME" >&2; exit 1; }

cat <<INFO

Nuclr test server - add it in the Net panel (F7 on the server list):
  Name      test-local
  Host      127.0.0.1
  Port      $PORT
  Username  tester
  Key file  $(host_path "$PWD/.keys/tester_ed25519")   (no passphrase)
  or password: tester

Host key fingerprint (the Net panel asks you to confirm it once):
  $(ssh-keygen -l -f .keys/ssh_host_ed25519_key.pub)

Plain ssh:  ssh -i .keys/tester_ed25519 -p $PORT tester@127.0.0.1
Fresh copy: ./reset.sh      Stop: ./down.sh
INFO
