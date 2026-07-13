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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide cache of remote directory listings, keyed by server id and
 * absolute remote path. Both panels (and repeated navigation into the same
 * folder) reuse a cached listing instead of re-running SFTP {@code readDir},
 * until it is explicitly invalidated: by the user (Ctrl+R / "Refresh"), or by
 * this plugin's own mutating actions on the affected folder (make folder,
 * create file, delete, rename, copy/move into it, or a completed F4 edit
 * upload) so a panel never shows stale results after its own writes.
 *
 * <p>There is deliberately no time-based expiry: changes made outside this
 * plugin's control (another SSH client, a cron job on the server, …) are only
 * picked up on an explicit refresh, trading a little staleness for far fewer
 * round trips on routine navigation.
 */
public final class NetDirectoryCache {

	private static final Map<String, List<NetResource>> CACHE = new ConcurrentHashMap<>();

	private NetDirectoryCache() {
	}

	/**
	 * Return the cached children of a directory, or {@code null} on a cache miss.
	 *
	 * @param serverId   owning server profile id
	 * @param remotePath absolute remote directory path
	 * @return the cached listing, or {@code null}
	 */
	public static List<NetResource> get(String serverId, String remotePath) {
		return CACHE.get(key(serverId, remotePath));
	}

	/**
	 * Cache the children of a directory.
	 *
	 * @param serverId   owning server profile id
	 * @param remotePath absolute remote directory path
	 * @param children   the listing to cache (copied defensively)
	 */
	public static void put(String serverId, String remotePath, List<NetResource> children) {
		CACHE.put(key(serverId, remotePath), List.copyOf(children));
	}

	/**
	 * Drop the cached listing for one directory, if present.
	 *
	 * @param serverId   owning server profile id
	 * @param remotePath absolute remote directory path
	 */
	public static void invalidate(String serverId, String remotePath) {
		CACHE.remove(key(serverId, remotePath));
	}

	/**
	 * Drop every cached listing for a server (used when its connection closes
	 * or its profile is removed/edited).
	 *
	 * @param serverId owning server profile id
	 */
	public static void invalidateServer(String serverId) {
		String prefix = serverId + "|";
		CACHE.keySet().removeIf(k -> k.startsWith(prefix));
	}

	private static String key(String serverId, String remotePath) {
		return serverId + "|" + remotePath;
	}

}
