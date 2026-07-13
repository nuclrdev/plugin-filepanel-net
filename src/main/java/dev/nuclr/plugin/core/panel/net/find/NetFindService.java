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
package dev.nuclr.plugin.core.panel.net.find;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;

import dev.nuclr.plugin.core.panel.net.ssh.NetConnection;
import dev.nuclr.plugin.core.panel.net.ssh.RemotePaths;
import dev.nuclr.plugin.core.panel.net.ssh.ShellEscape;
import lombok.extern.slf4j.Slf4j;

/**
 * Alt+F7 filename search for the Net panel.
 *
 * <p>Glob searches are first attempted with the remote {@code find} binary
 * (fast, server-side, honours the server's own permission model) via an exec
 * channel that streams {@code -print0}-separated matches back as they are
 * found. When the remote {@code find} is unavailable — the exec channel
 * cannot be opened, the command is not found ({@code exit 127}), or it exits
 * non-zero having produced no matches and stderr looks like a missing-command
 * error — the search falls back to a recursive walk over SFTP, matching each
 * entry name against the compiled pattern client-side. Regex searches always
 * use the SFTP walk, since {@code find}'s regex dialects vary too much across
 * server platforms (GNU, BusyBox, macOS) to target reliably from one command
 * line.
 */
@Slf4j
public final class NetFindService {

	private static final Duration EXEC_TIMEOUT = Duration.ofMinutes(10);

	private NetFindService() {
	}

	/**
	 * Run a search, streaming absolute remote paths of matches to
	 * {@code onMatch} as they are found.
	 *
	 * @param connection the server connection
	 * @param request    the search parameters
	 * @param cancelled  checked regularly; stops the search promptly when set
	 * @param onMatch    receives each matching absolute remote path
	 * @throws IOException if neither the remote {@code find} nor the SFTP
	 *                      fallback can complete (e.g. the root does not exist)
	 */
	public static void search(NetConnection connection, NetFindRequest request, AtomicBoolean cancelled,
			Consumer<String> onMatch) throws IOException {

		if (request.matchMode() == FindMatchMode.GLOB) {
			try {
				if (searchViaRemoteFind(connection, request, cancelled, onMatch)) {
					return;
				}
			} catch (IOException e) {
				log.info("Remote find unavailable for {} ({}); falling back to SFTP walk",
						request.rootPath(), e.getMessage());
			}
		}

		searchViaSftpWalk(connection, request, cancelled, onMatch);
	}

	/**
	 * Try the remote {@code find} command.
	 *
	 * @return {@code true} when {@code find} ran successfully (including
	 *         "ran but zero matches") and no fallback is needed
	 */
	private static boolean searchViaRemoteFind(NetConnection connection, NetFindRequest request,
			AtomicBoolean cancelled, Consumer<String> onMatch) throws IOException {

		String nameFlag = request.caseSensitive() ? "-name" : "-iname";
		String command = "find " + ShellEscape.quote(request.rootPath()) + " -mindepth 1 " + nameFlag + " "
				+ ShellEscape.quote(request.namePattern()) + " -print0";

		ChannelExec channel = connection.execChannel(command);
		int matches = 0;
		ByteArrayOutputStream stderr = new ByteArrayOutputStream();

		try {
			channel.open().verify(Duration.ofSeconds(15));

			Thread errorPump = Thread.ofVirtual().start(() -> pump(channel.getInvertedErr(), stderr));

			matches = readNulSeparated(channel.getInvertedOut(), cancelled, onMatch);

			channel.waitFor(java.util.Set.of(ClientChannelEvent.CLOSED), EXEC_TIMEOUT);
			try {
				errorPump.join(Duration.ofSeconds(2).toMillis());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}

			if (cancelled != null && cancelled.get()) {
				return true; // user cancelled: not a fallback situation
			}

			Integer exitStatus = channel.getExitStatus();
			String stderrText = stderr.toString(StandardCharsets.UTF_8);

			if (exitStatus != null && exitStatus == 127) {
				throw new IOException("remote find not found (exit 127)");
			}
			if (matches == 0 && exitStatus != null && exitStatus != 0 && looksLikeMissingCommand(stderrText)) {
				throw new IOException("remote find appears unavailable: " + firstLine(stderrText));
			}
			if (exitStatus != null && exitStatus != 0 && !stderrText.isBlank()) {
				log.debug("Remote find on {} exited {} with stderr: {}", request.rootPath(), exitStatus, stderrText);
			}
			return true;

		} finally {
			channel.close(false);
		}
	}

	private static boolean looksLikeMissingCommand(String stderr) {
		String lower = stderr.toLowerCase(java.util.Locale.ROOT);
		return lower.contains("not found") || lower.contains("no such file or directory: find")
				|| lower.contains("command not found");
	}

	private static String firstLine(String text) {
		int newline = text.indexOf('\n');
		return newline < 0 ? text : text.substring(0, newline);
	}

	private static void pump(InputStream in, ByteArrayOutputStream out) {
		try {
			in.transferTo(out);
		} catch (IOException e) {
			log.debug("Error reading remote find stderr: {}", e.getMessage());
		}
	}

	/** Read a {@code -print0}-separated stream, invoking {@code onMatch} per entry as it completes. */
	private static int readNulSeparated(InputStream in, AtomicBoolean cancelled, Consumer<String> onMatch)
			throws IOException {

		var buffer = new ByteArrayOutputStream(256);
		int matches = 0;
		int b;

		while ((b = in.read()) >= 0) {
			if (cancelled != null && cancelled.get()) {
				break;
			}
			if (b == 0) {
				String path = buffer.toString(StandardCharsets.UTF_8);
				buffer.reset();
				if (!path.isEmpty()) {
					onMatch.accept(path);
					matches++;
				}
			} else {
				buffer.write(b);
			}
		}
		return matches;
	}

	/** Recursive SFTP walk, matching each entry name against the compiled pattern. */
	private static void searchViaSftpWalk(NetConnection connection, NetFindRequest request, AtomicBoolean cancelled,
			Consumer<String> onMatch) throws IOException {

		Pattern pattern = request.matchMode() == FindMatchMode.GLOB
				? GlobMatcher.compile(request.namePattern(), request.caseSensitive())
				: Pattern.compile(request.namePattern(),
						request.caseSensitive() ? 0 : Pattern.CASE_INSENSITIVE);

		Path root = connection.path(request.rootPath());
		walk(root, pattern, cancelled, onMatch);
	}

	private static void walk(Path dir, Pattern pattern, AtomicBoolean cancelled, Consumer<String> onMatch)
			throws IOException {

		List<Path> subdirs = new java.util.ArrayList<>();

		try (DirectoryStream<Path> children = Files.newDirectoryStream(dir)) {
			for (Path child : children) {
				if (cancelled != null && cancelled.get()) {
					return;
				}
				String name = child.getFileName() != null ? child.getFileName().toString() : child.toString();
				boolean directory;
				try {
					directory = Files.isDirectory(child);
				} catch (RuntimeException e) {
					continue; // unreadable entry (broken link, permission) — skip like find does
				}
				if (pattern.matcher(name).matches()) {
					onMatch.accept(RemotePaths.normalize(child.toString().replace('\\', '/')));
				}
				if (directory) {
					subdirs.add(child);
				}
			}
		}

		for (Path subdir : subdirs) {
			if (cancelled != null && cancelled.get()) {
				return;
			}
			try {
				walk(subdir, pattern, cancelled, onMatch);
			} catch (IOException e) {
				log.debug("Skipping unreadable directory {}: {}", subdir, e.getMessage());
			}
		}
	}

}
