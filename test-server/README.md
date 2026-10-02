# Nuclr test server

A disposable Ubuntu 26.04 machine in Docker, reachable over SSH on
`127.0.0.1:2222`. Use it to try the Net panel and AI agent actions against a real
Linux system instead of a real server. Break anything; `./reset.sh` brings it back.

```bash
./up.sh      # build (first time) and start; prints how to connect
./reset.sh   # throw it away and start fresh: every file as built, same host key
./down.sh    # stop and remove
```

Needs Docker and `ssh-keygen` (Git Bash on Windows has both).

## Connecting

| Field | Value |
|---|---|
| Host | `127.0.0.1` |
| Port | `2222` (`NUCLR_TEST_SERVER_PORT` changes it) |
| Username | `tester` |
| Key file | `test-server/.keys/tester_ed25519` (no passphrase) |
| Password | `tester` (also the `sudo` password) |

In Commander: Net panel → F7 on the server list → name it `test-local`, so it can
never be mistaken for a real server. Confirm the host key once; resets keep it.

`.keys/` holds the server's host keys and the tester's login key. It is created by
`up.sh`, git-ignored, and only ever used for this server.

## What's on it

Built by `fixtures.sh`; nothing here is a real secret.

| Path | Why it's there |
|---|---|
| `~/app/.env` | mode `0600`, with secret-looking values (masking, permission keeping) |
| `~/app/deploy.sh` | mode `0755` (executable bits must survive edits) |
| `~/app/config.yml` | YAML with a `password:` key |
| `~/app/my folder with spaces/` | quoting |
| `~/logs/app.log` | 5,000 lines ending in an `ERROR` (truncation, tail) |
| `~/data/blob.bin` | 64 KB of binary (base64 reads) |
| `~/current`, `~/logs/latest.log` | symlinks |
| `/srv/www`, `/etc/nginx/...` | owned by root: writes fail without `sudo` |
| `/var/lib/fake-mysql` | `0700` root: not even readable |

`sudo` asks for the password, so `sudo -n` fails the way it does on most servers.

## Differences from a real server

- No systemd: `systemctl` does not work and no services run (only `sshd`). For
  that, use WSL2 Ubuntu or a cloud VM.
- Reachable from this machine only (published on loopback).

## Integration tests

The plugin's actions run against it in `TestServerIntegrationTest`, which is
skipped unless the server is named:

```bash
test-server/up.sh
NUCLR_TEST_SERVER=127.0.0.1:2222 mvn test -Dtest=TestServerIntegrationTest
```
