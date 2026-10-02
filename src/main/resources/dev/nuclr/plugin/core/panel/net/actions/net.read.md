# net.read — Read a file on a server

Reads a remote file over SFTP, from the start.

- Text comes back as `encoding: "utf-8"`; anything that is not valid UTF-8 (or
  contains NUL bytes) comes back base64-encoded.
- Up to `maxBytes` are read (default 256 KB, at most 4 MB). `truncated` is true when
  the file is longer — `size` is its full length. For the end of a large log,
  `net.exec` with `tail -n 200 <file>` is cheaper.
- Folders cannot be read; use `net.list`.

Example: `{ "server": "nuclr-db", "path": "/etc/mysql/mysql.conf.d/zz-nuclr.cnf" }`
