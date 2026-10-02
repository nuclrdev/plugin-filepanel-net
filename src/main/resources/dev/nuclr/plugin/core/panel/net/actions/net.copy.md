# net.copy — Copy between servers

Copies a file or a whole folder from one saved server to another — or within one —
streaming it through Commander. **The content never comes back to you**, which makes
this the right way to move files holding secrets (an `.env`, a private key, a
database dump) between servers without reading them.

- `toPath` is the copy's exact path. Copying the file `/opt/a/.env` to `toPath`
  `/opt/b/.env` creates that file; copying the folder `/srv/site` to `/srv/site-new`
  puts the folder's contents in `/srv/site-new` (merging if it exists).
- Existing files are never replaced unless `overwrite: true`, and even then the user
  is asked first. Without it the action fails before copying anything and says which
  files are in the way.
- Each file is copied to a hidden temporary file and renamed into place only when
  complete, so a failed or cancelled copy never leaves a half-written file, and a
  file being replaced stays intact until its copy has fully arrived.
- Copying something onto itself or into itself is refused, also when the two server
  names are different profiles for the same machine.
- Symbolic links inside a folder are skipped and counted in `skippedSymlinks`.
- Permissions work like `cp`: a file that is replaced keeps its own permissions (a
  0600 secrets file stays private, a script stays executable); a new file gets the
  source's. If the server will not set exactly those permissions, nothing is
  replaced or created: the file would otherwise become more widely readable or stop
  being executable. Change permissions deliberately with `net.exec` (`chmod`).

Example: `{ "fromServer": "Monolith", "fromPath": "/opt/nuclr/.env", "toServer": "nuclr-worker", "toPath": "/opt/nuclr/.env.from-monolith" }`
