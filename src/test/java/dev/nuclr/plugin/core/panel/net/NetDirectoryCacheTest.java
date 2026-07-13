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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The cache is a static, process-wide map, so each test uses a fresh random
 * server id to stay isolated from the others (and from a real plugin's use of
 * the same cache during other tests in the same JVM).
 */
class NetDirectoryCacheTest {

	private static String freshServerId() {
		return "test-" + UUID.randomUUID();
	}

	@Test
	void missReturnsNull() {
		assertNull(NetDirectoryCache.get(freshServerId(), "/home"));
	}

	@Test
	void putThenGetRoundTrips() {
		String serverId = freshServerId();
		List<NetResource> children = List.of();

		NetDirectoryCache.put(serverId, "/home", children);

		assertNotNull(NetDirectoryCache.get(serverId, "/home"));
	}

	@Test
	void invalidateRemovesOnlyThatPath() {
		String serverId = freshServerId();
		NetDirectoryCache.put(serverId, "/a", List.of());
		NetDirectoryCache.put(serverId, "/b", List.of());

		NetDirectoryCache.invalidate(serverId, "/a");

		assertNull(NetDirectoryCache.get(serverId, "/a"));
		assertNotNull(NetDirectoryCache.get(serverId, "/b"));
	}

	@Test
	void invalidateServerRemovesEveryPathForThatServerOnly() {
		String serverId = freshServerId();
		String otherServerId = freshServerId();
		NetDirectoryCache.put(serverId, "/a", List.of());
		NetDirectoryCache.put(serverId, "/b", List.of());
		NetDirectoryCache.put(otherServerId, "/a", List.of());

		NetDirectoryCache.invalidateServer(serverId);

		assertNull(NetDirectoryCache.get(serverId, "/a"));
		assertNull(NetDirectoryCache.get(serverId, "/b"));
		assertNotNull(NetDirectoryCache.get(otherServerId, "/a"));
	}

	@Test
	void differentServersWithTheSamePathDoNotCollide() {
		String serverA = freshServerId();
		String serverB = freshServerId();
		NetDirectoryCache.put(serverA, "/home", List.of());

		assertNotNull(NetDirectoryCache.get(serverA, "/home"));
		assertNull(NetDirectoryCache.get(serverB, "/home"));
	}

}
