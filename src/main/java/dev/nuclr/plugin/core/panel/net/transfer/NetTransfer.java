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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.nuclr.plugin.core.panel.net.ssh.ServerConfig;
import dev.nuclr.plugin.core.panel.net.ssh.ServerStore;

/**
 * This plugin's side of Commander's Configs > Export / Import, spoken over {@code act}
 * with plain maps (Commander's {@code TransferProtocol}; see
 * {@code commander/docs/settings-transfer-plan.md}, section 4). Saved servers are the
 * {@code connections} category.
 *
 * <p>Net stores no secrets: passwords and key passphrases are only ever prompted for and
 * kept in memory, so no field is declared SECRET, a row never has secrets and apply never
 * hands any back. The one place a password could still hide is a host typed as
 * {@code user:password@host}; a snapshot strips it, and so does apply (a crafted file
 * cannot put one into {@code servers.json}). With no secret field to keep it in, the
 * stripped password is dropped.
 *
 * <p>Called off the EDT, on the instance Commander keeps for actions.
 */
public final class NetTransfer {

	public static final String Describe = "transfer.describe";
	public static final String Snapshot = "transfer.snapshot";
	public static final String ReadSecrets = "transfer.readSecrets";
	public static final String FindEmbeddedSecrets = "transfer.findEmbeddedSecrets";
	public static final String Apply = "transfer.apply";
	public static final String Remove = "transfer.remove";

	static final int ProtocolVersion = 1;
	static final String Type = "connections";
	static final int SectionVersion = 1;
	static final String Title = "Net (SSH/SFTP)";

	// Field names, as they appear in the export file.
	static final String Name = "name";
	static final String Host = "host";
	static final String Port = "port";
	static final String Username = "username";
	static final String AuthMethod = "authMethod";
	static final String PrivateKeyPath = "privateKeyPath";
	static final String InitialPath = "initialPath";

	private final ServerStore store;

	public NetTransfer(ServerStore store) {
		this.store = store;
	}

	public static boolean handles(String action) {
		return switch (action) {
			case Describe, Snapshot, ReadSecrets, FindEmbeddedSecrets, Apply, Remove -> true;
			default -> false;
		};
	}

	/**
	 * Answer one transfer action into {@code data}. Throws on failure; Commander treats a
	 * throw as "the plugin failed" and, for apply, relies on the store being unchanged.
	 */
	public synchronized void handle(String action, Map<String, Object> data) {
		if (!(data.get("transfer.protocol") instanceof Integer version) || version != ProtocolVersion) {
			return; // a protocol this build does not speak: do not take part
		}
		if (!Describe.equals(action) && !Type.equals(data.get("type"))) {
			return;
		}
		switch (action) {
			case Describe -> data.put("transfer.providers", List.of(describe()));
			case Snapshot -> data.put("rows", snapshot());
			case ReadSecrets -> data.put("secrets", readSecrets());
			case FindEmbeddedSecrets -> data.put("fieldNames", findEmbeddedSecrets(strings(data.get("fields"))));
			case Apply -> apply(items(data.get("items")));
			case Remove -> remove(ids(data.get("ids")));
			default -> {
			}
		}
	}

	static Map<String, Object> describe() {
		var fields = new ArrayList<Map<String, Object>>();
		fields.add(field(Name, "PLAIN"));
		fields.add(field(Host, "ROUTING"));
		fields.add(field(Port, "ROUTING"));
		fields.add(field(Username, "ROUTING"));
		fields.add(field(AuthMethod, "PLAIN"));
		fields.add(field(PrivateKeyPath, "PRIVATE_KEY_PATH"));
		fields.add(field(InitialPath, "PLAIN"));
		return Map.of("type", Type, "version", SectionVersion, "title", Title, "fields", fields);
	}

	/**
	 * One strict read of {@code servers.json}, in file order. Row ids are the stored ids; an
	 * entry saved without one (the panel gives it a new random id on every load) gets
	 * {@code "#" + its place in the file}, which stays the same as long as the file does.
	 */
	List<Map<String, Object>> snapshot() {
		List<ServerConfig> configs;
		try {
			configs = store.loadStrictly();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		var rows = new ArrayList<Map<String, Object>>();
		var rowIds = new HashSet<String>();
		int index = 0;
		for (ServerConfig config : configs) {
			index++;
			String rowId = config.getId() != null ? config.getId() : "#" + index;
			if (!rowIds.add(rowId)) {
				continue; // a duplicated id: the panel shows one server for it, so export one
			}
			String label = config.displayName();
			var row = new HashMap<String, Object>();
			row.put("rowId", rowId);
			row.put("label", label == null || label.isBlank() ? rowId : label);
			row.put("fields", fields(config));
			row.put("hasSecrets", false);
			rows.add(row);
		}
		return rows;
	}

	/** Net keeps no secrets, so there is never anything to read. */
	static Map<String, String> readSecrets() {
		return Map.of();
	}

	static List<String> findEmbeddedSecrets(Map<String, String> fields) {
		return hasPassword(fields.get(Host)) ? List.of(Host) : List.of();
	}

	/** Add every item under its given id in one write. Nothing is ever recovered. */
	void apply(List<Map<String, Object>> items) {
		var added = new ArrayList<ServerConfig>();
		var ids = new HashSet<String>();
		for (Map<String, Object> item : items) {
			if (!(item.get("newId") instanceof String id) || id.isBlank()) {
				throw new IllegalArgumentException("An imported server has no id");
			}
			if (!ids.add(id)) {
				throw new IllegalArgumentException("An imported server id is used twice: " + id);
			}
			Map<String, String> fields = strings(item.get("fields"));
			Map<String, String> keyFiles = strings(item.get("keyFiles"));

			var config = new ServerConfig();
			config.setId(id);
			config.setName(orEmpty(fields.get(Name)));
			config.setHost(stripPassword(orEmpty(fields.get(Host))));
			config.setPort(port(fields.get(Port)));
			config.setUsername(orEmpty(fields.get(Username)));
			String keyPath = orEmpty(keyFiles.getOrDefault(PrivateKeyPath, fields.get(PrivateKeyPath)));
			config.setPrivateKeyPath(keyPath);
			config.setAuthMethod(authMethod(fields.get(AuthMethod), keyPath));
			config.setInitialPath(orEmpty(fields.get(InitialPath)));
			added.add(config);
		}
		try {
			// The panel collapses entries sharing an id, so an import must never reuse one.
			for (ServerConfig existing : store.loadStrictly()) {
				if (existing.getId() != null && ids.contains(existing.getId())) {
					throw new IllegalArgumentException("A server with id " + existing.getId() + " already exists");
				}
			}
			store.addAll(added);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	void remove(List<String> ids) {
		try {
			store.removeAll(new HashSet<>(ids));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** A profile's fields, as strings, with any password stripped out of the host. */
	static Map<String, Object> fields(ServerConfig config) {
		var fields = new LinkedHashMap<String, Object>();
		put(fields, Name, config.getName());
		put(fields, Host, stripPassword(config.getHost()));
		fields.put(Port, String.valueOf(config.getPort()));
		put(fields, Username, config.getUsername());
		if (config.getAuthMethod() != null) {
			fields.put(AuthMethod, config.getAuthMethod().name());
		}
		put(fields, PrivateKeyPath, config.getPrivateKeyPath());
		put(fields, InitialPath, config.getInitialPath());
		return fields;
	}

	/** Whether a host carries {@code user:password@} in front of it. */
	static boolean hasPassword(String host) {
		if (host == null) {
			return false;
		}
		int at = host.lastIndexOf('@');
		return at >= 0 && host.substring(0, at).indexOf(':') >= 0;
	}

	/** The host without a {@code user:password@} prefix; unchanged when it has none. */
	static String stripPassword(String host) {
		return hasPassword(host) ? host.substring(host.lastIndexOf('@') + 1) : host;
	}

	private static int port(String value) {
		if (value == null || value.isBlank()) {
			return 22;
		}
		try {
			int port = Integer.parseInt(value.trim());
			if (port >= 1 && port <= 65535) {
				return port;
			}
		} catch (NumberFormatException e) {
			// reported below
		}
		throw new IllegalArgumentException("An imported server has an invalid port: " + value);
	}

	private static ServerConfig.AuthMethod authMethod(String value, String keyPath) {
		if (value == null || value.isBlank()) {
			return keyPath.isBlank() ? ServerConfig.AuthMethod.PASSWORD : ServerConfig.AuthMethod.KEY;
		}
		try {
			return ServerConfig.AuthMethod.valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("An imported server has an unknown sign-in method: " + value, e);
		}
	}

	private static Map<String, Object> field(String name, String kind) {
		return Map.of("name", name, "kind", kind);
	}

	private static void put(Map<String, Object> fields, String name, String value) {
		if (value != null && !value.isBlank()) {
			fields.put(name, value);
		}
	}

	private static String orEmpty(String value) {
		return value == null ? "" : value;
	}

	private static Map<String, String> strings(Object raw) {
		var map = new HashMap<String, String>();
		if (raw instanceof Map<?, ?> values) {
			values.forEach((key, value) -> {
				if (key instanceof String name && value instanceof String text) {
					map.put(name, text);
				}
			});
		}
		return map;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> items(Object raw) {
		var items = new ArrayList<Map<String, Object>>();
		if (raw instanceof List<?> list) {
			for (Object item : list) {
				if (item instanceof Map<?, ?> map) {
					items.add((Map<String, Object>) map);
				}
			}
		}
		return items;
	}

	private static List<String> ids(Object raw) {
		var ids = new ArrayList<String>();
		if (raw instanceof List<?> list) {
			for (Object id : list) {
				if (id instanceof String text) {
					ids.add(text);
				}
			}
		}
		return ids;
	}

	/** Field names, for tests. */
	static Set<String> fieldNames() {
		var names = new HashSet<String>();
		for (Object field : (List<?>) describe().get("fields")) {
			names.add((String) ((Map<?, ?>) field).get("name"));
		}
		return names;
	}
}
