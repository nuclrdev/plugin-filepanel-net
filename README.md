# 🌐 Net Remote Panel (SSH / SFTP / SCP)

An official [Nuclr Commander](https://nuclr.dev) plugin that adds a **Net** file panel root for browsing and managing remote servers over SSH. Built on [Apache MINA SSHD](https://mina.apache.org/sshd-project/), it shares a single SSH session per server for both SFTP (filesystem operations) and SCP (transfers).

## ✨ What it does

| Feature | Details |
|---|---|
| 📡 Browsing | List remote directories via SFTP, with progressive streaming as entries arrive |
| 🔑 Authentication | Password, or a private key with optional passphrase — OpenSSH, PEM/PKCS#8 and PuTTY PPK (v2/v3) are all auto-detected |
| 🛡️ Host verification | `known_hosts`-backed, trust-on-first-use with an explicit fingerprint confirmation; a **changed** host key is flagged as a possible attack and requires an extra confirmation |
| 📋 Copy / Move | Both directions (local↔remote, remote↔remote, remote↔other virtual filesystems), with conflict prompts (Overwrite/Skip, with "…All" stickiness) and cancellable progress |
| ✏️ Rename | F6 on a single item within the same folder prompts for a new name and renames it over SFTP |
| 📁 Make Folder / Create File | F7 / Shift+F4 create a new remote directory or empty file |
| 🗑️ Delete | F8 recursively deletes files/folders over SFTP (always permanent — there is no remote trash) |
| 🔍 Find | Alt+F7 filename search: remote `find -iname`/`-name` streamed over an exec channel, falling back to a recursive SFTP walk (used automatically for regex searches, or when `find` is unavailable) |
| 📜 Tail -F | Live-follow any remote file (`tail -F`) in its own window, with automatic reconnect on a dropped session |
| 📝 Text / hex editing | F4 downloads to a local temp copy, opens the commander's usual editor picker (text or hex — whichever supports a plain file), watches the copy, detects remote changes made while you edited, and uploads via a hidden temp sibling + atomic SFTP rename |
| 👁️ View / Quick View | F3 and Ctrl+Q both work directly against the mounted SFTP path — no download needed, since the SFTP filesystem is a real `java.nio.file.FileSystem` |
| 🔌 Shared sessions | One SSH connection per server, shared by both panels and reused for reconnects; connections open lazily on first use |
| 💾 Server profiles | Saved to `~/.nuclr/net/servers.json` — passwords/passphrases are **never** written to disk, only cached in memory for the session |

## ⌨️ Keyboard shortcuts

Server-list view (`Net` root):

| Shortcut | Action |
|---|---|
| `Enter` | Connect (lazily) and open the server's remote root |
| `F4` | Edit the selected server profile |
| `F7` | New server profile |
| `F8` | Remove the selected server profile(s) |

Remote-folder view:

| Shortcut | Action |
|---|---|
| `F3` | View (streams the file directly over SFTP) |
| `F4` | Edit (downloads to a temp copy; see above) |
| `F5` | Copy |
| `F6` | Move / Rename |
| `F7` | Make Folder |
| `F8` | Delete |
| `Shift+F4` | Create empty file |
| `Alt+F7` | Find |
| *(context menu)* | Tail -F on a focused file |

## 🖥️ Server profile fields

| Field | Description |
|---|---|
| Name | Optional display label; falls back to `user@host` |
| Host / Port | Hostname or IP; port defaults to 22 |
| Username | SSH login user |
| Authentication | Password, or a private key file (+ optional passphrase) |
| Initial directory | Remote path to open on connect; blank uses the login home directory |

## 📁 Server configuration file

Profiles are stored at `~/.nuclr/net/servers.json`. Passwords and key passphrases are **not** part of this file at all — there is no blank placeholder field, the schema simply has nowhere to put a secret:

```json
[
  {
    "id": "a1b2c3d4-...",
    "name": "Production Box",
    "host": "example.com",
    "port": 22,
    "username": "alice",
    "authMethod": "KEY",
    "privateKeyPath": "/home/alice/.ssh/id_ed25519",
    "initialPath": "/var/www"
  }
]
```

Host keys are recorded in the standard OpenSSH `known_hosts` format at `~/.nuclr/net/known_hosts`.

## 📥 Installation

Copy the signed plugin archive and detached signature into the Nuclr Commander `plugins/` directory:

```text
filepanel-net-<version>.zip
filepanel-net-<version>.zip.sig
```

Nuclr Commander verifies the RSA-SHA256 signature against `nuclr-cert.pem` on load. The plugin becomes available immediately without a restart.

## ⚙️ How it works

- **`NetConnection`** (in `ssh/`) wraps one Apache MINA SSHD `ClientSession` per server: it authenticates (password or key, via `KeyLoader`), mounts the SFTP filesystem, and lazily creates SCP clients and exec channels on the same session. `ensureOpen()` transparently rebuilds a dropped session, so every panel operation reconnects on demand rather than failing outright.
- **`ConnectionRegistry`** is a process-wide, connection-per-server-id map, so both panels (and any number of plugin instances created while navigating) share one SSH session to a given server, plus the in-memory password/passphrase cache for that session.
- **`NetResource`** wraps entries from the mounted SFTP filesystem as ordinary `NuclrResource`s backed by a real `java.nio.file.Path`. Because the path is genuinely on a `java.nio.file.FileSystem`, the host's existing copy engines, quick-view plugins, and viewers work against it with no special-casing — only F4 Edit needs plugin-specific handling (see `NetEditService`), since editors need a fast local scratch file with conflict detection rather than reading/writing over the network on every keystroke.
- **`NetTransferEngine`** copies files into a remote directory: local sources upload via SCP (fast, matches the task's "SCP for transfers" requirement); sources on another virtual filesystem (a different server, an archive mount, …) fall back to a generic stream copy, since SCP is a single-host protocol.
- **`NetFindService`** streams `find -print0` output from an exec channel for glob searches, or walks the tree over SFTP (used for regex searches, and as the fallback when the remote `find` is missing or fails outright).
- **`NetEditService`** downloads the remote file via SCP, hands a `mainpanel.edit` event pointing at the local temp copy to the host (which shows the usual text/hex editor chooser), and polls the temp copy on a background thread. On a save it re-stats the remote file; if it changed since download/last-upload, the user is asked before overwriting. Otherwise it uploads to a hidden temp sibling (`.name.nuclr-<token>.tmp`) via SCP and renames it over the target with SFTP's overwriting rename (falling back to delete-then-rename if the server rejects that).

## 🗂️ Source layout

```text
src/main/java/dev/nuclr/plugin/core/panel/net/
├── NetFilePanelPlugin.java     plugin entry point: navigation, actions, function-key bar
├── NetResource.java            a remote file/folder entry (real SFTP-backed Path)
├── NetVirtualResource.java     the "Net" root and server-list entries (path-less)
├── find/                       Alt+F7 Find: remote find + SFTP-walk fallback, dialog, results window
├── service/                    copy/move/delete/mkdir/edit engines and shared dialog helpers
├── ssh/                        SSH session, SFTP/SCP access, server profiles, key loading, host-key gate
├── tail/                       tail -F viewer window
└── ui/                         connection dialog, credential/host-key prompts
```

## 📚 Dependencies

| Library | Version | Purpose |
|---|---|---|
| `dev.nuclr:platform-sdk` | `3.0.2` | Nuclr platform interfaces |
| `sshd-sftp` | `2.12.1` | Apache MINA SSHD — SFTP client/filesystem |
| `sshd-scp` | `2.12.1` | Apache MINA SSHD — SCP transfers |
| `sshd-putty` | `2.12.1` | Apache MINA SSHD — PuTTY PPK key parsing |
| `jackson-databind` | `2.21.0` | Server profile JSON persistence |

## ⚠️ Known limitations

- **Hex/quick-view writes bypass the atomic-upload flow.** F4 Edit routes through the temp-copy-and-atomic-rename workflow described above, so both the text and hex editors get conflict detection there. Quick View (Ctrl+Q) and any other code path that writes directly to the SFTP-backed `Path` (rather than going through F4) writes straight to the remote file with a plain SFTP write — no atomicity, no conflict check.
- **Same-server copies are not server-side.** Copying between two folders of the *same* connection currently downloads-then-uploads over SFTP rather than issuing a server-side copy command; correct, but not as fast as it could be.
- **`tail -F` reconnect resumes from "now", not from the exact byte offset.** If the SSH session drops mid-tail, a couple of lines written during the gap could be missed (the new `tail` invocation starts with `-n 0`).
- **Remote `find` regex dialects vary too much to target directly** (GNU vs BusyBox vs macOS), so regex searches always use the slower SFTP-walk fallback, even when the server's `find` would have supported an equivalent flag.
- **No FTP/FTPS support** — only SFTP/SCP over SSH, per the task scope.
- **Deleting a remote file is always permanent** — there is no server-side trash to move it to.

## 📜 License

Apache License 2.0 — see [LICENSE](LICENSE).
