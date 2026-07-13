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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerStoreTest {

	@TempDir
	Path tempDir;

	@Test
	void loadOnMissingFileReturnsEmptyList() {
		var store = new ServerStore(tempDir.resolve("servers.json"));
		assertTrue(store.load().isEmpty());
	}

	@Test
	void upsertThenLoadRoundTrips() throws IOException {
		var store = new ServerStore(tempDir.resolve("servers.json"));
		var config = new ServerConfig();
		config.setHost("example.com");
		config.setUsername("alice");

		store.upsert(config);

		List<ServerConfig> loaded = store.load();
		assertEquals(1, loaded.size());
		assertEquals("example.com", loaded.get(0).getHost());
		assertEquals(config.getId(), loaded.get(0).getId());
	}

	@Test
	void upsertReplacesExistingProfileById() throws IOException {
		var store = new ServerStore(tempDir.resolve("servers.json"));
		var config = new ServerConfig();
		config.setHost("example.com");
		store.upsert(config);

		config.setHost("changed.example.com");
		store.upsert(config);

		List<ServerConfig> loaded = store.load();
		assertEquals(1, loaded.size());
		assertEquals("changed.example.com", loaded.get(0).getHost());
	}

	@Test
	void removeDeletesById() throws IOException {
		var store = new ServerStore(tempDir.resolve("servers.json"));
		var a = new ServerConfig();
		a.setHost("a.example.com");
		var b = new ServerConfig();
		b.setHost("b.example.com");
		store.upsert(a);
		store.upsert(b);

		store.remove(a.getId());

		List<ServerConfig> loaded = store.load();
		assertEquals(1, loaded.size());
		assertEquals("b.example.com", loaded.get(0).getHost());
	}

	@Test
	void byIdFindsMatchingProfile() throws IOException {
		var store = new ServerStore(tempDir.resolve("servers.json"));
		var config = new ServerConfig();
		config.setHost("example.com");
		store.upsert(config);

		assertEquals("example.com", store.byId(config.getId()).getHost());
		assertNull(store.byId("does-not-exist"));
	}

	@Test
	void neverPersistsPasswordField() throws IOException {
		// ServerConfig intentionally has no password/passphrase field at all;
		// this test documents that guarantee so a future field addition would
		// need a deliberate, reviewed change rather than an accidental leak.
		// (The authMethod enum value "PASSWORD" legitimately appears in the
		// JSON, so the check below looks for a "password"/"passphrase" *key*,
		// not just the substring.)
		var store = new ServerStore(tempDir.resolve("servers.json"));
		var config = new ServerConfig();
		config.setHost("example.com");
		store.upsert(config);

		String json = Files.readString(store.file());
		assertTrue(json.contains("\"host\""));
		assertFalse(json.contains("\"password\""));
		assertFalse(json.contains("\"passphrase\""));
	}

}
