# net.list — List a folder on a server

Lists a remote folder over SFTP, sorted by name.

- `path` may be absolute (`/etc/nginx`), relative to the login folder
  (`app/logs`), or `~` / omitted for the login folder.
- Each entry has `name`, `type` (`file`, `directory`, `symlink`, `other`), `size`
  in bytes, `modified` (ISO-8601) and `permissions` (`rwxr-xr-x`).
- At most `limit` entries come back (default 500); `truncated` is true and `total`
  gives the full count when there are more. For a big folder, `net.exec` with
  `find` or `ls | grep` is usually the better tool.

Example: `{ "server": "nuclr-db", "path": "/var/log/mysql" }`
