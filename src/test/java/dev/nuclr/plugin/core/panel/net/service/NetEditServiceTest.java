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
package dev.nuclr.plugin.core.panel.net.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.panel.net.service.NetEditService.SyncAction;

/**
 * Unit tests for the pure decision logic behind the F4 edit-session watcher:
 * whether a poll cycle should upload, do nothing, or flag a conflict, and how
 * the hidden upload-sibling name is derived. The network/IO side (download,
 * SCP upload, atomic rename) is exercised indirectly through these building
 * blocks; a full SSH round trip is out of scope for a unit test.
 */
class NetEditServiceTest {

	@Test
	void noLocalChangeMeansNothingToDo() {
		assertEquals(SyncAction.NONE, NetEditService.decide(false, false));
		assertEquals(SyncAction.NONE, NetEditService.decide(false, true));
	}

	@Test
	void localChangeAloneUploads() {
		assertEquals(SyncAction.UPLOAD, NetEditService.decide(true, false));
	}

	@Test
	void bothChangedIsAConflict() {
		assertEquals(SyncAction.CONFLICT, NetEditService.decide(true, true));
	}

	@Test
	void uploadSiblingLivesInSameDirectoryAsTarget() {
		String sibling = NetEditService.uploadSiblingPath("/etc/nginx/nginx.conf", "ab12cd34");
		assertEquals("/etc/nginx/.nginx.conf.nuclr-ab12cd34.tmp", sibling);
	}

	@Test
	void uploadSiblingIsHiddenAndCarriesOriginalName() {
		String sibling = NetEditService.uploadSiblingPath("/home/alice/notes.txt", "token");
		assertTrue(sibling.startsWith("/home/alice/."));
		assertTrue(sibling.contains("notes.txt"));
	}

	@Test
	void differentTokensProduceDifferentSiblingNames() {
		String first = NetEditService.uploadSiblingPath("/data/file.bin", "aaaa1111");
		String second = NetEditService.uploadSiblingPath("/data/file.bin", "bbbb2222");
		assertNotEquals(first, second);
	}

}
