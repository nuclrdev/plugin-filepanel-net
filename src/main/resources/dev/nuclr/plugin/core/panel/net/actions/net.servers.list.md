# net.servers.list — List saved servers

Lists the SSH servers the user has saved in Commander's Net panel. Call it first:
every other `net.*` action names its server by the `id` or `name` returned here.

- Only saved servers can be used. To work with a new server, ask the user to add
  it in the Net panel (F7 on the Net server list).
- `connected` says whether a session is open right now. A server that is not
  connected is connected on first use; if it needs a password, a key passphrase or
  a new host key confirmed, **the user** is asked in Commander — never you.
- Passwords, passphrases and key files are never returned.
