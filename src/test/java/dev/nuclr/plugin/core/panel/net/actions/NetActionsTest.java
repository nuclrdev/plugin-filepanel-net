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
package dev.nuclr.plugin.core.panel.net.actions;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.plugin.core.panel.net.ssh.NetConnection;
import dev.nuclr.plugin.core.panel.net.ssh.ServerConfig;
import dev.nuclr.plugin.core.panel.net.ssh.ServerStore;

/** The six actions end to end, against two embedded SSH servers. */
class NetActionsTest {

	private static final String USERNAME = "tester";
	private static final String PASSWORD = "s3cret";

	@TempDir
	static Path temp;

	private static SshServer alphaServer;
	private static SshServer betaServer;
	private static Path alphaRoot;
	private static Path betaRoot;
	private static NetActions actions;
	private static final Map<String, NetConnection> CONNECTIONS = new ConcurrentHashMap<>();

	@BeforeAll
	static void start() throws IOException {
		alphaRoot = Files.createDirectories(temp.resolve("alpha"));
		betaRoot = Files.createDirectories(temp.resolve("beta"));
		alphaServer = startServer(alphaRoot, "alpha");
		betaServer = startServer(betaRoot, "beta");

		var store = new ServerStore(temp.resolve("servers.json"));
		store.save(List.of(
				profile("alpha-id", "Alpha", alphaServer.getPort()),
				profile("beta-id", "Beta", betaServer.getPort()),
				profile("dup-1", "Twin", alphaServer.getPort()),
				profile("dup-2", "Twin", betaServer.getPort()),
				// Two more profiles for server Alpha itself: what a user gets by saving
				// a server twice, or once by name and once by address.
				profile("alpha-again", "Alpha Again", alphaServer.getPort()),
				profile("alpha-by-ip", "Alpha By IP", "127.0.0.1", alphaServer.getPort()),
				// Server Alpha behind a name that does not resolve here (think of a
				// public and a private address for one machine): no identity check can
				// tell it is Alpha. The opener below still connects it to Alpha.
				profile("alpha-disguised", "Alpha Disguised", "alpha.invalid", alphaServer.getPort())));

		actions = new NetActions(store, profile -> {
			ServerConfig config = profile;
			if (profile.getHost().endsWith(".invalid")) {
				config = profile("routed-" + profile.getId(), profile.getName(), "localhost", profile.getPort());
			}
			ServerConfig target = config;
			NetConnection connection = CONNECTIONS.computeIfAbsent(target.getId(),
					id -> new NetConnection(target, AcceptAllServerKeyVerifier.INSTANCE, new NetConnection.CredentialsProvider() {
						@Override
						public String password(ServerConfig cfg, int retryIndex) {
							return PASSWORD;
						}

						@Override
						public String passphrase(ServerConfig cfg, int retryIndex) {
							return null;
						}
					}));
			connection.ensureOpen();
			return connection;
		});
	}

	@AfterAll
	static void stop() throws IOException {
		CONNECTIONS.values().forEach(NetConnection::close);
		alphaServer.stop();
		betaServer.stop();
	}

	@BeforeEach
	void cleanRoots() throws IOException {
		for (Path root : List.of(alphaRoot, betaRoot)) {
			try (var children = Files.list(root)) {
				for (Path child : children.toList()) {
					deleteRecursively(child);
				}
			}
		}
	}

	// =========================================================================
	// net.servers.list and server resolution
	// =========================================================================

	@Test
	void serversListShowsProfilesButNoSecrets() {
		var call = run(NetActions.SERVERS_LIST, Map.of());
		call.assertOk();

		List<Map<String, Object>> servers = list(call.result.get("servers"));
		assertEquals(7, servers.size());
		Map<String, Object> alpha = servers.stream().filter(s -> "alpha-id".equals(s.get("id"))).findFirst().orElseThrow();
		assertEquals("Alpha", alpha.get("name"));
		assertEquals("password", alpha.get("auth"));
		assertEquals(List.of("id", "name", "host", "port", "username", "auth", "connected"),
				new ArrayList<>(alpha.keySet()), "nothing beyond the declared fields, so no secret can leak");
	}

	@Test
	void serversAreFoundByIdOrNameIgnoringCase() {
		run(NetActions.LIST, Map.of("server", "alpha-id")).assertOk();
		run(NetActions.LIST, Map.of("server", "aLPHA")).assertOk();
	}

	@Test
	void unknownAndAmbiguousServersSayWhatToDo() {
		var unknown = run(NetActions.LIST, Map.of("server", "gamma"));
		assertTrue(unknown.error.contains("No saved server 'gamma'"), unknown.error);
		assertTrue(unknown.error.contains("Alpha"), "lists the servers that do exist: " + unknown.error);

		var ambiguous = run(NetActions.LIST, Map.of("server", "twin"));
		assertTrue(ambiguous.error.contains("use the id"), ambiguous.error);
	}

	// =========================================================================
	// net.list
	// =========================================================================

	@Test
	void listReturnsSortedEntriesWithTypes() throws IOException {
		Files.writeString(alphaRoot.resolve("b.txt"), "bb");
		Files.writeString(alphaRoot.resolve("a.txt"), "a");
		Files.createDirectory(alphaRoot.resolve("logs"));

		var call = run(NetActions.LIST, Map.of("server", "Alpha", "path", "/"));
		call.assertOk();

		List<Map<String, Object>> entries = list(call.result.get("entries"));
		assertEquals(List.of("a.txt", "b.txt", "logs"), entries.stream().map(e -> e.get("name")).toList());
		assertEquals("file", entries.get(0).get("type"));
		assertEquals(1L, ((Number) entries.get(0).get("size")).longValue());
		assertEquals("directory", entries.get(2).get("type"));
		assertNotNull(entries.get(0).get("modified"));
		assertEquals(3, call.result.get("total"));
		assertEquals(false, call.result.get("truncated"));
	}

	@Test
	void listHonoursTheLimit() throws IOException {
		for (int i = 0; i < 5; i++) {
			Files.writeString(alphaRoot.resolve("f" + i), "x");
		}
		var call = run(NetActions.LIST, Map.of("server", "Alpha", "path", "/", "limit", 2));
		call.assertOk();
		assertEquals(2, list(call.result.get("entries")).size());
		assertEquals(5, call.result.get("total"));
		assertEquals(true, call.result.get("truncated"));
	}

	@Test
	void listingAFilePointsToNetRead() throws IOException {
		Files.writeString(alphaRoot.resolve("notes.txt"), "hi");
		var call = run(NetActions.LIST, Map.of("server", "Alpha", "path", "/notes.txt"));
		assertTrue(call.error.contains("net.read"), call.error);
	}

	@Test
	void permissionsAreFormattedLikeLs() {
		assertEquals("rwxr-xr-x", NetActions.permissions(0755));
		assertEquals("rw-------", NetActions.permissions(0600));
		assertEquals("---------", NetActions.permissions(0));
	}

	// =========================================================================
	// net.read
	// =========================================================================

	@Test
	void readReturnsTextAsUtf8() throws IOException {
		Files.writeString(alphaRoot.resolve("config.ini"), "name=Zoë 🚀\n", StandardCharsets.UTF_8);
		var call = run(NetActions.READ, Map.of("server", "Alpha", "path", "/config.ini"));
		call.assertOk();
		assertEquals("utf-8", call.result.get("encoding"));
		assertEquals("name=Zoë 🚀\n", call.result.get("content"));
		assertEquals(false, call.result.get("truncated"));
	}

	@Test
	void readReturnsBinaryAsBase64() throws IOException {
		byte[] binary = { 0, 1, 2, (byte) 0xFF, 0x7F };
		Files.write(alphaRoot.resolve("blob.bin"), binary);
		var call = run(NetActions.READ, Map.of("server", "Alpha", "path", "blob.bin"));
		call.assertOk();
		assertEquals("base64", call.result.get("encoding"));
		assertArrayEquals(binary, Base64.getDecoder().decode((String) call.result.get("content")));
	}

	@Test
	void truncatedReadDoesNotTurnASplitCharacterIntoBinary() throws IOException {
		Files.writeString(alphaRoot.resolve("accent.txt"), "aé and more", StandardCharsets.UTF_8);
		// 'é' is two bytes; reading two bytes cuts it in half.
		var call = run(NetActions.READ, Map.of("server", "Alpha", "path", "/accent.txt", "maxBytes", 2));
		call.assertOk();
		assertEquals("utf-8", call.result.get("encoding"));
		assertEquals("a", call.result.get("content"));
		assertEquals(true, call.result.get("truncated"));
		assertEquals(12L, ((Number) call.result.get("size")).longValue());
	}

	@Test
	void readingAFolderPointsToNetList() throws IOException {
		Files.createDirectory(alphaRoot.resolve("dir"));
		var call = run(NetActions.READ, Map.of("server", "Alpha", "path", "/dir"));
		assertTrue(call.error.contains("net.list"), call.error);
	}

	// =========================================================================
	// net.exec
	// =========================================================================

	@Test
	void execReturnsExitCodeAndOutput() {
		var call = run(NetActions.EXEC, Map.of("server", "Alpha", "command", "hello"));
		call.assertOk();
		assertEquals(0, call.result.get("exitCode"));
		assertEquals("ran: hello\n", call.result.get("stdout"));
		assertEquals("", call.result.get("stderr"));
		assertEquals(false, call.result.get("timedOut"));
		assertEquals("ran: hello\n", call.output.toString(), "output is streamed as well as returned");
	}

	@Test
	void execReportsAFailingCommandAsAResultNotAnError() {
		var call = run(NetActions.EXEC, Map.of("server", "Alpha", "command", "fail"));
		call.assertOk();
		assertEquals(3, call.result.get("exitCode"));
		assertEquals("boom\n", call.result.get("stderr"));
	}

	@Test
	void execRunsInTheGivenFolderQuoted() {
		var call = run(NetActions.EXEC, Map.of("server", "Alpha", "command", "hello", "cwd", "/my dir"));
		call.assertOk();
		assertEquals("ran: cd '/my dir' || exit\nhello\n", call.result.get("stdout"));
	}

	@Test
	void aFailedCdStopsTheWholeCommandLineNotJustItsFirstCommand() {
		// "cd x && a; b" would run b in the login folder when cd fails. The guard
		// must end the shell instead, whatever the command line contains.
		assertEquals("cd '/srv/app' || exit\necho starting; ./deploy",
				NetActions.withWorkingDirectory("/srv/app", "echo starting; ./deploy"));
	}

	@Test
	void execDoesNotStartACommandWhenAlreadyCancelled() {
		var callback = new RecordingCallback();
		callback.cancelled.set(true);
		actions.run(NetActions.EXEC, Map.of("server", "Alpha", "command", "hello"), callback);
		assertTrue(callback.error != null && callback.error.startsWith("Cancelled"), callback.error);
		assertEquals("", callback.output.toString(), "the command never ran");
	}

	@Test
	void execGivesCommandsAnEndedStdinSoTheyDoNotHang() {
		var call = run(NetActions.EXEC, Map.of("server", "Alpha", "command", "read-stdin", "timeoutSeconds", 20));
		call.assertOk();
		assertEquals("stdin ended after 0 bytes\n", call.result.get("stdout"));
		assertEquals(false, call.result.get("timedOut"));
	}

	@Test
	void execStopsACommandThatRunsTooLong() {
		long started = System.nanoTime();
		var call = run(NetActions.EXEC, Map.of("server", "Alpha", "command", "sleep", "timeoutSeconds", 1));
		call.assertOk();
		assertEquals(true, call.result.get("timedOut"));
		assertEquals(-1, call.result.get("exitCode"));
		assertTrue(System.nanoTime() - started < 10_000_000_000L, "stopped promptly, not after the sleep");
	}

	@Test
	void execCanBeCancelled() {
		var callback = new RecordingCallback();
		Thread.ofVirtual().start(() -> {
			try {
				Thread.sleep(600);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			callback.cancelled.set(true);
		});
		actions.run(NetActions.EXEC, Map.of("server", "Alpha", "command", "sleep"), callback);
		assertNotNull(callback.error);
		assertTrue(callback.error.startsWith("Cancelled"), callback.error);
		assertNull(callback.result);
	}

	@Test
	void execKeepsTheTailOfHugeOutput() {
		var call = run(NetActions.EXEC, Map.of("server", "Alpha", "command", "flood"));
		call.assertOk();
		String stdout = (String) call.result.get("stdout");
		assertTrue(stdout.startsWith("[... "), "marks the dropped beginning");
		assertTrue(stdout.endsWith("END\n"), "keeps the end, where errors and summaries are");
		assertTrue(stdout.length() < NetActions.MAX_CAPTURED_OUTPUT + 100);
		assertEquals(true, call.result.get("truncated"));
		assertEquals(FakeCommand.FLOOD_BYTES + 4, call.output.length(), "everything is still streamed");
	}

	@Test
	void execStreamsACharacterSplitAcrossWritesIntact() {
		var call = run(NetActions.EXEC, Map.of("server", "Alpha", "command", "split-utf8"));
		call.assertOk();
		assertEquals("🚀\n", call.output.toString());
		assertEquals("🚀\n", call.result.get("stdout"));
	}

	// =========================================================================
	// net.write
	// =========================================================================

	@Test
	void writeCreatesANewFileWithoutAsking() throws IOException {
		var call = run(NetActions.WRITE, Map.of("server", "Alpha", "path", "/new.txt", "content", "fresh\n"));
		call.assertOk();
		assertEquals(true, call.result.get("created"));
		assertEquals(6L, ((Number) call.result.get("bytesWritten")).longValue());
		assertEquals("fresh\n", Files.readString(alphaRoot.resolve("new.txt")));
		assertTrue(call.confirms.isEmpty());
		assertNoTempSiblings(alphaRoot);
	}

	@Test
	void overwritingAsksWithADiffFirst() throws IOException {
		Files.writeString(alphaRoot.resolve("app.conf"), "port=80\nhost=a\n");
		var callback = new RecordingCallback();
		callback.approve = true;
		actions.run(NetActions.WRITE, Map.of("server", "Alpha", "path", "/app.conf", "content", "port=8080\nhost=a\n"),
				callback);
		callback.assertOk();

		assertEquals(1, callback.confirms.size());
		String[] confirm = callback.confirms.get(0);
		assertTrue(confirm[0].contains("/app.conf"), confirm[0]);
		assertTrue(confirm[2].contains("-port=80"), confirm[2]);
		assertTrue(confirm[2].contains("+port=8080"), confirm[2]);
		assertEquals("port=8080\nhost=a\n", Files.readString(alphaRoot.resolve("app.conf")));
		assertNoTempSiblings(alphaRoot);
	}

	@Test
	void aDeclinedOverwriteLeavesTheFileAlone() throws IOException {
		Files.writeString(alphaRoot.resolve("keep.conf"), "original\n");
		var call = run(NetActions.WRITE, Map.of("server", "Alpha", "path", "/keep.conf", "content", "changed\n"));
		assertTrue(call.error.contains("not approved"), call.error);
		assertEquals("original\n", Files.readString(alphaRoot.resolve("keep.conf")));
		assertNoTempSiblings(alphaRoot);
	}

	@Test
	void aCancelledWriteWritesNothing() throws IOException {
		var callback = new RecordingCallback();
		callback.cancelled.set(true);
		actions.run(NetActions.WRITE, Map.of("server", "Alpha", "path", "/cancelled.txt", "content", "x"), callback);
		assertTrue(callback.error != null && callback.error.startsWith("Cancelled"), callback.error);
		assertFalse(Files.exists(alphaRoot.resolve("cancelled.txt")));
		assertNoTempSiblings(alphaRoot);
	}

	@Test
	void cancellingWhileTheOverwriteIsBeingApprovedKeepsTheOriginal() throws IOException {
		Files.writeString(alphaRoot.resolve("approve.conf"), "original\n");
		var callback = new RecordingCallback() {
			@Override
			public boolean confirm(String title, String details, String diff) {
				cancelled.set(true); // the user pressed Cancel on the running action...
				return true;          // ...while (or after) the approval went through
			}
		};
		actions.run(NetActions.WRITE, Map.of("server", "Alpha", "path", "/approve.conf", "content", "changed\n"),
				callback);
		assertTrue(callback.error != null && callback.error.startsWith("Cancelled"), callback.error);
		assertEquals("original\n", Files.readString(alphaRoot.resolve("approve.conf")));
		assertNoTempSiblings(alphaRoot);
	}

	@Test
	void writingIdenticalContentChangesNothingAndAsksNothing() throws IOException {
		Files.writeString(alphaRoot.resolve("same.txt"), "same\n");
		var call = run(NetActions.WRITE, Map.of("server", "Alpha", "path", "/same.txt", "content", "same\n"));
		call.assertOk();
		assertEquals(true, call.result.get("unchanged"));
		assertTrue(call.confirms.isEmpty());
	}

	@Test
	void writeNeedsCreateDirsForAMissingFolder() throws IOException {
		var refused = run(NetActions.WRITE, Map.of("server", "Alpha", "path", "/a/b/c.txt", "content", "x"));
		assertTrue(refused.error.contains("createDirs"), refused.error);

		var created = run(NetActions.WRITE,
				Map.of("server", "Alpha", "path", "/a/b/c.txt", "content", "x", "createDirs", true));
		created.assertOk();
		assertEquals("x", Files.readString(alphaRoot.resolve("a/b/c.txt")));
	}

	@Test
	void writeAcceptsBinaryAsBase64() throws IOException {
		byte[] binary = { 0, (byte) 0xFE, 42 };
		var call = run(NetActions.WRITE, Map.of("server", "Alpha", "path", "/bin.dat",
				"content", Base64.getEncoder().encodeToString(binary), "encoding", "base64"));
		call.assertOk();
		assertArrayEquals(binary, Files.readAllBytes(alphaRoot.resolve("bin.dat")));
	}

	// =========================================================================
	// net.copy
	// =========================================================================

	@Test
	void copyMovesAFileBetweenServersWithoutReturningIt() throws IOException {
		Files.writeString(alphaRoot.resolve(".env"), "SECRET=hunter2\n");
		var call = run(NetActions.COPY, Map.of("fromServer", "Alpha", "fromPath", "/.env",
				"toServer", "Beta", "toPath", "/.env"));
		call.assertOk();
		assertEquals("SECRET=hunter2\n", Files.readString(betaRoot.resolve(".env")));
		assertEquals(1, call.result.get("files"));
		assertFalse(call.result.toString().contains("hunter2"), "the content never reaches the caller");
		assertFalse(call.output.toString().contains("hunter2"));
	}

	@Test
	void copyCopiesAWholeFolder() throws IOException {
		Files.createDirectories(alphaRoot.resolve("site/css"));
		Files.writeString(alphaRoot.resolve("site/index.html"), "<h1>hi</h1>");
		Files.writeString(alphaRoot.resolve("site/css/main.css"), "body{}");
		Files.createDirectories(alphaRoot.resolve("site/empty"));

		var call = run(NetActions.COPY, Map.of("fromServer", "Alpha", "fromPath", "/site",
				"toServer", "Beta", "toPath", "/site-copy"));
		call.assertOk();
		assertEquals("<h1>hi</h1>", Files.readString(betaRoot.resolve("site-copy/index.html")));
		assertEquals("body{}", Files.readString(betaRoot.resolve("site-copy/css/main.css")));
		assertTrue(Files.isDirectory(betaRoot.resolve("site-copy/empty")));
		assertEquals(2, call.result.get("files"));
	}

	@Test
	void copyRefusesToReplaceFilesUnlessAskedAndApproved() throws IOException {
		Files.writeString(alphaRoot.resolve("data.txt"), "new");
		Files.writeString(betaRoot.resolve("data.txt"), "old");
		Map<String, Object> args = new HashMap<>(Map.of("fromServer", "Alpha", "fromPath", "/data.txt",
				"toServer", "Beta", "toPath", "/data.txt"));

		var refused = run(NetActions.COPY, args);
		assertTrue(refused.error.contains("overwrite: true"), refused.error);
		assertEquals("old", Files.readString(betaRoot.resolve("data.txt")));

		args.put("overwrite", true);
		var declined = run(NetActions.COPY, args);
		assertTrue(declined.error.contains("not approved"), declined.error);
		assertEquals("old", Files.readString(betaRoot.resolve("data.txt")));

		var callback = new RecordingCallback();
		callback.approve = true;
		actions.run(NetActions.COPY, args, callback);
		callback.assertOk();
		assertEquals("new", Files.readString(betaRoot.resolve("data.txt")));
	}

	@Test
	void copyOntoTheSameFileThroughAnotherProfileIsRefusedAndLosesNothing() throws IOException {
		Files.writeString(alphaRoot.resolve("precious.db"), "irreplaceable data");
		for (String alias : List.of("Alpha Again", "Alpha By IP")) {
			var callback = new RecordingCallback();
			callback.approve = true;
			actions.run(NetActions.COPY, Map.of("fromServer", "Alpha", "fromPath", "/precious.db",
					"toServer", alias, "toPath", "/precious.db", "overwrite", true), callback);
			assertNotNull(callback.error, alias + ": must be refused");
			assertTrue(callback.error.contains("onto itself or into itself"), alias + ": " + callback.error);
			assertEquals("irreplaceable data", Files.readString(alphaRoot.resolve("precious.db")), alias);
		}
	}

	@Test
	void evenAnUnrecognisedAliasCannotEraseTheSource() throws IOException {
		Files.writeString(alphaRoot.resolve("precious.db"), "irreplaceable data");
		var callback = new RecordingCallback();
		callback.approve = true;
		actions.run(NetActions.COPY, Map.of("fromServer", "Alpha", "fromPath", "/precious.db",
				"toServer", "Alpha Disguised", "toPath", "/precious.db", "overwrite", true), callback);
		// The identity check cannot see through this alias, so the copy goes ahead;
		// staging means the file is read in full before anything replaces it.
		callback.assertOk();
		assertEquals("irreplaceable data", Files.readString(alphaRoot.resolve("precious.db")));
		assertNoTempSiblings(alphaRoot);
	}

	@Test
	void copyIntoItselfThroughAnotherProfileIsRefused() throws IOException {
		Files.createDirectories(alphaRoot.resolve("tree"));
		Files.writeString(alphaRoot.resolve("tree/leaf.txt"), "leaf");
		var call = run(NetActions.COPY, Map.of("fromServer", "Alpha", "fromPath", "/tree",
				"toServer", "Alpha By IP", "toPath", "/tree/nested"));
		assertTrue(call.error != null && call.error.contains("onto itself or into itself"), call.error);
	}

	@Test
	void aCancelledCopyLeavesTheDestinationAsItWas() throws IOException {
		byte[] big = new byte[3 * 1024 * 1024];
		java.util.Arrays.fill(big, (byte) 'n');
		Files.write(alphaRoot.resolve("big.bin"), big);
		Files.writeString(betaRoot.resolve("big.bin"), "old version");
		var callback = new RecordingCallback() {
			@Override
			public void onProgress(long current, long total) {
				cancelled.set(true); // cancel once the first chunk has gone across
			}
		};
		callback.approve = true;
		actions.run(NetActions.COPY, Map.of("fromServer", "Alpha", "fromPath", "/big.bin",
				"toServer", "Beta", "toPath", "/big.bin", "overwrite", true), callback);
		assertTrue(callback.error != null && callback.error.startsWith("Cancelled"), callback.error);
		assertEquals("old version", Files.readString(betaRoot.resolve("big.bin")), "replaced only when complete");
		assertNoTempSiblings(betaRoot);
	}

	@Test
	void copyRefusesToCopyAFolderIntoItself() throws IOException {
		Files.createDirectories(alphaRoot.resolve("loop"));
		var call = run(NetActions.COPY, Map.of("fromServer", "Alpha", "fromPath", "/loop",
				"toServer", "Alpha", "toPath", "/loop/inner"));
		assertTrue(call.error.contains("onto itself or into itself"), call.error);
	}

	// =========================================================================
	// Helpers
	// =========================================================================

	private static RecordingCallback run(String id, Map<String, Object> args) {
		var callback = new RecordingCallback();
		actions.run(id, args, callback);
		return callback;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object value) {
		return (List<Map<String, Object>>) value;
	}

	private static void assertNoTempSiblings(Path root) throws IOException {
		try (var files = Files.list(root)) {
			assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")),
					"the hidden upload sibling must be gone");
		}
	}

	private static ServerConfig profile(String id, String name, int port) {
		return profile(id, name, "localhost", port);
	}

	private static ServerConfig profile(String id, String name, String host, int port) {
		var config = new ServerConfig();
		config.setId(id);
		config.setName(name);
		config.setHost(host);
		config.setPort(port);
		config.setUsername(USERNAME);
		config.setAuthMethod(ServerConfig.AuthMethod.PASSWORD);
		return config;
	}

	private static SshServer startServer(Path root, String name) throws IOException {
		var server = SshServer.setUpDefaultServer();
		server.setPort(0);
		server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(temp.resolve(name + "-hostkey.ser")));
		server.setPasswordAuthenticator((user, pass, session) -> USERNAME.equals(user) && PASSWORD.equals(pass));
		server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
		server.setCommandFactory((channel, command) -> new FakeCommand(command));
		server.setFileSystemFactory(new VirtualFileSystemFactory(root));
		server.start();
		return server;
	}

	private static void deleteRecursively(Path path) throws IOException {
		if (Files.isDirectory(path)) {
			try (var children = Files.list(path)) {
				for (Path child : children.toList()) {
					deleteRecursively(child);
				}
			}
		}
		Files.deleteIfExists(path);
	}

	/** Records everything an action reports; confirmations are declined unless {@link #approve}. */
	static class RecordingCallback implements NuclrPluginCallback {

		final AtomicBoolean cancelled = new AtomicBoolean();
		final StringBuffer output = new StringBuffer();
		final List<String[]> confirms = new ArrayList<>();
		volatile boolean approve;
		volatile Map<String, Object> result;
		volatile String error;
		volatile boolean completed;

		@Override
		public void onStart(String description) {
		}

		@Override
		public void onProgress(long current, long total) {
		}

		@Override
		public void onComplete() {
			completed = true;
		}

		@Override
		public void onError(String description, Exception e) {
			error = description;
		}

		@Override
		public boolean isCancelled() {
			return cancelled.get();
		}

		@Override
		public void onResult(Map<String, Object> result) {
			this.result = result;
		}

		@Override
		public void onOutput(String chunk) {
			output.append(chunk);
		}

		@Override
		public boolean confirm(String title, String details, String diff) {
			confirms.add(new String[] { title, details, diff });
			return approve;
		}

		void assertOk() {
			assertNull(error, "unexpected error: " + error);
			assertNotNull(result, "no result reported");
			assertTrue(completed, "onComplete not called");
		}
	}

	/**
	 * A stand-in for the server's shell. Echoes the command line it was given
	 * ({@code ran: ...}) unless it names one of the behaviours below.
	 */
	static final class FakeCommand implements Command, Runnable {

		static final int FLOOD_BYTES = 700 * 1024;

		private final String command;
		private InputStream in;
		private OutputStream out;
		private OutputStream err;
		private ExitCallback exit;
		private volatile boolean destroyed;

		FakeCommand(String command) {
			this.command = command;
		}

		@Override
		public void setInputStream(InputStream in) {
			this.in = in;
		}

		@Override
		public void setOutputStream(OutputStream out) {
			this.out = out;
		}

		@Override
		public void setErrorStream(OutputStream err) {
			this.err = err;
		}

		@Override
		public void setExitCallback(ExitCallback exit) {
			this.exit = exit;
		}

		@Override
		public void start(ChannelSession channel, Environment env) {
			Thread.ofVirtual().start(this);
		}

		@Override
		public void destroy(ChannelSession channel) {
			destroyed = true;
		}

		@Override
		public void run() {
			int code = 0;
			try {
				switch (command) {
					case "fail" -> {
						err.write("boom\n".getBytes(StandardCharsets.UTF_8));
						code = 3;
					}
					case "sleep" -> {
						for (int i = 0; i < 400 && !destroyed; i++) {
							Thread.sleep(50);
						}
						if (destroyed) {
							return;
						}
					}
					case "read-stdin" -> {
						long read = in.transferTo(OutputStream.nullOutputStream());
						out.write(("stdin ended after " + read + " bytes\n").getBytes(StandardCharsets.UTF_8));
					}
					case "flood" -> {
						byte[] chunk = new byte[1024];
						java.util.Arrays.fill(chunk, (byte) 'x');
						for (int i = 0; i < FLOOD_BYTES / chunk.length; i++) {
							out.write(chunk);
						}
						out.write("END\n".getBytes(StandardCharsets.UTF_8));
					}
					case "split-utf8" -> {
						byte[] rocket = "🚀\n".getBytes(StandardCharsets.UTF_8);
						out.write(rocket, 0, 2);
						out.flush();
						Thread.sleep(100);
						out.write(rocket, 2, rocket.length - 2);
					}
					default -> out.write(("ran: " + command + "\n").getBytes(StandardCharsets.UTF_8));
				}
				out.flush();
				err.flush();
			} catch (IOException | InterruptedException e) {
				code = 255;
			}
			exit.onExit(code);
		}
	}

}
