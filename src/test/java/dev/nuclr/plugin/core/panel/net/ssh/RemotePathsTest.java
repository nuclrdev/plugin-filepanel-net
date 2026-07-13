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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RemotePathsTest {

	@Test
	void normalizesBlankToRoot() {
		assertEquals("/", RemotePaths.normalize(null));
		assertEquals("/", RemotePaths.normalize(""));
		assertEquals("/", RemotePaths.normalize("   "));
	}

	@Test
	void collapsesRepeatedSeparators() {
		assertEquals("/a/b", RemotePaths.normalize("/a//b///"));
	}

	@Test
	void resolvesDotSegments() {
		assertEquals("/a/c", RemotePaths.normalize("/a/./b/../c"));
	}

	@Test
	void dotDotNeverEscapesRoot() {
		assertEquals("/", RemotePaths.normalize("/../../.."));
		assertEquals("/x", RemotePaths.normalize("/../../x"));
	}

	@Test
	void joinsChildOntoParent() {
		assertEquals("/home/user/file.txt", RemotePaths.join("/home/user", "file.txt"));
		assertEquals("/home/user", RemotePaths.join("/home/user", null));
		assertEquals("/home/user", RemotePaths.join("/home/user", ""));
	}

	@Test
	void parentOfRootIsNull() {
		assertNull(RemotePaths.parent("/"));
	}

	@Test
	void parentOfTopLevelIsRoot() {
		assertEquals("/", RemotePaths.parent("/etc"));
	}

	@Test
	void parentOfNestedPath() {
		assertEquals("/etc/ssh", RemotePaths.parent("/etc/ssh/sshd_config"));
	}

	@Test
	void nameExtractsLastSegment() {
		assertEquals("sshd_config", RemotePaths.name("/etc/ssh/sshd_config"));
		assertEquals("/", RemotePaths.name("/"));
	}

	@Test
	void isRootDetection() {
		assertTrue(RemotePaths.isRoot("/"));
		assertTrue(RemotePaths.isRoot(""));
		assertFalse(RemotePaths.isRoot("/etc"));
	}

}
