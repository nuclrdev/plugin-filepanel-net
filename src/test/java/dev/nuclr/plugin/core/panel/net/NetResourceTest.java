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

class NetResourceTest {

	@Test
	void permissionsStringRendersReadWriteExecuteBits() {
		// rwxr-xr-x = 0755
		assertEquals("rwxr-xr-x", NetResource.permissionsString(0b111_101_101));
	}

	@Test
	void permissionsStringRendersReadOnly() {
		// rw-r--r-- = 0644
		assertEquals("rw-r--r--", NetResource.permissionsString(0b110_100_100));
	}

	@Test
	void permissionsStringRendersNoPermissions() {
		assertEquals("---------", NetResource.permissionsString(0));
	}

	@Test
	void humanReadableSizeBelowOneKilobyte() {
		assertEquals("512 B", NetResource.humanReadableSize(512));
	}

	@Test
	void humanReadableSizeInKilobytes() {
		assertEquals("2 KB", NetResource.humanReadableSize(2048));
	}

	@Test
	void humanReadableSizeInMegabytesWithOneDecimal() {
		assertEquals("1.5 MB", NetResource.humanReadableSize(1_572_864));
	}

}
