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

import java.nio.file.FileSystem;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import lombok.extern.slf4j.Slf4j;

/**
 * Process-wide registry of live {@link NetConnection}s, keyed by server
 * profile id. Both file-panel sides (and every plugin instance the commander
 * creates while navigating) share the same connection to a server, so one SSH
 * session carries SFTP, SCP and exec traffic for that host.
 *
 * <p>Also holds the session-lifetime credential cache: passwords and key
 * passphrases entered in dialogs are kept in memory only and never persisted.
 */
@Slf4j
public final class ConnectionRegistry {

	private static final Map<String, NetConnection> CONNECTIONS = new ConcurrentHashMap<>();

	private static final Map<String, String> PASSWORDS = new ConcurrentHashMap<>();

	private static final Map<String, String> PASSPHRASES = new ConcurrentHashMap<>();

	private ConnectionRegistry() {
	}

	/**
	 * Return the connection for a server, creating it with {@code factory} on
	 * first use. The returned connection may still be closed; callers go
	 * through {@link NetConnection#ensureOpen()}.
	 *
	 * @param serverId the profile id
	 * @param factory  creates the connection when none is registered yet
	 * @return the shared connection
	 */
	public static NetConnection getOrCreate(String serverId, Function<String, NetConnection> factory) {
		return CONNECTIONS.computeIfAbsent(serverId, factory);
	}

	/**
	 * Return the registered connection for a server, or {@code null}.
	 *
	 * @param serverId the profile id
	 * @return the connection, or {@code null} when never opened
	 */
	public static NetConnection get(String serverId) {
		return CONNECTIONS.get(serverId);
	}

	/**
	 * Return {@code true} when the server currently has an open session.
	 *
	 * @param serverId the profile id
	 * @return {@code true} when connected
	 */
	public static boolean isConnected(String serverId) {
		NetConnection connection = CONNECTIONS.get(serverId);
		return connection != null && connection.isOpen();
	}

	/**
	 * Find the server id owning the given NIO filesystem, used by
	 * {@code supports(...)} checks on SFTP-backed resources.
	 *
	 * @param fileSystem the filesystem to look up
	 * @return the owning server id, or {@code null} when not one of ours
	 */
	public static String serverIdFor(FileSystem fileSystem) {
		if (fileSystem == null) {
			return null;
		}
		for (var entry : CONNECTIONS.entrySet()) {
			NetConnection connection = entry.getValue();
			try {
				if (connection.isOpen() && connection.fileSystem() == fileSystem) {
					return entry.getKey();
				}
			} catch (Exception e) {
				// Connection raced shut; not a match.
			}
		}
		return null;
	}

	/**
	 * Close and forget the connection for a server (used when a profile is
	 * edited, removed or explicitly disconnected). Cached credentials for the
	 * server are dropped as well.
	 *
	 * @param serverId the profile id
	 */
	public static void closeAndRemove(String serverId) {
		NetConnection connection = CONNECTIONS.remove(serverId);
		if (connection != null) {
			log.info("Closing connection to {}", connection.config().address());
			connection.close();
		}
		PASSWORDS.remove(serverId);
		PASSPHRASES.remove(serverId);
	}

	/** Close every connection (application shutdown). */
	public static void closeAll() {
		for (String id : CONNECTIONS.keySet().toArray(String[]::new)) {
			closeAndRemove(id);
		}
	}

	/**
	 * Cache a password for the session lifetime (memory only).
	 *
	 * @param serverId the profile id
	 * @param password the entered password
	 */
	public static void cachePassword(String serverId, String password) {
		if (password != null) {
			PASSWORDS.put(serverId, password);
		}
	}

	/**
	 * Return the cached password, or {@code null}.
	 *
	 * @param serverId the profile id
	 * @return the cached password
	 */
	public static String cachedPassword(String serverId) {
		return PASSWORDS.get(serverId);
	}

	/** Drop a cached password (e.g. after a failed authentication). */
	public static void dropPassword(String serverId) {
		PASSWORDS.remove(serverId);
	}

	/**
	 * Cache a key passphrase for the session lifetime (memory only).
	 *
	 * @param serverId   the profile id
	 * @param passphrase the entered passphrase
	 */
	public static void cachePassphrase(String serverId, String passphrase) {
		if (passphrase != null) {
			PASSPHRASES.put(serverId, passphrase);
		}
	}

	/**
	 * Return the cached key passphrase, or {@code null}.
	 *
	 * @param serverId the profile id
	 * @return the cached passphrase
	 */
	public static String cachedPassphrase(String serverId) {
		return PASSPHRASES.get(serverId);
	}

	/** Drop a cached passphrase (e.g. after a failed key decryption). */
	public static void dropPassphrase(String serverId) {
		PASSPHRASES.remove(serverId);
	}

}
