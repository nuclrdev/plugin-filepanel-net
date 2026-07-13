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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

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
		} catch (IOException e) {
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

}
