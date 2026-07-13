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
package dev.nuclr.plugin.core.panel.net.find;

import java.util.regex.Pattern;

/**
 * Converts a shell-style glob ({@code *}, {@code ?}) into a {@link Pattern}
 * that matches a bare file name (no path separators are treated specially,
 * since matching is always applied per entry name).
 */
public final class GlobMatcher {

	private GlobMatcher() {
	}

	/**
	 * Compile a glob pattern.
	 *
	 * @param glob          the glob text ({@code *} and {@code ?} wildcards)
	 * @param caseSensitive whether the match distinguishes case
	 * @return the compiled pattern
	 */
	public static Pattern compile(String glob, boolean caseSensitive) {
		String regex = toRegex(glob);
		int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
		return Pattern.compile(regex, flags);
	}

	/**
	 * Translate a glob into an equivalent anchored regular expression.
	 *
	 * @param glob the glob text
	 * @return the regex text (anchored with {@code ^...$})
	 */
	static String toRegex(String glob) {
		StringBuilder sb = new StringBuilder("^");
		for (int i = 0; i < glob.length(); i++) {
			char c = glob.charAt(i);
			switch (c) {
				case '*' -> sb.append(".*");
				case '?' -> sb.append('.');
				case '.', '(', ')', '+', '|', '^', '$', '@', '%', '[', ']', '{', '}', '\\' -> {
					sb.append('\\').append(c);
				}
				default -> sb.append(c);
			}
		}
		return sb.append('$').toString();
	}

}
