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
package dev.nuclr.plugin.core.panel.net.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.JOptionPane;

import org.apache.sshd.sftp.client.SftpClient;

import dev.nuclr.platform.events.NuclrEventListener;
import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.net.NetDirectoryCache;
import dev.nuclr.plugin.core.panel.net.ssh.NetConnection;
import dev.nuclr.plugin.core.panel.net.ssh.RemotePaths;
import dev.nuclr.plugin.core.panel.net.ssh.ShellEscape;
import lombok.extern.slf4j.Slf4j;

/**
 * Remote view/edit sessions for the Net panel (F3/F4).
 *
 * <p>The remote file is downloaded over SCP into a local temp copy and the
 * commander's fullscreen viewer/editor plugins (text, hex, …) are pointed at
 * that copy — so they work at local speed and never block on the network.
 * For edit sessions a watcher polls the temp copy; when the editor saves it,
 * the watcher
 * <ol>
 *   <li>re-checks the remote file's stamp (size + mtime) against the stamp
 *       recorded at download / last upload — a difference means someone else
 *       modified the file remotely, and the user is asked before overwriting;</li>
 *   <li>uploads the copy over SCP to a hidden <i>temporary sibling</i> in the
 *       target directory;</li>
 *   <li>renames the sibling over the real file via SFTP
 *       rename-with-overwrite (atomic on POSIX servers).</li>
 * </ol>
 * The session ends when the fullscreen editor closes; a final sync runs after
 * the editor's own save-on-close, then the temp copy is removed.
 */
@Slf4j
public final class NetEditService implements NuclrEventListener {

	/** What the watcher should do after comparing local and remote state. */
	enum SyncAction {
		/** Nothing changed locally: keep waiting. */
		NONE,
		/** The local copy changed and the remote file is untouched: upload. */
		UPLOAD,
		/** Both the local copy and the remote file changed: ask the user. */
		CONFLICT
	}

	/** Size + mtime pair used to detect remote modification. */
	record RemoteStamp(long size, long mtimeMillis) {

		static RemoteStamp of(SftpClient.Attributes attrs) {
			if (attrs == null) {
				return null;
			}
			long mtime = attrs.getModifyTime() != null ? attrs.getModifyTime().toMillis() : 0;
			return new RemoteStamp(attrs.getSize(), mtime);
		}
	}

	/**
	 * Decide the watcher's next step. Pure function, unit-tested directly.
	 *
	 * @param localChanged  whether the temp copy changed since the last sync
	 * @param remoteChanged whether the remote stamp differs from the last-synced stamp
	 * @return the action to take
	 */
	static SyncAction decide(boolean localChanged, boolean remoteChanged) {
		if (!localChanged) {
			return SyncAction.NONE;
		}
		return remoteChanged ? SyncAction.CONFLICT : SyncAction.UPLOAD;
	}

	/**
	 * Build the hidden temporary-sibling name used for atomic uploads.
	 *
	 * @param remotePath the target file's remote path
	 * @param token      a uniqueness token (random hex)
	 * @return the sibling's absolute remote path in the same directory
	 */
	static String uploadSiblingPath(String remotePath, String token) {
		String parent = RemotePaths.parent(remotePath);
		String name = RemotePaths.name(remotePath);
		return RemotePaths.join(parent == null ? "/" : parent, "." + name + ".nuclr-" + token + ".tmp");
	}

	private static final long POLL_MILLIS = 750;

	private static final long CLOSE_GRACE_MILLIS = 1_500;

	private static NetEditService instance;

	/**
	 * Return the process-wide edit-session manager, subscribing it to the
	 * event bus on first use (to observe fullscreen-editor close events).
	 *
	 * @param context any live plugin context
	 * @return the singleton service
	 */
	public static synchronized NetEditService instance(NuclrPluginContext context) {
		if (instance == null) {
			instance = new NetEditService();
			if (context != null && context.getEventBus() != null) {
				context.getEventBus().subscribe(instance);
			}
		}
		return instance;
	}

	private final Map<Path, EditSession> sessions = new ConcurrentHashMap<>();

	private NetEditService() {
	}

	private static final class EditSession {
		NetConnection connection;
		String remotePath;
		Path tempDir;
		Path tempFile;
		NuclrPluginContext context;
		volatile boolean closing;
		volatile boolean stopped;
		long lastLocalSize;
		long lastLocalMtime;
		RemoteStamp lastRemoteStamp;
	}

	/**
	 * F4: download the remote file to a local temp copy, open the commander's
	 * fullscreen editor chooser on it (text/hex — whichever plugins support a
	 * plain local file) and keep the copy synchronized back to the server as
	 * described in the class comment. F3 View does not go through this path:
	 * it hands the remote resource directly to {@code mainpanel.view}, since
	 * viewers only read and can stream over the mounted SFTP filesystem.
	 *
	 * @param connection the server connection
	 * @param resource   the focused remote file
	 * @param context    plugin context
	 */
	public void edit(NetConnection connection, NuclrResource resource, NuclrPluginContext context) {

		if (resource == null || resource.isFolder()) {
			return;
		}
		String remotePath = resource.getMetadata("net.path", (String) null);
		if (remotePath == null) {
			return;
		}

		var session = new EditSession();
		session.connection = connection;
		session.remotePath = remotePath;
		session.context = context;

		boolean downloaded = downloadToTemp(session, resource.getLength(), context);
		if (!downloaded) {
			cleanup(session);
			return;
		}

		try {
			recordLocalStamp(session);
			session.lastRemoteStamp = RemoteStamp.of(connection.statOrNull(remotePath));
		} catch (IOException e) {
			Alerts.showError(context, "Edit", "<html>Cannot stat <b>" + remotePath + "</b><br/>" + e.getMessage() + "</html>");
			cleanup(session);
			return;
		}

		sessions.put(session.tempFile, session);

		var editable = new LocalEditResource(session.tempFile, resource.getName(), remotePath);
		context.getEventBus().emit("mainpanel.edit", Map.of("resource", editable), null);

		Thread.ofVirtual().name("net-edit-sync").start(() -> watch(session));
	}

	private boolean downloadToTemp(EditSession session, long expectedSize, NuclrPluginContext context) {

		final boolean[] ok = { false };

		NetProgressDialog.run("Download", callback -> {
			try {
				session.tempDir = Files.createTempDirectory("nuclr-net-edit-");
				session.tempFile = session.tempDir.resolve(RemotePaths.name(session.remotePath));

				callback.onStart("Downloading " + RemotePaths.name(session.remotePath));

				try (OutputStream out = countingOutput(
						Files.newOutputStream(session.tempFile), callback, expectedSize)) {
					// See ShellEscape.isSafeForUnquotedScp: MINA's SCP client can't safely
					// handle paths with shell-significant characters, so fall back to a
					// plain SFTP read for those.
					if (ShellEscape.isSafeForUnquotedScp(session.remotePath)) {
						session.connection.scp().download(session.remotePath, out);
					} else {
						try (InputStream in = Files.newInputStream(session.connection.path(session.remotePath))) {
							in.transferTo(out);
						}
					}
				}
				ok[0] = !callback.isCancelled();

			} catch (IOException e) {
				log.warn("Download of [{}] failed: {}", session.remotePath, e.getMessage());
				if (!callback.isCancelled()) {
					Alerts.showError(context, "Download",
							"<html>Could not download <b>" + session.remotePath + "</b><br/>" + e.getMessage()
									+ "</html>");
				}
			}
		}, context);

		return ok[0];
	}

	private void watch(EditSession session) {

		long closingSince = 0;

		while (!session.stopped) {
			try {
				Thread.sleep(POLL_MILLIS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}

			if (session.closing && closingSince == 0) {
				closingSince = System.currentTimeMillis();
			}

			try {
				syncOnce(session);
			} catch (IOException e) {
				log.warn("Sync of [{}] failed: {}", session.remotePath, e.getMessage());
				Alerts.showError(session.context, "Upload",
						"<html>Could not upload <b>" + session.remotePath + "</b><br/>" + e.getMessage()
								+ "<br/><br/>Your changes are kept locally at<br/><b>" + session.tempFile
								+ "</b></html>");
				session.stopped = true;
				sessions.remove(session.tempFile);
				return; // keep the temp copy so the user's edits survive
			}

			// After the editor closed (and its save-on-close ran), one final poll
			// cycle picks up the last save; then the session ends.
			if (closingSince > 0 && System.currentTimeMillis() - closingSince >= CLOSE_GRACE_MILLIS) {
				session.stopped = true;
			}
		}

		sessions.remove(session.tempFile);
		cleanup(session);
	}

	private void syncOnce(EditSession session) throws IOException {

		boolean localChanged = localChanged(session);
		if (!localChanged) {
			return;
		}

		RemoteStamp current = RemoteStamp.of(session.connection.statOrNull(session.remotePath));
		boolean remoteChanged = current != null
				? !current.equals(session.lastRemoteStamp)
				: false; // remote file vanished: recreate it silently

		SyncAction action = decide(true, remoteChanged);

		if (action == SyncAction.CONFLICT && !confirmOverwriteRemote(session)) {
			// User declined: stop syncing but keep watching for nothing further;
			// end the session and keep the local copy for manual recovery.
			session.stopped = true;
			Alerts.showInfo(session.context, "Upload skipped",
					"<html>The remote file was left untouched. Your edited copy is at<br/><b>"
							+ session.tempFile + "</b></html>");
			sessions.remove(session.tempFile);
			return;
		}

		upload(session);
	}

	private boolean localChanged(EditSession session) {
		try {
			BasicFileAttributes attrs = Files.readAttributes(session.tempFile, BasicFileAttributes.class);
			return attrs.size() != session.lastLocalSize
					|| attrs.lastModifiedTime().toMillis() != session.lastLocalMtime;
		} catch (IOException e) {
			return false;
		}
	}

	private void recordLocalStamp(EditSession session) throws IOException {
		BasicFileAttributes attrs = Files.readAttributes(session.tempFile, BasicFileAttributes.class);
		session.lastLocalSize = attrs.size();
		session.lastLocalMtime = attrs.lastModifiedTime().toMillis();
	}

	private boolean confirmOverwriteRemote(EditSession session) {
		final boolean[] overwrite = { false };
		Alerts.runOnEdtAndWait(() -> overwrite[0] = JOptionPane.showConfirmDialog(
				null,
				"<html>The remote file changed while you were editing:<br/><b>" + session.remotePath
						+ "</b><br/><br/>Overwrite the remote changes with your copy?</html>",
				"Remote file modified",
				JOptionPane.YES_NO_OPTION,
				JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION);
		return overwrite[0];
	}

	/** Upload the temp copy to a hidden sibling, then atomically rename it over the target. */
	private void upload(EditSession session) throws IOException {

		String sibling = uploadSiblingPath(session.remotePath, UUID.randomUUID().toString().substring(0, 8));
		long size = Files.size(session.tempFile);

		log.info("Uploading {} -> {} (via {})", session.tempFile, session.remotePath, sibling);

		try {
			// See ShellEscape.isSafeForUnquotedScp: MINA's SCP client can't safely
			// handle paths with shell-significant characters, so fall back to a
			// plain SFTP write for those.
			if (ShellEscape.isSafeForUnquotedScp(sibling)) {
				try (InputStream in = Files.newInputStream(session.tempFile)) {
					session.connection.scp().upload(in, sibling, size,
							java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"), null);
				}
			} else {
				try (InputStream in = Files.newInputStream(session.tempFile);
						OutputStream out = Files.newOutputStream(session.connection.path(sibling),
								java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE,
								java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
					in.transferTo(out);
				}
			}
			session.connection.atomicReplace(sibling, session.remotePath);
		} catch (IOException e) {
			// Best effort: never leave the hidden sibling behind.
			try (SftpClient sftp = session.connection.sftp()) {
				sftp.remove(sibling);
			} catch (IOException ignored) {
				// sibling may not exist
			}
			throw e;
		}

		recordLocalStamp(session);
		session.lastRemoteStamp = RemoteStamp.of(session.connection.statOrNull(session.remotePath));

		String parent = RemotePaths.parent(session.remotePath);
		NetDirectoryCache.invalidate(session.connection.serverId(), parent == null ? "/" : parent);
	}

	private void cleanup(EditSession session) {
		try {
			if (session.tempFile != null) {
				Files.deleteIfExists(session.tempFile);
			}
			if (session.tempDir != null) {
				Files.deleteIfExists(session.tempDir);
			}
		} catch (IOException e) {
			log.debug("Could not remove temp copy [{}]: {}", session.tempFile, e.getMessage());
		}
	}

	@Override
	public void handleMessage(Object source, String type, Map<String, Object> eventData,
			NuclrPluginCallback callback) {
		if ("plugin.fullscreen.close".equals(type)) {
			// Editor is closing; its save-on-close may still write the temp copy.
			// Mark sessions so the watcher runs one final grace-period sync.
			for (EditSession session : sessions.values()) {
				session.closing = true;
			}
		}
	}

	@Override
	public boolean isMessageSupported(String type) {
		return "plugin.fullscreen.close".equals(type);
	}

	private static OutputStream countingOutput(OutputStream out, NuclrPluginCallback callback, long total) {
		return new OutputStream() {
			private long written;

			@Override
			public void write(int b) throws IOException {
				write(new byte[] { (byte) b }, 0, 1);
			}

			@Override
			public void write(byte[] buffer, int offset, int length) throws IOException {
				if (callback.isCancelled()) {
					throw new IOException("Download cancelled");
				}
				out.write(buffer, offset, length);
				written += length;
				callback.onProgress(written, total);
			}

			@Override
			public void close() throws IOException {
				out.close();
			}
		};
	}

	/**
	 * The resource handed to the fullscreen editor/viewer plugins: a plain
	 * local file (the downloaded temp copy) that additionally remembers where
	 * it came from.
	 */
	public static final class LocalEditResource extends NuclrResource {

		private static final long serialVersionUID = 1L;

		LocalEditResource(Path tempFile, String displayName, String remotePath) {
			super(tempFile);
			this.name = displayName;
			this.setUuid("net:edit:" + remotePath + ":" + tempFile);
			this.setFullPath(tempFile.toString());
			this.setFolder(false);
			this.setReadable(true);
			try {
				this.setLength(Files.size(tempFile));
			} catch (IOException e) {
				this.setLength(0);
			}
			this.getMetadata().put("net.remote.path", remotePath);
		}

		@Override
		public InputStream openInputStream(OpenOption... options) throws Exception {
			return Files.newInputStream(getPath(), options);
		}
	}

}
