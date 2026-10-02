# net.exec — Run a command on a server

Runs one command line on the server through the login user's shell, the way
`ssh server 'command'` does, and returns `exitCode`, `stdout` and `stderr`. Output is
also streamed to the user while the command runs.

This action can do anything the login user can, so Commander may ask the user to
approve it first. Say plainly what the command is for.

**There is no terminal and no input:**

- Nothing can be typed in. Standard input is closed, so a command that waits for
  input ends at once (or fails) rather than hanging.
- Use non-interactive forms: `apt-get -y`, `DEBIAN_FRONTEND=noninteractive`,
  `sudo -n` (fails instead of asking for a password), `git --no-pager`,
  `systemctl --no-pager`, `mysql -e "…"`.
- Interactive programs (`top`, `vim`, `less`, a bare `mysql` shell) do not work.

**Limits:**

- `timeoutSeconds` defaults to 300 (at most 3600). A command still running then is
  stopped, `timedOut` is true and `exitCode` is -1. For longer work, start it in the
  background (`nohup … > /tmp/job.log 2>&1 &`) and check the log later.
- `stdout` and `stderr` keep the last 256 KB each; `truncated` says when earlier
  output was left out.
- `cwd` runs the whole command line in that folder. If the folder cannot be entered,
  nothing runs: the exit code is non-zero and `stderr` says why.

Example: `{ "server": "nuclr-db", "command": "systemctl status mysql --no-pager" }`
