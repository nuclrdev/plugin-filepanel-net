# net.write — Write a file on a server

Creates a file, or replaces one, with `content` as its complete new content.

- Replacing an existing file shows the user a **diff** of the change and waits for
  approval; if it is declined, nothing is written and the action fails saying so.
  A file that already has exactly this content is left alone (`unchanged: true`).
- The write is atomic: the content goes to a hidden temporary file beside the target
  and is then renamed over it, so nobody ever sees a half-written file. An existing
  file keeps its permissions (a 0600 file stays private, a script stays
  executable); if the server will not set exactly those, the file is not replaced.
- Binary content: pass it base64-encoded with `encoding: "base64"`.
- The parent folder must exist, unless `createDirs: true`.
- Owner and group are those of the login user; for files only root may write, write
  to a temporary path and move it with `net.exec` and `sudo -n`.

Example: `{ "server": "nuclr-web", "path": "/opt/nuclr/.env", "content": "KEY=value\n" }`
