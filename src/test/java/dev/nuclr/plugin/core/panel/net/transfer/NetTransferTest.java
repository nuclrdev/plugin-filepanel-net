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
package dev.nuclr.plugin.core.panel.net.transfer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.plugin.core.panel.net.NetFilePanelPlugin;
import dev.nuclr.plugin.core.panel.net.ssh.ServerConfig;
import dev.nuclr.plugin.core.panel.net.ssh.ServerStore;

class NetTransferTest {

	@TempDir
	Path temp;

	private Path file;
	private ServerStore store;
	private NetFilePanelPlugin plugin;

	@BeforeEach
	void setUp() {
		file = temp.resolve("net").resolve("servers.json");
		store = new ServerStore(file);
		plugin = plugin(store);
	}

	private static NetFilePanelPlugin plugin(ServerStore store) {
		var plugin = new NetFilePanelPlugin(store);
		plugin.preinit(null);
		plugin.init();
		return plugin;
	}

	/** One action through the plugin's act, as Commander sends it. */
	private Map<String, Object> act(String action, Map<String, Object> input) {
		return act(plugin, action, input);
	}

	private static Map<String, Object> act(NetFilePanelPlugin plugin, String action, Map<String, Object> input) {
		var data = new HashMap<String, Object>(input);
		data.put("transfer.protocol", 1);
		if (!action.equals(NetTransfer.Describe)) {
			data.putIfAbsent("type", "connections");
		}
		plugin.act(null, action, List.of(), null, data, null);
		return data;
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> rows() {
		return (List<Map<String, Object>>) act(NetTransfer.Snapshot, Map.of()).get("rows");
	}

	private static ServerConfig server(String id, String name, String host) {
		var config = new ServerConfig();
		config.setId(id);
		config.setName(name);
		config.setHost(host);
		config.setUsername("alice");
		return config;
	}

	@Test
	void describeDeclaresTheConnectionsCategoryWithEveryFieldTagged() {
		var data = act(NetTransfer.Describe, Map.of());

		@SuppressWarnings("unchecked")
		var providers = (List<Map<String, Object>>) data.get("transfer.providers");
		assertEquals(1, providers.size());
		var provider = providers.getFirst();
		assertEquals("connections", provider.get("type"));
		assertEquals(1, provider.get("version"));
		assertEquals("Net (SSH/SFTP)", provider.get("title"));
		var kinds = new HashMap<String, Object>();
		for (Object field : (List<?>) provider.get("fields")) {
			var map = (Map<?, ?>) field;
			String name = (String) map.get("name");
			assertTrue(name.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}"), name);
			kinds.put(name, map.get("kind"));
		}
		assertEquals(Map.of("name", "PLAIN", "host", "ROUTING", "port", "ROUTING", "username", "ROUTING",
				"authMethod", "PLAIN", "privateKeyPath", "PRIVATE_KEY_PATH", "initialPath", "PLAIN"), kinds);
		assertFalse(kinds.containsValue("SECRET"), "Net stores no secrets");
	}

	@Test
	void aSnapshotGivesEveryServerAsStringsAndNeverAPasswordInTheHost() throws IOException {
		var config = server("a", "worker", "deploy:hunter2@worker.internal");
		config.setPort(2222);
		config.setAuthMethod(ServerConfig.AuthMethod.KEY);
		config.setPrivateKeyPath("/home/alice/.ssh/id_ed25519");
		config.setInitialPath("/srv");
		store.upsert(config);

		var row = rows().getFirst();

		assertEquals("a", row.get("rowId"));
		assertEquals("worker", row.get("label"));
		assertEquals(false, row.get("hasSecrets"));
		assertEquals(Map.of("name", "worker", "host", "worker.internal", "port", "2222", "username", "alice",
				"authMethod", "KEY", "privateKeyPath", "/home/alice/.ssh/id_ed25519", "initialPath", "/srv"),
				row.get("fields"));
		assertFalse(row.toString().contains("hunter2"));
	}

	@Test
	void anEntryWithoutAnIdKeepsOneStableRowWithinTheSession() throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, "[{\"id\":\"a\",\"host\":\"one\"},{\"host\":\"legacy\",\"username\":\"bob\"}]");

		var first = rows();
		var second = rows();

		assertEquals(2, first.size());
		assertEquals("#2", first.get(1).get("rowId"));
		assertEquals("bob@legacy", first.get(1).get("label"));
		assertEquals(first.get(1).get("rowId"), second.get(1).get("rowId"));
		assertEquals(first.get(1).get("fields"), second.get(1).get("fields"));
	}

	@Test
	void readSecretsIsAlwaysEmpty() throws IOException {
		store.upsert(server("a", "worker", "worker.internal"));
		rows();

		assertEquals(Map.of(), act(NetTransfer.ReadSecrets, Map.of("rowId", "a")).get("secrets"));
		assertEquals(Map.of(), act(NetTransfer.ReadSecrets, Map.of("rowId", "unknown")).get("secrets"));
	}

	@Test
	void findEmbeddedSecretsNamesAHostThatStillCarriesAPassword() {
		assertEquals(List.of("host"),
				act(NetTransfer.FindEmbeddedSecrets, Map.of("fields", Map.of("host", "bob:pw@h"))).get("fieldNames"));
		assertEquals(List.of(),
				act(NetTransfer.FindEmbeddedSecrets, Map.of("fields", Map.of("host", "bob@h"))).get("fieldNames"));
		assertEquals(List.of(),
				act(NetTransfer.FindEmbeddedSecrets, Map.of("fields", Map.of("host", "fe80::1"))).get("fieldNames"));
	}

	@Test
	void applyAppendsUnderTheGivenIdsKeepsTheRestAndUsesThePlacedKeyFile() throws IOException {
		store.upsert(server("existing", "local", "local.internal"));

		var data = act(NetTransfer.Apply, Map.of("items", List.of(
				item("new-1", Map.of("name", "worker", "host", "deploy:crafted@worker", "port", "2222",
						"username", "deploy", "authMethod", "KEY", "privateKeyPath", "/old/id_ed25519",
						"initialPath", "/srv"), Map.of("privateKeyPath", "/home/u/.ssh/nuclr/id_ed25519")),
				item("new-2", Map.of("host", "web"), Map.of()))));

		var all = store.loadStrictly();
		assertEquals(List.of("existing", "new-1", "new-2"), all.stream().map(ServerConfig::getId).toList());
		var worker = store.byId("new-1");
		assertEquals("worker", worker.getName());
		assertEquals("worker", worker.getHost(), "a crafted file cannot put a password into the host");
		assertEquals(2222, worker.getPort());
		assertEquals("deploy", worker.getUsername());
		assertEquals(ServerConfig.AuthMethod.KEY, worker.getAuthMethod());
		assertEquals("/home/u/.ssh/nuclr/id_ed25519", worker.getPrivateKeyPath());
		assertEquals("/srv", worker.getInitialPath());
		var web = store.byId("new-2");
		assertEquals(22, web.getPort());
		assertEquals(ServerConfig.AuthMethod.PASSWORD, web.getAuthMethod());
		assertFalse(data.containsKey("recoveredSecrets"), "Net has no secret field to recover into");
		assertFalse(Files.readString(file).contains("crafted"));
		// Net has no keychain access at all; the only file it touches is servers.json.
		try (var files = Files.list(file.getParent())) {
			assertEquals(List.of(file), files.toList());
		}
	}

	@Test
	void applyKeepsAnExistingEntryExactlyAsStored() throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, "[{\"host\":\"legacy\",\"futureField\":\"kept\"}]");

		act(NetTransfer.Apply, Map.of("items", List.of(item("n", Map.of("host", "x"), Map.of()))));

		String json = Files.readString(file);
		assertTrue(json.contains("\"futureField\""), json);
		assertEquals(2, store.loadStrictly().size());
		assertEquals(null, store.loadStrictly().getFirst().getId(), "an id-less entry is not given one");
	}

	@Test
	void applyRefusesAnIdThatIsAlreadyTaken() throws IOException {
		store.upsert(server("taken", "local", "local.internal"));
		byte[] before = Files.readAllBytes(file);

		assertThrows(IllegalArgumentException.class,
				() -> act(NetTransfer.Apply, Map.of("items", List.of(item("taken", Map.of("host", "x"), Map.of())))));
		assertArrayEquals(before, Files.readAllBytes(file));
	}

	@Test
	void applyOverAnUnreadableStoreThrowsAndChangesNothing() throws IOException {
		Files.createDirectories(file.getParent());
		byte[] corrupt = "{not json".getBytes(StandardCharsets.UTF_8);
		Files.write(file, corrupt);

		assertThrows(UncheckedIOException.class,
				() -> act(NetTransfer.Apply, Map.of("items", List.of(item("n", Map.of("host", "x"), Map.of())))));
		assertThrows(UncheckedIOException.class, () -> act(NetTransfer.Remove, Map.of("ids", List.of("n"))));
		assertArrayEquals(corrupt, Files.readAllBytes(file));
	}

	@Test
	void aFailedWriteThrowsInsteadOfBeingSwallowed() throws IOException {
		var failing = new ServerStore(file) {
			@Override
			protected void replaceFile(Path tempFile, Path target) throws IOException {
				throw new IOException("disk full");
			}
		};
		store.upsert(server("existing", "local", "local.internal"));
		byte[] before = Files.readAllBytes(file);
		var failingPlugin = plugin(failing);

		assertThrows(UncheckedIOException.class, () -> act(failingPlugin, NetTransfer.Apply,
				Map.of("items", List.of(item("n", Map.of("host", "x"), Map.of())))));
		assertArrayEquals(before, Files.readAllBytes(file));
		try (var files = Files.list(file.getParent())) {
			assertEquals(List.of(file), files.toList(), "no temporary file is left behind");
		}
	}

	@Test
	void removeDeletesOnlyTheGivenIds() throws IOException {
		store.upsert(server("keep", "a", "a.internal"));
		store.upsert(server("drop", "b", "b.internal"));

		act(NetTransfer.Remove, Map.of("ids", List.of("drop", "unknown")));

		assertEquals(List.of("keep"), store.loadStrictly().stream().map(ServerConfig::getId).toList());
	}

	@Test
	void anotherProtocolVersionOrCategoryIsNotAnswered() {
		var data = new HashMap<String, Object>();
		data.put("transfer.protocol", 2);
		plugin.act(null, NetTransfer.Describe, List.of(), null, data, null);
		assertFalse(data.containsKey("transfer.providers"));

		var other = act(NetTransfer.Snapshot, Map.of("type", "profiles"));
		assertFalse(other.containsKey("rows"));

		var applyOther = act(NetTransfer.Apply,
				Map.of("type", "profiles", "items", List.of(item("n", Map.of("host", "x"), Map.of()))));
		assertFalse(applyOther.containsKey("recoveredSecrets"));
		assertFalse(Files.exists(file), "nothing was written for a category Net does not have");
	}

	@Test
	void everyExportedFieldIsDeclared() {
		var config = server("a", "n", "h");
		config.setPrivateKeyPath("k");
		config.setInitialPath("/p");
		var exported = new ArrayList<>(NetTransfer.fields(config).keySet());
		assertEquals(7, exported.size(), exported.toString());
		assertTrue(NetTransfer.fieldNames().containsAll(exported), exported.toString());
	}

	private static Map<String, Object> item(String id, Map<String, String> fields, Map<String, String> keyFiles) {
		return Map.of("newId", id, "fields", fields, "keyFiles", keyFiles);
	}
}
