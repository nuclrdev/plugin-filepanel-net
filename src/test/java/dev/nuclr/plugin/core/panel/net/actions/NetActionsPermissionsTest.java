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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.server.FileHandle;
import org.apache.sshd.sftp.server.SftpEventListener;
import org.apache.sshd.sftp.server.SftpFileSystemAccessor;
import org.apache.sshd.sftp.server.SftpSubsystemProxy;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;

import dev.nuclr.plugin.core.panel.net.actions.NetActionsTest.RecordingCallback;
import dev.nuclr.plugin.core.panel.net.ssh.NetConnection;
import dev.nuclr.plugin.core.panel.net.ssh.ServerConfig;
import dev.nuclr.plugin.core.panel.net.ssh.ServerStore;

/**
 * File modes through net.copy and net.write, against a server whose files have
 * real POSIX permissions (an in-memory Unix filesystem), so this runs the same on
 * Windows.
 */
class NetActionsPermissionsTest {

	private static final String USERNAME = "tester";
	private static final String PASSWORD = "s3cret";

	@TempDir
	static Path temp;

	private static FileSystem unix;
	private static Path root;
	private static SshServer server;
	private static NetActions actions;
	private static NetConnection connection;

	/** Mode of each staging file at the moment its first byte arrived. */
	private static final Map<String, String> MODE_AT_FIRST_WRITE = new ConcurrentHashMap<>();
	/** When set, the server rejects every attempt to change a file's mode. */
	private static volatile boolean refuseModeChanges;

	@BeforeAll
	static void start() throws IOException {
		unix = Jimfs.newFileSystem(Configuration.unix().toBuilder()
				.setAttributeViews("basic", "owner", "posix", "unix").build());
		root = Files.createDirectories(unix.getPath("/srv"));

		var sftp = new SftpSubsystemFactory();
		// Behave like an SFTP server on Linux. On a Windows host SSHD applies modes
		// through java.io.File, which an in-memory filesystem does not have, and the
		// change would be dropped without an error.
		sftp.setFileSystemAccessor(new SftpFileSystemAccessor() {
			@Override
			public void setFilePermissions(SftpSubsystemProxy subsystem, Path file, Set<PosixFilePermission> perms,
					LinkOption... options) throws IOException {
				Files.setPosixFilePermissions(file, perms);
			}
		});
		sftp.addSftpEventListener(new SftpEventListener() {
			@Override
			public void writing(ServerSession session, String remoteHandle, FileHandle localHandle, long offset,
					byte[] data, int dataOffset, int dataLen) throws IOException {
				Path file = localHandle.getFile();
				if (offset == 0 && file.getFileName().toString().contains(".nuclr-")) {
					MODE_AT_FIRST_WRITE.putIfAbsent(file.getFileName().toString(),
							PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
				}
			}

			@Override
			public void modifyingAttributes(ServerSession session, Path path, Map<String, ?> attrs)
					throws IOException {
				if (refuseModeChanges && attrs.containsKey("permissions")) {
					throw new IOException("Operation not permitted");
				}
			}
		});

		server = SshServer.setUpDefaultServer();
		server.setPort(0);
		server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(temp.resolve("hostkey.ser")));
		server.setPasswordAuthenticator((user, pass, session) -> USERNAME.equals(user) && PASSWORD.equals(pass));
		server.setSubsystemFactories(List.of(sftp));
		server.setFileSystemFactory(new VirtualFileSystemFactory(root));
		server.start();

		var config = new ServerConfig();
		config.setId("posix-id");
		config.setName("Posix");
		config.setHost("localhost");
		config.setPort(server.getPort());
		config.setUsername(USERNAME);
		config.setAuthMethod(ServerConfig.AuthMethod.PASSWORD);
		var store = new ServerStore(temp.resolve("servers.json"));
		store.save(List.of(config));

		connection = new NetConnection(config, AcceptAllServerKeyVerifier.INSTANCE, new NetConnection.CredentialsProvider() {
			@Override
			public String password(ServerConfig cfg, int retryIndex) {
				return PASSWORD;
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
	}

	@AfterAll
	static void stop() throws IOException {
		connection.close();
		server.stop();
		unix.close();
	}

	@BeforeEach
	void reset() throws IOException {
		refuseModeChanges = false;
		MODE_AT_FIRST_WRITE.clear();
		try (var children = Files.list(root)) {
			for (Path child : children.toList()) {
				Files.delete(child);
			}
		}
	}

	// =========================================================================
	// net.copy
	// =========================================================================

	@Test
	void replacingASecretsFileKeepsItPrivate() throws IOException {
		file("new.env", "SECRET=new\n", "rw-r--r--");
		file("live.env", "SECRET=old\n", "rw-------");

		copy("/new.env", "/live.env").assertOk();

		assertEquals("SECRET=new\n", Files.readString(root.resolve("live.env")));
		assertEquals("rw-------", mode("live.env"), "the destination keeps its own mode");
	}

	@Test
	void replacingAnExecutableKeepsItExecutable() throws IOException {
		file("deploy.new", "#!/bin/sh\necho v2\n", "rw-r--r--");
		file("deploy", "#!/bin/sh\necho v1\n", "rwxr-xr-x");

		copy("/deploy.new", "/deploy").assertOk();

		assertEquals("rwxr-xr-x", mode("deploy"));
	}

	@Test
	void aNewCopyTakesTheSourcesMode() throws IOException {
		file("secret.key", "key material\n", "rw-------");

		copy("/secret.key", "/secret-copy.key").assertOk();

		assertEquals("rw-------", mode("secret-copy.key"), "a private file is not copied as world-readable");
	}

	@Test
	void theContentNeverSitsUnderLooserPermissions() throws IOException {
		file("source.env", "SECRET=x\n", "rw-------");

		copy("/source.env", "/target.env").assertOk();

		assertEquals(1, MODE_AT_FIRST_WRITE.size(), "one staging file was written");
		assertEquals("rw-------", MODE_AT_FIRST_WRITE.values().iterator().next(),
				"the staging file was already private when the first byte arrived");
	}

	@Test
	void aServerThatWillNotKeepTheModeDoesNotGetTheFileReplaced() throws IOException {
		file("new.env", "SECRET=new\n", "rw-r--r--");
		file("live.env", "SECRET=old\n", "rw-------");
		refuseModeChanges = true;

		var call = copy("/new.env", "/live.env");

		assertNotNull(call.error);
		assertTrue(call.error.contains("more widely accessible"), call.error);
		assertEquals("SECRET=old\n", Files.readString(root.resolve("live.env")), "the original is untouched");
		assertEquals("rw-------", mode("live.env"));
		assertNoStagingFiles();
	}

	@Test
	void aRefusedChmodDoesNotStripAnExecutable() throws IOException {
		file("deploy.new", "#!/bin/sh\necho v2\n", "rw-r--r--");
		file("deploy", "#!/bin/sh\necho v1\n", "rwxr-xr-x");
		refuseModeChanges = true;

		// The staging file stays at the default 0644: no more access than 0755, but
		// not the same either. Replacing would quietly make the script unrunnable.
		var call = copy("/deploy.new", "/deploy");

		assertNotNull(call.error, "must not replace the executable with a non-executable copy");
		assertTrue(call.error.contains("0755") && call.error.contains("0644"), call.error);
		assertTrue(call.error.contains("no longer executable"), call.error);
		assertEquals("#!/bin/sh\necho v1\n", Files.readString(root.resolve("deploy")), "the original is untouched");
		assertEquals("rwxr-xr-x", mode("deploy"));
		assertNoStagingFiles();
	}

	@Test
	void aRefusedChmodDoesNotGiveANewCopyTheWrongMode() throws IOException {
		file("tool", "#!/bin/sh\n", "rwxr-xr-x");
		refuseModeChanges = true;

		var call = copy("/tool", "/tool-copy");

		assertNotNull(call.error);
		assertTrue(Files.notExists(root.resolve("tool-copy")), "no copy with the wrong mode is left behind");
		assertNoStagingFiles();
	}

	// =========================================================================
	// net.write
	// =========================================================================

	@Test
	void overwritingAFileWithNetWriteKeepsItsMode() throws IOException {
		file("app.env", "A=1\n", "rw-------");
		file("run.sh", "#!/bin/sh\n", "rwxr-x---");

		write("/app.env", "A=2\n").assertOk();
		write("/run.sh", "#!/bin/sh\necho hi\n").assertOk();

		assertEquals("rw-------", mode("app.env"));
		assertEquals("rwxr-x---", mode("run.sh"));
		assertEquals("A=2\n", Files.readString(root.resolve("app.env")));
	}

	@Test
	void netWriteDoesNotStripAnExecutableWhenChmodIsRefused() throws IOException {
		file("run.sh", "#!/bin/sh\necho v1\n", "rwxr-xr-x");
		refuseModeChanges = true;

		var call = write("/run.sh", "#!/bin/sh\necho v2\n");

		assertTrue(call.error != null && call.error.contains("0755"), call.error);
		assertEquals("#!/bin/sh\necho v1\n", Files.readString(root.resolve("run.sh")));
		assertEquals("rwxr-xr-x", mode("run.sh"));
		assertNoStagingFiles();
	}

	@Test
	void netWriteRefusesToLoosenAModeTheServerWillNotKeep() throws IOException {
		file("app.env", "A=1\n", "rw-------");
		refuseModeChanges = true;

		var call = write("/app.env", "A=2\n");

		assertTrue(call.error != null && call.error.contains("more widely accessible"), call.error);
		assertEquals("A=1\n", Files.readString(root.resolve("app.env")));
		assertNoStagingFiles();
	}

	// =========================================================================
	// Helpers
	// =========================================================================

	private static RecordingCallback copy(String from, String to) {
		var callback = new RecordingCallback();
		callback.approve = true;
		actions.run(NetActions.COPY, Map.of("fromServer", "Posix", "fromPath", from,
				"toServer", "Posix", "toPath", to, "overwrite", true), callback);
		return callback;
	}

	private static RecordingCallback write(String path, String content) {
		var callback = new RecordingCallback();
		callback.approve = true;
		actions.run(NetActions.WRITE, Map.of("server", "Posix", "path", path, "content", content), callback);
		return callback;
	}

	private static void file(String name, String content, String mode) throws IOException {
		Path file = root.resolve(name);
		Files.writeString(file, content, StandardCharsets.UTF_8);
		Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(mode));
	}

	private static String mode(String name) throws IOException {
		return PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve(name)));
	}

	private static void assertNoStagingFiles() throws IOException {
		try (var files = Files.list(root)) {
			assertTrue(files.noneMatch(path -> path.getFileName().toString().contains(".nuclr-")),
					"the staging file must be removed");
		}
	}

}
