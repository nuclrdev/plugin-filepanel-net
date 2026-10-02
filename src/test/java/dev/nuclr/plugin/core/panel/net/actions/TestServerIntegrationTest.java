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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.plugin.core.panel.net.actions.NetActionsTest.RecordingCallback;
import dev.nuclr.plugin.core.panel.net.ssh.NetConnection;
import dev.nuclr.plugin.core.panel.net.ssh.ServerConfig;
import dev.nuclr.plugin.core.panel.net.ssh.ServerStore;

/**
 * The actions against a real Linux machine: the test server in test-server/
 * (Ubuntu in Docker). Runs only when NUCLR_TEST_SERVER is set:
 *
 * <pre>
 *   test-server/up.sh
 *   NUCLR_TEST_SERVER=127.0.0.1:2222 mvn test -Dtest=TestServerIntegrationTest
 * </pre>
 *
 * Works in a fresh folder per run, so the server's fixtures stay as built.
 */
@EnabledIfEnvironmentVariable(named = "NUCLR_TEST_SERVER", matches = ".+:\\d+")
class TestServerIntegrationTest {

	private static final Path KEY = Path.of("test-server", ".keys", "tester_ed25519");

	@TempDir
	static Path temp;

	private static NetActions actions;
	private static NetConnection connection;
	/** This run's scratch folder on the server, a copy of the fixture app folder. */
	private static String work;

	@BeforeAll
	static void connect() throws IOException {
		String[] hostPort = System.getenv("NUCLR_TEST_SERVER").split(":");
		var config = new ServerConfig();
		config.setId("test-server");
		config.setName("test-local");
		config.setHost(hostPort[0]);
		config.setPort(Integer.parseInt(hostPort[1]));
		config.setUsername("tester");
		config.setAuthMethod(ServerConfig.AuthMethod.KEY);
		config.setPrivateKeyPath(KEY.toAbsolutePath().toString());
		var store = new ServerStore(temp.resolve("servers.json"));
		store.save(List.of(config));

		connection = new NetConnection(config, AcceptAllServerKeyVerifier.INSTANCE, new NetConnection.CredentialsProvider() {
			@Override
			public String password(ServerConfig cfg, int retryIndex) {
				return null;
			}

			@Override
			public String passphrase(ServerConfig cfg, int retryIndex) {
				return null;
			}
		});
		actions = new NetActions(store, profile -> {
			connection.ensureOpen();
			return connection;
		});

		work = "/home/tester/it-" + UUID.randomUUID().toString().substring(0, 8);
		exec("cp -a /home/tester/app " + work).assertOk();
	}

	@AfterAll
	static void cleanUp() {
		if (work != null) {
			exec("rm -rf " + work);
		}
		if (connection != null) {
			connection.close();
		}
	}

	@Test
	void runsCommandsAsTheLoginUser() {
		var call = exec("whoami; id -un");
		call.assertOk();
		assertEquals(0, call.result.get("exitCode"));
		assertEquals("tester\ntester\n", call.result.get("stdout"));
	}

	@Test
	void aMissingWorkingFolderStopsTheWholeLineInARealShell() {
		var call = run(NetActions.EXEC, Map.of("server", "test-local", "cwd", work + "/does-not-exist",
				"command", "echo starting; touch " + work + "/should-not-exist"));
		call.assertOk();
		assertNotEquals(0, call.result.get("exitCode"));
		assertTrue(((String) call.result.get("stderr")).contains("does-not-exist"), (String) call.result.get("stderr"));
		assertEquals(1, exec("test -e " + work + "/should-not-exist").result.get("exitCode"), "nothing after the cd ran");
	}

	@Test
	void runsInTheGivenFolder() {
		var call = run(NetActions.EXEC, Map.of("server", "test-local", "cwd", work + "/my folder with spaces",
				"command", "pwd; cat readme.txt"));
		call.assertOk();
		assertEquals(work + "/my folder with spaces\nA file in a folder whose name has spaces.\n",
				call.result.get("stdout"));
	}

	@Test
	void aCommandReadingInputSeesItsEndAtOnce() {
		var call = run(NetActions.EXEC, Map.of("server", "test-local", "command", "cat; echo done", "timeoutSeconds", 20));
		call.assertOk();
		assertEquals(false, call.result.get("timedOut"));
		assertEquals("done\n", call.result.get("stdout"));
	}

	@Test
	void sudoWithoutAPasswordFailsInsteadOfHanging() {
		var call = run(NetActions.EXEC, Map.of("server", "test-local", "command", "sudo -n true", "timeoutSeconds", 20));
		call.assertOk();
		assertNotEquals(0, call.result.get("exitCode"));
		assertTrue(((String) call.result.get("stderr")).contains("password is required"), (String) call.result.get("stderr"));
	}

	@Test
	void overwritingKeepsARealFilesMode() {
		var secrets = new RecordingCallback();
		secrets.approve = true;
		actions.run(NetActions.WRITE, Map.of("server", "test-local", "path", work + "/.env", "content", "APP_ENV=it\n"),
				secrets);
		secrets.assertOk();
		var script = new RecordingCallback();
		script.approve = true;
		actions.run(NetActions.WRITE, Map.of("server", "test-local", "path", work + "/deploy.sh",
				"content", "#!/bin/sh\necho v2\n"), script);
		script.assertOk();

		assertEquals("600 755\n", exec("stat -c %a " + work + "/.env " + work + "/deploy.sh | tr '\\n' ' ' | sed 's/ $/\\n/'")
				.result.get("stdout"));
		assertEquals("v2\n", exec(work + "/deploy.sh").result.get("stdout"), "still executable");
	}

	@Test
	void aNewCopyOfAPrivateFileStaysPrivate() {
		var copy = run(NetActions.COPY, Map.of("fromServer", "test-local", "fromPath", work + "/.env",
				"toServer", "test-local", "toPath", work + "/env-copy"));
		copy.assertOk();
		assertEquals("600\n", exec("stat -c %a " + work + "/env-copy").result.get("stdout"));
	}

	@Test
	void listsSymlinksAsSymlinks() {
		var call = run(NetActions.LIST, Map.of("server", "test-local", "path", "/home/tester"));
		call.assertOk();
		@SuppressWarnings("unchecked")
		var entries = (List<Map<String, Object>>) call.result.get("entries");
		var current = entries.stream().filter(e -> "current".equals(e.get("name"))).findFirst().orElseThrow();
		assertEquals("symlink", current.get("type"));
	}

	@Test
	void writingWhereTheUserMayNotFailsWithTheServersReason() {
		var call = run(NetActions.WRITE, Map.of("server", "test-local", "path", "/srv/www/x.html", "content", "x"));
		assertNotNull(call.error);
		assertTrue(call.error.toLowerCase().contains("permission"), call.error);
	}

	private static RecordingCallback exec(String command) {
		return run(NetActions.EXEC, Map.of("server", "test-local", "command", command));
	}

	private static RecordingCallback run(String id, Map<String, Object> args) {
		var callback = new RecordingCallback();
		actions.run(id, args, callback);
		return callback;
	}

	static {
		// Fail loudly, not with a confusing auth error, when the key is missing.
		if (System.getenv("NUCLR_TEST_SERVER") != null && !Files.isRegularFile(KEY)) {
			throw new IllegalStateException("Run test-server/up.sh first: " + KEY.toAbsolutePath() + " is missing");
		}
	}
}
