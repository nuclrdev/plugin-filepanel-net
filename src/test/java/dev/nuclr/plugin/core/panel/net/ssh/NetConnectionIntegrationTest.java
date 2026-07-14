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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.scp.server.ScpCommandFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.plugin.core.panel.net.find.FindMatchMode;
import dev.nuclr.plugin.core.panel.net.find.NetFindRequest;
import dev.nuclr.plugin.core.panel.net.find.NetFindService;

/**
 * Exercises {@link NetConnection} against a real, embedded Apache MINA SSHD
 * server (password auth, SFTP subsystem, SCP command factory) rooted at a
 * temp directory — a genuine SSH round trip, not a mock, covering the parts
 * of the plugin that a pure unit test cannot: authentication, the shared
 * session, SFTP filesystem operations, SCP transfers, atomic rename, and the
 * Find service's SFTP-walk fallback.
 *
 * <p>Deliberately does not exercise remote {@code find}/{@code tail} exec
 * commands: those need a real shell on the server side, which an embedded
 * MINA server (SFTP + SCP only, no shell) does not provide.
 */
class NetConnectionIntegrationTest {

	private static final String USERNAME = "tester";
	private static final String PASSWORD = "s3cret";

	private static SshServer server;
	private static Path serverRoot;
	private static int port;

	@TempDir
	static Path tempRoot;

	@BeforeAll
	static void startServer() throws IOException {

		serverRoot = tempRoot.resolve("root");
		Files.createDirectories(serverRoot);

		server = SshServer.setUpDefaultServer();
		server.setPort(0);
		server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempRoot.resolve("hostkey.ser")));
		server.setPasswordAuthenticator((user, pass, session) -> USERNAME.equals(user) && PASSWORD.equals(pass));
		server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
		server.setCommandFactory(new ScpCommandFactory());
		server.setFileSystemFactory(new VirtualFileSystemFactory(serverRoot));
		server.start();
		port = server.getPort();
	}

	@AfterAll
	static void stopServer() throws IOException {
		if (server != null) {
			server.stop();
		}
	}

	private NetConnection connect() {
		var config = new ServerConfig();
		config.setHost("localhost");
		config.setPort(port);
		config.setUsername(USERNAME);
		config.setAuthMethod(ServerConfig.AuthMethod.PASSWORD);

		NetConnection.CredentialsProvider credentials = new NetConnection.CredentialsProvider() {
			@Override
			public String password(ServerConfig cfg, int retryIndex) {
				return PASSWORD;
			}

			@Override
			public String passphrase(ServerConfig cfg, int retryIndex) {
				return null;
			}
		};

		return new NetConnection(config, AcceptAllServerKeyVerifier.INSTANCE, credentials);
	}

	@Test
	void connectsAndAuthenticates() throws IOException {
		try (NetConnection connection = connect()) {
			connection.ensureOpen();
			assertTrue(connection.isOpen());
		}
	}

	@Test
	void mkdirAndListViaSftp() throws IOException {
		try (NetConnection connection = connect()) {
			connection.ensureOpen();

			try (var sftp = connection.sftp()) {
				sftp.mkdir("/subdir");
			}

			Path mounted = connection.path("/subdir");
			assertTrue(Files.isDirectory(mounted));
		}
	}

	@Test
	void writeAndReadFileContentRoundTrips() throws IOException {
		try (NetConnection connection = connect()) {
			connection.ensureOpen();

			Path file = connection.path("/hello.txt");
			Files.writeString(file, "hello over sftp", StandardCharsets.UTF_8);

			assertEquals("hello over sftp", Files.readString(file, StandardCharsets.UTF_8));
		}
	}

	@Test
	void statOrNullReflectsRealAttributesAndAbsence() throws IOException {
		try (NetConnection connection = connect()) {
			connection.ensureOpen();

			Path file = connection.path("/stat-me.txt");
			Files.writeString(file, "0123456789", StandardCharsets.UTF_8);

			var attrs = connection.statOrNull("/stat-me.txt");
			assertEquals(10L, attrs.getSize());
			assertFalse(attrs.isDirectory());

			assertNull(connection.statOrNull("/does-not-exist.txt"));
		}
	}

	@Test
	void atomicReplaceSwapsSiblingOverTarget() throws IOException {
		try (NetConnection connection = connect()) {
			connection.ensureOpen();

			Files.writeString(connection.path("/target.conf"), "old content", StandardCharsets.UTF_8);
			Files.writeString(connection.path("/.target.conf.tmp"), "new content", StandardCharsets.UTF_8);

			connection.atomicReplace("/.target.conf.tmp", "/target.conf");

			assertEquals("new content", Files.readString(connection.path("/target.conf"), StandardCharsets.UTF_8));
			assertNull(connection.statOrNull("/.target.conf.tmp"), "the hidden sibling must be gone after the rename");
		}
	}

	@Test
	void scpUploadThenDownloadRoundTrips() throws IOException {
		try (NetConnection connection = connect()) {
			connection.ensureOpen();

			byte[] payload = "payload via scp".getBytes(StandardCharsets.UTF_8);
			try (var in = new ByteArrayInputStream(payload)) {
				connection.scp().upload(in, "/scp-file.bin", payload.length,
						java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"), null);
			}

			var out = new ByteArrayOutputStream();
			connection.scp().download("/scp-file.bin", out);

			assertEquals("payload via scp", out.toString(StandardCharsets.UTF_8));
		}
	}

	@Test
	void findServiceRegexModeWalksNestedDirectoriesOverSftp() throws IOException {
		try (NetConnection connection = connect()) {
			connection.ensureOpen();

			Files.createDirectories(connection.path("/search/a/b"));
			Files.writeString(connection.path("/search/report-2024.log"), "x", StandardCharsets.UTF_8);
			Files.writeString(connection.path("/search/a/b/report-2025.log"), "x", StandardCharsets.UTF_8);
			Files.writeString(connection.path("/search/a/ignore.txt"), "x", StandardCharsets.UTF_8);

			// Regex mode always uses the SFTP-walk fallback (see NetFindService),
			// so this genuinely exercises that path end-to-end.
			var request = new NetFindRequest("/search", "report-\\d{4}\\.log", FindMatchMode.REGEX, true);

			var matches = new java.util.ArrayList<String>();
			NetFindService.search(connection, request, new AtomicBoolean(false), matches::add);

			assertEquals(2, matches.size());
			assertTrue(matches.contains("/search/report-2024.log"));
			assertTrue(matches.contains("/search/a/b/report-2025.log"));
		}
	}

	@Test
	void findServiceHonoursCancellation() throws IOException {
		try (NetConnection connection = connect()) {
			connection.ensureOpen();

			Files.createDirectories(connection.path("/cancel-test"));
			for (int i = 0; i < 20; i++) {
				Files.writeString(connection.path("/cancel-test/file-" + i + ".log"), "x",
						StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
						java.nio.file.StandardOpenOption.WRITE);
			}

			var cancelled = new AtomicBoolean(false);
			var seen = new AtomicInteger(0);
			var request = new NetFindRequest("/cancel-test", ".*\\.log", FindMatchMode.REGEX, true);

			NetFindService.search(connection, request, cancelled, path -> {
				if (seen.incrementAndGet() == 1) {
					cancelled.set(true);
				}
			});

			// Cancellation is checked between entries, so the walk stops early
			// rather than visiting all 20 files.
			assertTrue(seen.get() < 20);
		}
	}

	@Test
	void reconnectsAfterExplicitClose() throws IOException {
		try (NetConnection connection = connect()) {
			connection.ensureOpen();
			assertTrue(connection.isOpen());

			connection.close();
			assertFalse(connection.isOpen());

			connection.ensureOpen();
			assertTrue(connection.isOpen());

			// The reopened session is fully usable, not just "open".
			Files.writeString(connection.path("/after-reconnect.txt"), "still works", StandardCharsets.UTF_8);
			assertEquals("still works",
					Files.readString(connection.path("/after-reconnect.txt"), StandardCharsets.UTF_8));
		}
	}

	@Test
	void wrongPasswordFailsAuthenticationWithClearError() {
		var config = new ServerConfig();
		config.setHost("localhost");
		config.setPort(port);
		config.setUsername(USERNAME);
		config.setAuthMethod(ServerConfig.AuthMethod.PASSWORD);

		NetConnection.CredentialsProvider wrongPassword = new NetConnection.CredentialsProvider() {
			@Override
			public String password(ServerConfig cfg, int retryIndex) {
				return "not-the-right-password";
			}

			@Override
			public String passphrase(ServerConfig cfg, int retryIndex) {
				return null;
			}
		};

		try (NetConnection connection = new NetConnection(config, AcceptAllServerKeyVerifier.INSTANCE,
				wrongPassword)) {
			IOException failure = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
					connection::ensureOpen);
			assertTrue(failure.getMessage().contains(config.address()),
					"error should name the server: " + failure.getMessage());
		}
	}

	@Test
	void wrongPasswordIsDroppedFromCacheSoTheNextAttemptPromptsAgain() throws IOException {

		// Mirrors NetCredentialsPrompt's own caching: consult ConnectionRegistry
		// first, only "prompting" (counting a call) on a cache miss.
		var config = new ServerConfig();
		config.setHost("localhost");
		config.setPort(port);
		config.setUsername(USERNAME);
		config.setAuthMethod(ServerConfig.AuthMethod.PASSWORD);

		var promptCount = new AtomicInteger();
		NetConnection.CredentialsProvider cacheBackedCredentials = new NetConnection.CredentialsProvider() {
			@Override
			public String password(ServerConfig cfg, int retryIndex) {
				String cached = ConnectionRegistry.cachedPassword(cfg.getId());
				if (cached != null && retryIndex == 0) {
					return cached;
				}
				promptCount.incrementAndGet();
				String entered = promptCount.get() == 1 ? "not-the-right-password" : PASSWORD;
				ConnectionRegistry.cachePassword(cfg.getId(), entered);
				return entered;
			}

			@Override
			public String passphrase(ServerConfig cfg, int retryIndex) {
				return null;
			}
		};

		try (NetConnection connection = new NetConnection(config, AcceptAllServerKeyVerifier.INSTANCE,
				cacheBackedCredentials)) {

			// First attempt: wrong password, cached, then auth fails.
			org.junit.jupiter.api.Assertions.assertThrows(IOException.class, connection::ensureOpen);
			assertEquals(1, promptCount.get());

			// The bug: without dropping the cache on auth failure, this retry would
			// silently reuse "not-the-right-password" from the cache and never call
			// the provider again — reproduced here by asserting the cache is now
			// empty, and that the retry both re-prompts and succeeds.
			assertNull(ConnectionRegistry.cachedPassword(config.getId()),
					"a rejected password must not remain cached");

			connection.ensureOpen();
			assertTrue(connection.isOpen());
			assertEquals(2, promptCount.get(), "the retry must prompt again rather than reuse the cached bad value");

		} finally {
			ConnectionRegistry.dropPassword(config.getId());
		}
	}

}
