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
package dev.nuclr.plugin.core.panel.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Covers the F9 "Go to Folder" path-resolution logic. */
class NetFilePanelPluginTest {

	@Test
	void absoluteEntryIsUsedAsGiven() {
		assertEquals("/etc/ssh", NetFilePanelPlugin.resolveEnteredPath("/home/alice", "/etc/ssh"));
	}

	@Test
	void absoluteEntryIsNormalized() {
		assertEquals("/etc", NetFilePanelPlugin.resolveEnteredPath("/home/alice", "/etc//ssh/.."));
	}

	@Test
	void relativeSubfolderIsJoinedOntoCurrentPath() {
		assertEquals("/home/alice/projects", NetFilePanelPlugin.resolveEnteredPath("/home/alice", "projects"));
	}

	@Test
	void relativeParentReferenceWalksUpFromCurrentPath() {
		assertEquals("/home", NetFilePanelPlugin.resolveEnteredPath("/home/alice", ".."));
	}

	@Test
	void relativeEntryFromRootStaysUnderRoot() {
		assertEquals("/etc", NetFilePanelPlugin.resolveEnteredPath("/", "etc"));
	}

}
