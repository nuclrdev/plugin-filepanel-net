#!/bin/sh
# Installs the host keys and the tester's login key from /keys (mounted from the
# developer's .keys folder), then runs sshd in the foreground. Mounted files from
# Windows have loose permissions, which sshd refuses, so they are copied, not used
# in place.
set -eu

install -m 600 /keys/ssh_host_ed25519_key /etc/ssh/ssh_host_ed25519_key
install -m 644 /keys/ssh_host_ed25519_key.pub /etc/ssh/ssh_host_ed25519_key.pub
install -m 600 /keys/ssh_host_rsa_key /etc/ssh/ssh_host_rsa_key
install -m 644 /keys/ssh_host_rsa_key.pub /etc/ssh/ssh_host_rsa_key.pub

install -d -m 700 -o tester -g tester /home/tester/.ssh
install -m 600 -o tester -g tester /keys/tester_ed25519.pub /home/tester/.ssh/authorized_keys

mkdir -p /run/sshd
exec /usr/sbin/sshd -D -e
