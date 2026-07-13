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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShellEscapeTest {

	@Test
	void quotesPlainArgument() {
		assertEquals("'hello'", ShellEscape.quote("hello"));
	}

	@Test
	void quotesArgumentWithSpaces() {
		assertEquals("'hello world'", ShellEscape.quote("hello world"));
	}

	@Test
	void splicesEmbeddedSingleQuote() {
		assertEquals("'it'\\''s'", ShellEscape.quote("it's"));
	}

	@Test
	void quotesEmptyStringAsEmptyQuotes() {
		assertEquals("''", ShellEscape.quote(""));
	}

	@Test
	void neutralizesShellMetacharacters() {
		// The quoted result must be inert: none of these chars can trigger
		// expansion, substitution, or command chaining when the shell parses it.
		String dangerous = "$(rm -rf /); `echo pwned`; a && b || c; a|b; a>b; a<b; a;b";
		String quoted = ShellEscape.quote(dangerous);
		assertEquals("'" + dangerous.replace("'", "'\\''") + "'", quoted);
	}

	@Test
	void rejectsNulByte() {
		assertThrows(IllegalArgumentException.class, () -> ShellEscape.quote("bad\0name"));
	}

	@Test
	void rejectsNullArgument() {
		assertThrows(IllegalArgumentException.class, () -> ShellEscape.quote(null));
	}

	@Test
	void plainPathIsSafeForUnquotedScp() {
		assertTrue(ShellEscape.isSafeForUnquotedScp("/home/alice/report.txt"));
		assertTrue(ShellEscape.isSafeForUnquotedScp("/var/log/app-2026.01.01.log"));
		assertTrue(ShellEscape.isSafeForUnquotedScp("/a/b_c/d-e/f.tar.gz"));
	}

	@Test
	void pathWithSpaceIsNotSafeForUnquotedScp() {
		// MINA's ScpClient embeds the remote path unquoted in the exec command it
		// sends the server; a space would be parsed as a separate shell argument.
		assertFalse(ShellEscape.isSafeForUnquotedScp("/home/alice/my file.txt"));
	}

	@Test
	void pathWithParensIsNotSafeForUnquotedScp() {
		// Parentheses are shell metacharacters (subshell syntax).
		assertFalse(ShellEscape.isSafeForUnquotedScp("/home/alice/image (1).png"));
	}

	@Test
	void pathWithOtherShellMetacharactersIsNotSafeForUnquotedScp() {
		assertFalse(ShellEscape.isSafeForUnquotedScp("/tmp/a;rm -rf b"));
		assertFalse(ShellEscape.isSafeForUnquotedScp("/tmp/$HOME"));
		assertFalse(ShellEscape.isSafeForUnquotedScp("/tmp/a`b`"));
		assertFalse(ShellEscape.isSafeForUnquotedScp("/tmp/a&b"));
		assertFalse(ShellEscape.isSafeForUnquotedScp("/tmp/a|b"));
		assertFalse(ShellEscape.isSafeForUnquotedScp("/tmp/a'b"));
		assertFalse(ShellEscape.isSafeForUnquotedScp("/tmp/a\"b"));
		assertFalse(ShellEscape.isSafeForUnquotedScp("/tmp/a*b"));
	}

	@Test
	void nullOrEmptyPathIsNotSafeForUnquotedScp() {
		assertFalse(ShellEscape.isSafeForUnquotedScp(null));
		assertFalse(ShellEscape.isSafeForUnquotedScp(""));
	}

}
