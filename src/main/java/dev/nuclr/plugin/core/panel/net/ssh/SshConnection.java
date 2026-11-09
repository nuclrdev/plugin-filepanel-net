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
import java.io.InputStream;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.session.SessionHeartbeatController;
import org.apache.sshd.scp.client.ScpClient;
import org.apache.sshd.scp.client.ScpClientCreator;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.fs.SftpFileSystem;
import org.apache.sshd.sftp.client.fs.SftpFileSystemProvider;

import dev.nuclr.plugin.core.panel.net.remote.NetConnection;
import dev.nuclr.plugin.core.panel.net.remote.RemoteAttributes;
import dev.nuclr.plugin.core.panel.net.remote.RemoteEntry;
import dev.nuclr.plugin.core.panel.net.remote.RemoteListing;
import lombok.extern.slf4j.Slf4j;

/**
 * A {@link NetConnection} over SSH. A single {@link ClientSession} carries all
 * traffic: the mounted {@link SftpFileSystem} (directory listings and
 * filesystem operations), {@link ScpClient} transfers and {@code exec}
 * channels ({@code find}, {@code tail}, …) offered through {@link ShellAccess}.
 *
 * <p>The connection opens lazily and reconnects transparently: every consumer
 * goes through {@link #ensureOpen()}, which rebuilds a dead session from the
 * cached credentials. Thread-safe; shared by both panel sides through
 * {@code ConnectionRegistry}.
 */
@Slf4j
public final class SshConnection implements NetConnection, ShellAccess {

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration AUTH_TIMEOUT = Duration.ofSeconds(15);
	private static final Duration HEARTBEAT = Duration.ofSeconds(30);

	private final ServerConfig config;
	private final ServerKeyVerifier keyVerifier;
	private final CredentialsProvider credentials;

	private SshClient client;
	private ClientSession session;
	private SftpFileSystem fileSystem;
	private String home;

	/**
	 * Create a (not yet opened) connection for the given profile.
	 *
	 * @param config      the server profile
	 * @param keyVerifier host-key verifier (see {@link HostKeyGate})
	 * @param credentials secret supplier used on every (re)open
	 */
	public SshConnection(ServerConfig config, ServerKeyVerifier keyVerifier, CredentialsProvider credentials) {
		this.config = config;
		this.keyVerifier = keyVerifier;
		this.credentials = credentials;
	}

	@Override
	public ServerConfig config() {
		return config;
	}

	/**
	 * Return whether the session and the SFTP mount are currently open.
	 *
	 * @return {@code true} when usable without reconnecting
	 */
	@Override
	public synchronized boolean isOpen() {
		return session != null && session.isOpen() && fileSystem != null && fileSystem.isOpen();
	}

	@Override
	public synchronized void ensureOpen() throws IOException {
		if (isOpen()) {
			return;
		}
		closeQuietly();
		open();
	}

	private void open() throws IOException {

		log.info("Connecting to {} ...", config.address());

		client = SshClient.setUpDefaultClient();
		client.setServerKeyVerifier(keyVerifier);
		client.setSessionHeartbeat(SessionHeartbeatController.HeartbeatType.IGNORE, HEARTBEAT);
		client.start();

		try {
			session = client.connect(config.getUsername(), config.getHost(), config.getPort())
					.verify(CONNECT_TIMEOUT)
					.getSession();

			authenticate();

			fileSystem = new SftpFileSystemProvider(client).newFileSystem(session);
			home = resolveHome();

			log.info("Connected to {} (home: {})", config.address(), home);

		} catch (IOException e) {
			closeQuietly();
			throw translate(e);
		}
	}

	private void authenticate() throws IOException {

		if (config.usesKey()) {
			FilePasswordProvider passphraseProvider = (sessionContext, resourceKey, retryIndex) -> {
				String passphrase = credentials.passphrase(config, retryIndex);
				if (passphrase == null) {
					throw new IOException("Key passphrase entry cancelled");
				}
				return passphrase;
			};
			List<KeyPair> identities;
			try {
				identities = KeyLoader.load(Path.of(config.getPrivateKeyPath()), passphraseProvider);
			} catch (GeneralSecurityException e) {
				// A wrong passphrase surfaces here as a decryption failure. Drop the
				// cached value so the next connection attempt prompts again instead of
				// silently retrying with the same bad passphrase forever.
				ConnectionRegistry.dropPassphrase(config.getId());
				throw new IOException("Cannot load private key " + config.getPrivateKeyPath() + ": " + e.getMessage(), e);
			}
			identities.forEach(session::addPublicKeyIdentity);
		} else {
			String password = credentials.password(config, 0);
			if (password == null) {
				throw new IOException("Password entry cancelled");
			}
			session.addPasswordIdentity(password);
		}

		try {
			session.auth().verify(AUTH_TIMEOUT);
		} catch (IOException authFailure) {
			// The server rejected the credentials we just supplied (wrong password,
			// or a key it doesn't accept): drop whichever secret we cached above so
			// the next connection attempt prompts fresh rather than silently
			// reusing the same rejected value.
			ConnectionRegistry.dropPassword(config.getId());
			ConnectionRegistry.dropPassphrase(config.getId());
			throw authFailure;
		}
	}

	private String resolveHome() {
		try (SftpClient sftp = fileSystem.getClient()) {
			String canonical = sftp.canonicalPath(".");
			if (canonical != null && !canonical.isBlank()) {
				return RemotePaths.normalize(canonical);
			}
		} catch (IOException e) {
			log.debug("Cannot resolve remote home for {}: {}", config.address(), e.getMessage());
		}
		return "/";
	}

	@Override
	public synchronized String home() {
		return home != null ? home : "/";
	}

	/**
	 * Return the mounted SFTP NIO filesystem. Paths from this filesystem flow
	 * through the whole commander (listings, copy engines, editors, viewers).
	 *
	 * @return the SFTP filesystem
	 * @throws IOException if the connection cannot be (re)opened
	 */
	@Override
	public synchronized SftpFileSystem fileSystem() throws IOException {
		ensureOpen();
		return fileSystem;
	}

	@Override
	public Path path(String remotePath) throws IOException {
		return fileSystem().getPath(RemotePaths.normalize(remotePath));
	}

	/**
	 * Borrow a pooled SFTP client channel; close it to return it to the pool.
	 *
	 * @return a pooled {@link SftpClient}
	 * @throws IOException if the connection cannot be (re)opened
	 */
	public SftpClient sftp() throws IOException {
		return fileSystem().getClient();
	}

	@Override
	public RemoteListing list(String remotePath) throws IOException {
		SftpClient sftp = sftp();
		Iterable<SftpClient.DirEntry> entries;
		try {
			entries = sftp.readDir(remotePath);
		} catch (IOException | RuntimeException e) {
			sftp.close();
			throw e;
		}
		return new RemoteListing() {

			@Override
			public Iterator<RemoteEntry> iterator() {
				return new SkipDotsIterator(entries.iterator());
			}

			@Override
			public void close() throws IOException {
				sftp.close();
			}
		};
	}

	/** Maps SFTP directory entries to {@link RemoteEntry}, dropping {@code .} and {@code ..}. */
	private static final class SkipDotsIterator implements Iterator<RemoteEntry> {

		private final Iterator<SftpClient.DirEntry> raw;
		private RemoteEntry next;

		SkipDotsIterator(Iterator<SftpClient.DirEntry> raw) {
			this.raw = raw;
		}

		@Override
		public boolean hasNext() {
			while (next == null && raw.hasNext()) {
				SftpClient.DirEntry entry = raw.next();
				String name = entry.getFilename();
				if (!".".equals(name) && !"..".equals(name)) {
					next = new RemoteEntry(name, attributes(entry.getAttributes()));
				}
			}
			return next != null;
		}

		@Override
		public RemoteEntry next() {
			if (!hasNext()) {
				throw new NoSuchElementException();
			}
			RemoteEntry result = next;
			next = null;
			return result;
		}
	}

	/**
	 * Convert SFTP attributes to the protocol-neutral form.
	 *
	 * @param attrs the SFTP attributes, may be {@code null}
	 * @return the converted attributes, or {@code null} when {@code attrs} is
	 */
	static RemoteAttributes attributes(SftpClient.Attributes attrs) {
		if (attrs == null) {
			return null;
		}
		return new RemoteAttributes(
				attrs.isDirectory(),
				attrs.isRegularFile(),
				attrs.isSymbolicLink(),
				attrs.getSize(),
				attrs.getModifyTime(),
				attrs.getAccessTime(),
				attrs.getCreateTime(),
				attrs.getPermissions());
	}

	@Override
	public RemoteAttributes statOrNull(String remotePath) throws IOException {
		try (SftpClient sftp = sftp()) {
			return attributes(sftp.stat(remotePath));
		} catch (IOException e) {
			if (isNoSuchFile(e)) {
				return null;
			}
			throw e;
		}
	}

	@Override
	public String canonicalPath(String remotePath) throws IOException {
		try (SftpClient sftp = sftp()) {
			String real = sftp.canonicalPath(remotePath);
			return real == null || real.isBlank() ? remotePath : RemotePaths.normalize(real);
		}
	}

	@Override
	public InputStream read(String remotePath) throws IOException {
		SftpClient sftp = sftp();
		try {
			InputStream in = sftp.read(remotePath);
			return new java.io.FilterInputStream(in) {
				@Override
				public void close() throws IOException {
					try {
						super.close();
					} finally {
						sftp.close();
					}
				}
			};
		} catch (IOException | RuntimeException e) {
			sftp.close();
			throw e;
		}
	}

	@Override
	public void delete(String remotePath) throws IOException {
		try (SftpClient sftp = sftp()) {
			sftp.remove(remotePath);
		}
	}

	@Override
	public void setPermissions(String remotePath, int permissions) throws IOException {
		try (SftpClient sftp = sftp()) {
			sftp.setStat(remotePath, new SftpClient.Attributes().perms(permissions));
		}
	}

	@Override
	public synchronized ScpClient scp() throws IOException {
		ensureOpen();
		return ScpClientCreator.instance().createScpClient(session);
	}

	@Override
	public synchronized ChannelExec execChannel(String command) throws IOException {
		ensureOpen();
		return session.createExecChannel(command);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>The channel rides on the same {@link ClientSession} as the SFTP mount,
	 * so it costs no extra connection and no second authentication.
	 */
	@Override
	public synchronized ChannelShell shellChannel() throws IOException {
		ensureOpen();
		return session.createShellChannel();
	}

	@Override
	public synchronized String exec(String command) throws IOException {
		ensureOpen();
		return session.executeRemoteCommand(command);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Uses SFTP rename-with-overwrite (POSIX rename where the server supports
	 * it). Falls back to a delete-then-rename when the server rejects the
	 * overwriting rename; the fallback is not atomic and is logged.
	 */
	@Override
	public void atomicReplace(String sourcePath, String targetPath) throws IOException {
		try (SftpClient sftp = sftp()) {
			try {
				sftp.rename(sourcePath, targetPath, SftpClient.CopyMode.Overwrite);
			} catch (IOException | UnsupportedOperationException overwriteRejected) {
				log.info("Overwriting rename not supported by {} ({}); falling back to delete+rename",
						config.address(), overwriteRejected.getMessage());
				try {
					sftp.remove(targetPath);
				} catch (IOException e) {
					if (!isNoSuchFile(e)) {
						throw e;
					}
				}
				sftp.rename(sourcePath, targetPath);
			}
		}
	}

	private static boolean isNoSuchFile(IOException e) {
		if (e instanceof java.nio.file.NoSuchFileException) {
			return true;
		}
		String message = e.getMessage();
		return message != null && (message.contains("No such file") || message.contains("SSH_FX_NO_SUCH_FILE"));
	}

	private IOException translate(IOException e) {
		String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
		if (message.contains("verification failed") || message.contains("Key exchange")) {
			return new IOException("Host key verification failed for " + config.address()
					+ ". The server key was not accepted.", e);
		}
		if (message.contains("No more authentication methods") || message.contains("Authentication failed")
				|| message.contains("auth")) {
			return new IOException("Authentication failed for " + config.address()
					+ ". Check the username, password or key.", e);
		}
		if (message.contains("timeout") || message.contains("Timeout")) {
			return new IOException("Connection to " + config.address() + " timed out.", e);
		}
		return new IOException("Cannot connect to " + config.address() + ": " + message, e);
	}

	/**
	 * Close the session and all channels. The connection can be reopened later
	 * via {@link #ensureOpen()}.
	 */
	@Override
	public synchronized void close() {
		closeQuietly();
	}

	private void closeQuietly() {
		if (fileSystem != null) {
			try {
				fileSystem.close();
			} catch (IOException e) {
				log.debug("Error closing SFTP filesystem for {}: {}", config.address(), e.getMessage());
			}
			fileSystem = null;
		}
		if (session != null) {
			try {
				session.close(true);
			} catch (RuntimeException e) {
				log.debug("Error closing session for {}: {}", config.address(), e.getMessage());
			}
			session = null;
		}
		if (client != null) {
			try {
				client.stop();
			} catch (RuntimeException e) {
				log.debug("Error stopping SSH client for {}: {}", config.address(), e.getMessage());
			}
			client = null;
		}
	}

}
