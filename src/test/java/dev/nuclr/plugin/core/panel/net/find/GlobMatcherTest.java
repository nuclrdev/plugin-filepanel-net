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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GlobMatcherTest {

	@Test
	void starMatchesAnySuffix() {
		var pattern = GlobMatcher.compile("*.log", true);
		assertTrue(pattern.matcher("app.log").matches());
		assertTrue(pattern.matcher("2024-01-01-app.log").matches());
		assertFalse(pattern.matcher("app.log.gz").matches());
	}

	@Test
	void questionMarkMatchesSingleChar() {
		var pattern = GlobMatcher.compile("file?.txt", true);
		assertTrue(pattern.matcher("file1.txt").matches());
		assertFalse(pattern.matcher("file12.txt").matches());
		assertFalse(pattern.matcher("file.txt").matches());
	}

	@Test
	void caseSensitivityIsHonoured() {
		var sensitive = GlobMatcher.compile("*.LOG", true);
		assertFalse(sensitive.matcher("app.log").matches());

		var insensitive = GlobMatcher.compile("*.LOG", false);
		assertTrue(insensitive.matcher("app.log").matches());
	}

	@Test
	void regexMetacharactersInGlobAreLiteral() {
		// A glob like "a.b+c" should only match that literal name (the '.', '+'
		// are not regex metacharacters here), not e.g. "axb+c".
		var pattern = GlobMatcher.compile("a.b+c", true);
		assertTrue(pattern.matcher("a.b+c").matches());
		assertFalse(pattern.matcher("axb+c").matches());
	}

}
