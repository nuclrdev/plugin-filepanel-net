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

/**
 * Immutable description of an Alt+F7 Find search over a remote server,
 * produced by {@code NetFindDialog} and consumed by {@link NetFindService}.
 *
 * @param rootPath      absolute remote directory to search under
 * @param namePattern   the filename pattern (glob or regex per {@code matchMode})
 * @param matchMode     how {@code namePattern} is interpreted
 * @param caseSensitive whether the match distinguishes case
 */
public record NetFindRequest(String rootPath, String namePattern, FindMatchMode matchMode, boolean caseSensitive) {

	/**
	 * Compact constructor validating the pattern is non-blank.
	 */
	public NetFindRequest {
		if (namePattern == null || namePattern.isBlank()) {
			throw new IllegalArgumentException("A Find search needs a non-blank name pattern");
		}
	}

}
