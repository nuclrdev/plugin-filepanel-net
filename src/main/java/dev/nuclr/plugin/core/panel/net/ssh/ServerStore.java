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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import tools.jackson.core.type.TypeReference;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * JSON persistence for {@link ServerConfig} profiles. The default store lives
 * at {@code ~/.nuclr/net/servers.json}; a custom file can be supplied for
 * tests. Loading is defensive: a corrupt file yields an empty list rather than
 * an error, duplicate ids are collapsed and missing ids regenerated.
 */
@Slf4j
public class ServerStore {

	private final ObjectMapper mapper = new ObjectMapper();

	private final Path file;

	/**
	 * Create a store over the given JSON file.
	 *
	 * @param file the backing file; created on first save
	 */
	public ServerStore(Path file) {
		this.file = file;
	}

	/**
	 * Create the production store at {@code ~/.nuclr/net/servers.json}.
	 *
	 * @return the default store
	 */
	public static ServerStore defaultStore() {
		return new ServerStore(Path.of(System.getProperty("user.home"), ".nuclr", "net", "servers.json"));
	}

	/**
	 * Return the backing file location (shown to the user in the panel UI).
	 *
	 * @return the JSON file path
	 */
	public Path file() {
		return file;
	}

	/**
	 * Load all profiles, sorted by display name. Missing or unreadable files
	 * yield an empty list.
	 *
	 * @return the saved profiles, never {@code null}
	 */
	public synchronized List<ServerConfig> load() {

		if (!Files.isRegularFile(file)) {
			return List.of();
		}

		try {
			List<ServerConfig> raw = mapper.readValue(file.toFile(), new TypeReference<List<ServerConfig>>() {});
			var byId = new LinkedHashMap<String, ServerConfig>();
			for (ServerConfig config : raw) {
				if (config == null) {
					continue;
				}
				if (config.getId() == null || config.getId().isBlank()) {
					config.setId(UUID.randomUUID().toString());
				}
				byId.put(config.getId(), config);
			}
			var configs = new ArrayList<>(byId.values());
			configs.sort(Comparator.comparing(ServerConfig::displayName, String.CASE_INSENSITIVE_ORDER));
			return configs;
		} catch (JacksonException e) {
			log.warn("Cannot read Net server list [{}]: {}", file, e.getMessage());
			return List.of();
		}
	}

	/**
	 * Persist the given profiles, replacing the previous contents.
	 *
	 * @param configs the profiles to save
	 * @throws IOException if the file cannot be written
	 */
	public synchronized void save(List<ServerConfig> configs) throws IOException {
		Path parent = file.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), configs);
	}

	/**
	 * Insert or replace one profile (matched by id) and persist the list.
	 *
	 * @param config the profile to save
	 * @throws IOException if the file cannot be written
	 */
	public synchronized void upsert(ServerConfig config) throws IOException {
		var configs = new ArrayList<>(load());
		configs.removeIf(existing -> Objects.equals(existing.getId(), config.getId()));
		configs.add(config);
		save(configs);
	}

	/**
	 * Remove the profile with the given id (a no-op when absent) and persist.
	 *
	 * @param id the profile id to remove
	 * @throws IOException if the file cannot be written
	 */
	public synchronized void remove(String id) throws IOException {
		var configs = new ArrayList<>(load());
		configs.removeIf(existing -> Objects.equals(existing.getId(), id));
		save(configs);
	}

	/**
	 * Find a profile by id.
	 *
	 * @param id the profile id
	 * @return the profile, or {@code null} when not found
	 */
	public synchronized ServerConfig byId(String id) {
		return load().stream().filter(config -> Objects.equals(config.getId(), id)).findFirst().orElse(null);
	}

	// =========================================================================
	// Strict access for settings transfer (Configs > Export / Import)
	// =========================================================================

	/**
	 * Load every entry exactly as stored, in file order: nothing is sorted or
	 * collapsed, and an entry saved without an id keeps a {@code null} id
	 * instead of a fresh random one. Unlike {@link #load()} an unreadable file
	 * is an error, never an empty list.
	 *
	 * @return the stored entries; empty when the file does not exist
	 * @throws IOException if the file exists but cannot be read or parsed
	 */
	public synchronized List<ServerConfig> loadStrictly() throws IOException {
		var configs = new ArrayList<ServerConfig>();
		for (Map<String, Object> raw : readRaw()) {
			ServerConfig config;
			try {
				config = mapper.convertValue(raw, ServerConfig.class);
			} catch (JacksonException e) {
				throw new IOException("The saved Net servers cannot be read: " + e.getMessage(), e);
			}
			if (!(raw.get("id") instanceof String id) || id.isBlank()) {
				config.setId(null);
			}
			configs.add(config);
		}
		return configs;
	}

	/**
	 * Append the given profiles to the stored ones and write the combined list
	 * once, atomically. Existing entries are kept exactly as stored. When this
	 * throws, the file is unchanged.
	 *
	 * @param configs the profiles to add, each with its id already set
	 * @throws IOException if the stored file exists but cannot be read, or the write fails
	 */
	public synchronized void addAll(List<ServerConfig> configs) throws IOException {
		List<Map<String, Object>> all = readRaw();
		for (ServerConfig config : configs) {
			all.add(mapper.convertValue(config, new TypeReference<Map<String, Object>>() {}));
		}
		writeAtomically(all);
	}

	/**
	 * Remove the entries with these ids (unknown ids are ignored) in one atomic
	 * write. Throws like {@link #addAll}; when it throws, the file is unchanged.
	 *
	 * @param ids the ids to remove
	 * @throws IOException if the stored file exists but cannot be read, or the write fails
	 */
	public synchronized void removeAll(Collection<String> ids) throws IOException {
		if (!Files.exists(file)) {
			return;
		}
		List<Map<String, Object>> all = readRaw();
		all.removeIf(raw -> raw.get("id") instanceof String id && ids.contains(id));
		writeAtomically(all);
	}

	/** The stored entries as raw maps, so a rewrite keeps whatever this build does not model. */
	private List<Map<String, Object>> readRaw() throws IOException {
		if (!Files.exists(file)) {
			return new ArrayList<>();
		}
		if (!Files.isRegularFile(file)) {
			throw new IOException("The saved Net servers are not a regular file: " + file);
		}
		List<Map<String, Object>> raw;
		try {
			raw = mapper.readValue(file.toFile(), new TypeReference<List<Map<String, Object>>>() {});
		} catch (JacksonException e) {
			throw new IOException("The saved Net servers cannot be read; not changing them: " + e.getMessage(), e);
		}
		var entries = new ArrayList<Map<String, Object>>();
		if (raw != null) {
			for (Map<String, Object> entry : raw) {
				if (entry != null) {
					entries.add(entry);
				}
			}
		}
		return entries;
	}

	/** Write to a temporary file next to the store, then move it over the store in one step. */
	private void writeAtomically(List<Map<String, Object>> entries) throws IOException {
		Path parent = file.toAbsolutePath().getParent();
		Files.createDirectories(parent);
		Path temp = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
		try {
			try {
				mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), entries);
			} catch (JacksonException e) {
				throw new IOException("Cannot write the Net servers: " + e.getMessage(), e);
			}
			replaceFile(temp, file);
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	/**
	 * Move the freshly written {@code temp} over {@code target}. A separate step
	 * so a test can make the final write fail.
	 *
	 * @param temp the complete new contents
	 * @param target the store file
	 * @throws IOException if the move fails
	 */
	protected void replaceFile(Path temp, Path target) throws IOException {
		try {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

}
