/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at

	http://www.apache.org/licenses/LICENSE-2.0

	Unless required by applicable law or agreed to in writing, software
	distributed under the License is distributed on an "AS IS" BASIS,
	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
	See the License for the specific language governing permissions and
	limitations under the License.

*/
package dev.nuclr.plugin.core.panel.net.ssh;

/**
 * Safe quoting of arguments for remote POSIX shells. Every argument sent to a
 * remote {@code exec} channel ({@code find}, {@code tail}, …) must go through
 * {@link #quote} so that file names containing spaces, quotes, globs or shell
 * metacharacters can never be interpreted as shell syntax.
 */
public final class ShellEscape {

	private ShellEscape() {
	}

	/**
	 * Quote a single argument for a POSIX shell using single quotes; embedded
	 * single quotes are emitted as the standard {@code '\''} splice.
	 *
	 * @param argument the raw argument, never {@code null}
	 * @return the safely quoted argument
	 * @throws IllegalArgumentException if the argument contains a NUL byte,
	 *                                  which cannot be represented in a shell
	 *                                  command line
	 */
	public static String quote(String argument) {

		if (argument == null) {
			throw new IllegalArgumentException("argument must not be null");
		}
		if (argument.indexOf('\0') >= 0) {
			throw new IllegalArgumentException("argument must not contain NUL bytes");
		}
		if (argument.isEmpty()) {
			return "''";
		}

		var sb = new StringBuilder(argument.length() + 2);
		sb.append('\'');
		for (int i = 0; i < argument.length(); i++) {
			char c = argument.charAt(i);
			if (c == '\'') {
				sb.append("'\\''");
			} else {
				sb.append(c);
			}
		}
		sb.append('\'');
		return sb.toString();
	}

	/**
	 * Return {@code true} when {@code path} contains only characters that are
	 * safe to embed <b>unquoted</b> in a shell command line.
	 *
	 * <p>Apache MINA's SCP client ({@code ScpClient.upload}/{@code download})
	 * builds its {@code scp -t}/{@code scp -f} exec command by concatenating
	 * the remote path directly into the command string, with no shell quoting
	 * at all — a MINA limitation, not something callers can override, since the
	 * same string is also used verbatim as the literal filename recorded in the
	 * SCP protocol stream (quoting it would corrupt that filename instead).
	 * Most SSH servers run exec commands through a shell, so a path containing
	 * spaces, parentheses or other shell-significant characters silently
	 * breaks the remote {@code scp} invocation (typically surfacing as an
	 * {@code EOFException: readAck - EOF before ACK}, since the shell fails to
	 * even start the command). Callers should check this before choosing SCP
	 * for a given path and fall back to a plain SFTP read/write otherwise.
	 *
	 * @param path the remote path to check
	 * @return {@code true} when the path is safe to pass to MINA's SCP client unquoted
	 */
	public static boolean isSafeForUnquotedScp(String path) {

		if (path == null || path.isEmpty()) {
			return false;
		}

		for (int i = 0; i < path.length(); i++) {
			char c = path.charAt(i);
			boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
					|| c == '/' || c == '.' || c == '_' || c == '-';
			if (!safe) {
				return false;
			}
		}
		return true;
	}

}
