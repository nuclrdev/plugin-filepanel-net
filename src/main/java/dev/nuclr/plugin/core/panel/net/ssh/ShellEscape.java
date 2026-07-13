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

}
