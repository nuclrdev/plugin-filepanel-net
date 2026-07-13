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
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.sshd.scp.common.helpers.ScpTimestampCommandDetails;

import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.plugin.core.panel.net.ssh.NetConnection;
import lombok.extern.slf4j.Slf4j;

/**
 * UI-agnostic engine that copies files and folders <b>into a remote SFTP
 * directory</b>. Local sources travel over SCP on the destination's shared SSH
 * session; sources already on a virtual filesystem (another SFTP mount, an
 * archive mount) are streamed. Runs synchronously on the caller's background
 * thread, reports byte progress and honours cancellation through the
 * {@link NuclrPluginCallback}, and delegates user decisions to the functional
 * interfaces so it stays Swing-free and unit-testable.
 *
 * <p>Directory copies merge into an existing target directory; symbolic links
 * are followed (their content is copied).
 */
@Slf4j
public final class NetTransferEngine {

	/** How an existing-target clash is resolved. */
	public enum Action {
		OVERWRITE, SKIP, CANCEL
	}

	/** Asked to resolve a clash when the target already exists. */
	@FunctionalInterface
	public interface ConflictResolver {
		/**
		 * Decide what to do about an existing target.
		 *
		 * @param source the source entry
		 * @param target the clashing target
		 * @return the chosen action; {@code null} aborts the operation
		 */
		Action resolve(Path source, Path target);
	}

	/** Per-item failure prompt. Return {@code true} to skip and continue, {@code false} to abort. */
	@FunctionalInterface
	public interface ErrorPrompt {
		boolean onError(Path source, Exception e);
	}

	private static final int BUFFER = 64 * 1024;

	private static final Set<PosixFilePermission> DEFAULT_FILE_PERMISSIONS =
			PosixFilePermissions.fromString("rw-r--r--");

	private final NetConnection connection;
	private final NuclrPluginCallback cb;
	private final ConflictResolver resolver;
	private final ErrorPrompt errorPrompt;

	private long totalBytes;
	private long transferredBytes;
	private boolean aborted;

	/**
	 * Create an engine writing into directories of the given connection's
	 * filesystem.
	 *
	 * @param connection  the destination server's connection (SCP transport)
	 * @param cb          progress/cancellation bridge
	 * @param resolver    conflict decision callback
	 * @param errorPrompt per-item error decision callback
	 */
	public NetTransferEngine(NetConnection connection, NuclrPluginCallback cb, ConflictResolver resolver,
			ErrorPrompt errorPrompt) {
		this.connection = connection;
		this.cb = cb;
		this.resolver = resolver;
		this.errorPrompt = errorPrompt;
	}

	/**
	 * Copy each source into {@code destinationDir}.
	 *
	 * @param sources        the entries to copy (any NIO provider)
	 * @param destinationDir the remote target directory (SFTP path)
	 * @return {@code true} if the run completed (possibly with skips),
	 *         {@code false} when cancelled or aborted
	 */
	public boolean copy(List<Path> sources, Path destinationDir) {

		this.totalBytes = scanTotalBytes(sources);
		this.transferredBytes = 0;
		this.aborted = false;

		for (Path source : sources) {
			if (isCancelled()) {
				return false;
			}
			copyEntry(source, destinationDir.resolve(fileName(source)));
			if (aborted || isCancelled()) {
				return false;
			}
		}
		return true;
	}

	private void copyEntry(Path source, Path target) {

		if (isCancelled() || aborted) {
			return;
		}

		BasicFileAttributes attrs;
		try {
			attrs = Files.readAttributes(source, BasicFileAttributes.class);
		} catch (NoSuchFileException gone) {
			return; // vanished between selection and copy
		} catch (IOException e) {
			reportError(source, e);
			return;
		}

		if (attrs.isDirectory()) {
			copyDirectory(source, target);
		} else {
			copyFile(source, target);
		}
	}

	private void copyDirectory(Path source, Path target) {

		try {
			Files.createDirectories(target); // merge if it already exists
		} catch (FileAlreadyExistsException existsAsFile) {
			Action action = ask(source, target);
			if (action == null || action == Action.CANCEL) {
				aborted = true;
				return;
			}
			if (action == Action.SKIP) {
				return;
			}
			try {
				Files.deleteIfExists(target);
				Files.createDirectories(target);
			} catch (IOException e) {
				reportError(source, e);
				return;
			}
		} catch (IOException e) {
			reportError(source, e);
			return;
		}

		try (DirectoryStream<Path> children = Files.newDirectoryStream(source)) {
			for (Path child : children) {
				if (isCancelled() || aborted) {
					return;
				}
				copyEntry(child, target.resolve(fileName(child)));
			}
		} catch (IOException e) {
			reportError(source, e);
		}
	}

	private void copyFile(Path source, Path target) {

		if (isCancelled()) {
			return;
		}

		cb.onStart("Copying " + fileName(source));

		try {
			if (Files.exists(target)) {
				Action action = ask(source, target);
				if (action == null || action == Action.CANCEL) {
					aborted = true;
					return;
				}
				if (action == Action.SKIP) {
					transferredBytes += Files.size(source);
					cb.onProgress(transferredBytes, totalBytes);
					return;
				}
			}

			transferFile(source, target);
			cb.onComplete();

		} catch (CancelledException cancelled) {
			// user cancelled mid-file; a partial target may remain
		} catch (IOException e) {
			reportError(source, e);
		}
	}

	private void transferFile(Path source, Path target) throws IOException {

		long size = Files.size(source);

		if (source.getFileSystem() == FileSystems.getDefault()) {
			uploadViaScp(source, target, size);
		} else {
			streamCopy(source, target);
		}

		preserveModifiedTime(source, target);
	}

	/** Local file to remote target: SCP over the destination's shared SSH session. */
	private void uploadViaScp(Path source, Path target, long size) throws IOException {

		var time = scpTimestamp(source);

		try (InputStream in = countingInput(Files.newInputStream(source))) {
			connection.scp().upload(in, target.toString(), size, DEFAULT_FILE_PERMISSIONS, time);
		}
	}

	private static ScpTimestampCommandDetails scpTimestamp(Path source) {
		try {
			FileTime modified = Files.getLastModifiedTime(source);
			return new ScpTimestampCommandDetails(modified, modified);
		} catch (IOException e) {
			return null;
		}
	}

	/** Virtual-filesystem source (other SFTP mount, archive mount, …): stream copy. */
	private void streamCopy(Path source, Path target) throws IOException {
		try (InputStream in = countingInput(Files.newInputStream(source));
				OutputStream out = Files.newOutputStream(target,
						StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
			in.transferTo(out);
		}
	}

	private void preserveModifiedTime(Path source, Path target) {
		try {
			Files.setLastModifiedTime(target, Files.getLastModifiedTime(source));
		} catch (IOException e) {
			log.debug("Could not preserve timestamp for [{}]: {}", target, e.getMessage());
		}
	}

	private InputStream countingInput(InputStream in) {
		return new InputStream() {
			@Override
			public int read() throws IOException {
				byte[] one = new byte[1];
				int n = read(one, 0, 1);
				return n < 0 ? -1 : one[0] & 0xFF;
			}

			@Override
			public int read(byte[] buffer, int offset, int length) throws IOException {
				if (isCancelled()) {
					throw new CancelledException();
				}
				int read = in.read(buffer, offset, Math.min(length, BUFFER));
				if (read > 0) {
					transferredBytes += read;
					cb.onProgress(transferredBytes, totalBytes);
				}
				return read;
			}

			@Override
			public void close() throws IOException {
				in.close();
			}
		};
	}

	private Action ask(Path source, Path target) {
		if (resolver == null) {
			return Action.OVERWRITE;
		}
		return resolver.resolve(source, target);
	}

	private void reportError(Path source, Exception e) {
		log.warn("Failed to copy [{}]: {}", source, e.getMessage(), e);
		cb.onError(fileName(source), e);
		boolean skip = errorPrompt == null || errorPrompt.onError(source, e);
		if (!skip) {
			aborted = true;
		}
	}

	private long scanTotalBytes(Iterable<Path> sources) {
		long total = 0;
		for (Path source : sources) {
			if (isCancelled()) {
				return total;
			}
			total += sizeOf(source);
		}
		return total;
	}

	private long sizeOf(Path path) {
		try {
			BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
			if (!attrs.isDirectory()) {
				return attrs.size();
			}
			long total = 0;
			try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
				for (Path child : children) {
					total += sizeOf(child);
				}
			}
			return total;
		} catch (IOException e) {
			return 0;
		}
	}

	private boolean isCancelled() {
		return cb != null && cb.isCancelled();
	}

	private static String fileName(Path path) {
		Path name = path.getFileName();
		return name != null ? name.toString() : path.toString();
	}

	/** Internal signal that the user cancelled mid-transfer. */
	private static final class CancelledException extends IOException {
		private static final long serialVersionUID = 1L;
	}

}
