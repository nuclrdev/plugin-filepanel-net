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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.sftp.client.SftpClient;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;

import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.plugin.core.panel.net.NetDirectoryCache;
import dev.nuclr.plugin.core.panel.net.service.NetEditService;
import dev.nuclr.plugin.core.panel.net.ssh.ConnectionRegistry;
import dev.nuclr.plugin.core.panel.net.ssh.Connections;
import dev.nuclr.plugin.core.panel.net.ssh.NetConnection;
import dev.nuclr.plugin.core.panel.net.ssh.RemotePaths;
import dev.nuclr.plugin.core.panel.net.ssh.ServerConfig;
import dev.nuclr.plugin.core.panel.net.ssh.ServerStore;
import dev.nuclr.plugin.core.panel.net.ssh.ShellEscape;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs the actions this plugin declares in {@code actions.json}: listing saved
 * servers, browsing, reading and writing remote files, running commands and copying
 * between servers.
 *
 * <p>Actions need no pane, selection or open resource, so they behave the same on
 * a headless plugin instance. They share the panels' connection registry, so an
 * agent and a person browsing the same server use one live session, one set of
 * host-key decisions and one password prompt.
 *
 * <p>Error messages go back to whoever asked - often an agent - so they say what to
 * do next, not just what went wrong.
 *
 * <p>This class is the only place the plugin uses SDK API newer than its manifest's
 * {@code platformSdkVersion} ({@code onResult}, {@code onOutput}, {@code confirm});
 * only Commanders that have that API run actions.
 */
@Slf4j
public final class NetActions {

	/** Opens a server profile's connection. Production shares the panels' registry. */
	@FunctionalInterface
	public interface Opener {

		/**
		 * Return an open connection for the profile.
		 *
		 * @param config the server profile
		 * @return the open connection
		 * @throws IOException if connecting, host verification or authentication fails
		 */
		NetConnection open(ServerConfig config) throws IOException;
	}

	public static final String SERVERS_LIST = "net.servers.list";
	public static final String LIST = "net.list";
	public static final String READ = "net.read";
	public static final String EXEC = "net.exec";
	public static final String WRITE = "net.write";
	public static final String COPY = "net.copy";

	/** Every action id this class handles; must match actions.json exactly. */
	public static final Set<String> IDS = Set.of(SERVERS_LIST, LIST, READ, EXEC, WRITE, COPY);

	static final int DEFAULT_LIST_LIMIT = 500;
	static final int MAX_LIST_LIMIT = 5000;
	static final int DEFAULT_READ_BYTES = 256 * 1024;
	static final int MAX_READ_BYTES = 4 * 1024 * 1024;
	static final int DEFAULT_TIMEOUT_SECONDS = 300;
	static final int MAX_TIMEOUT_SECONDS = 3600;
	/** Output kept per stream in the result; everything is still streamed through onOutput. */
	static final int MAX_CAPTURED_OUTPUT = 256 * 1024;
	/** Files larger than this are overwritten without a diff in the confirmation. */
	static final long MAX_DIFF_BYTES = 1024 * 1024;

	private static final Duration CHANNEL_OPEN_TIMEOUT = Duration.ofSeconds(15);
	private static final long POLL_MILLIS = 250;
	private static final int COPY_BUFFER = 64 * 1024;
	private static final SecureRandom RANDOM = new SecureRandom();

	private static final NetActions STANDARD = new NetActions(ServerStore.defaultStore(), Connections::open);

	private final ServerStore store;
	private final Opener opener;

	/**
	 * @param store  where saved server profiles are read from
	 * @param opener opens a profile's connection
	 */
	public NetActions(ServerStore store, Opener opener) {
		this.store = store;
		this.opener = opener;
	}

	/**
	 * Return the instance backed by the user's saved servers and the shared
	 * connection registry.
	 *
	 * @return the standard instance
	 */
	public static NetActions standard() {
		return STANDARD;
	}

	/**
	 * Return whether {@code actionType} is one of this plugin's declared actions.
	 *
	 * @param actionType the action id passed to {@code act}
	 * @return {@code true} if {@link #run} handles it
	 */
	public static boolean handles(String actionType) {
		return actionType != null && IDS.contains(actionType);
	}

	/**
	 * Run an action and report through the callback: {@code onResult} then
	 * {@code onComplete} on success, {@code onError} otherwise.
	 *
	 * @param id       the action id
	 * @param args     the arguments, already validated against the action's schema
	 * @param callback progress, output, result, approvals and cancellation
	 */
	public void run(String id, Map<String, Object> args, NuclrPluginCallback callback) {
		Map<String, Object> arguments = args == null ? Map.of() : args;
		try {
			Map<String, Object> result = switch (id) {
				case SERVERS_LIST -> serversList();
				case LIST -> list(arguments, callback);
				case READ -> read(arguments);
				case EXEC -> exec(arguments, callback);
				case WRITE -> write(arguments, callback);
				case COPY -> copy(arguments, callback);
				default -> throw new ActionException("Unknown action '" + id + "'.");
			};
			callback.onResult(result);
			callback.onComplete();
		} catch (ActionException e) {
			callback.onError(e.getMessage(), e);
		} catch (IOException | RuntimeException e) {
			log.warn("Action {} failed: {}", id, e.toString());
			callback.onError(id + " failed: " + describe(e), e);
		}
	}

	// =========================================================================
	// net.servers.list
	// =========================================================================

	private Map<String, Object> serversList() {
		var servers = new ArrayList<Map<String, Object>>();
		for (ServerConfig config : store.load()) {
			var server = new LinkedHashMap<String, Object>();
			server.put("id", config.getId());
			server.put("name", config.displayName());
			server.put("host", config.getHost());
			server.put("port", config.getPort());
			server.put("username", config.getUsername());
			server.put("auth", config.usesKey() ? "key" : "password");
			server.put("connected", ConnectionRegistry.isConnected(config.getId()));
			servers.add(server);
		}
		return Map.of("servers", servers);
	}

	// =========================================================================
	// net.list
	// =========================================================================

	private Map<String, Object> list(Map<String, Object> args, NuclrPluginCallback callback)
			throws ActionException, IOException {

		ServerConfig config = server(text(args, "server", true));
		int limit = integer(args, "limit", DEFAULT_LIST_LIMIT, 1, MAX_LIST_LIMIT);
		NetConnection connection = connect(config);
		String path = resolve(connection, text(args, "path", false));

		SftpClient.Attributes attrs = connection.statOrNull(path);
		if (attrs == null) {
			throw new ActionException("Not found on " + config.displayName() + ": " + path);
		}
		if (!attrs.isDirectory()) {
			throw new ActionException(path + " is a file, not a folder. Use net.read to read it.");
		}

		var entries = new ArrayList<Map<String, Object>>();
		try (SftpClient sftp = connection.sftp()) {
			for (SftpClient.DirEntry entry : sftp.readDir(path)) {
				if (callback.isCancelled()) {
					throw new ActionException("Cancelled.");
				}
				String name = entry.getFilename();
				if (".".equals(name) || "..".equals(name)) {
					continue;
				}
				entries.add(describeEntry(name, entry.getAttributes()));
			}
		}
		entries.sort(Comparator.comparing(entry -> (String) entry.get("name")));

		int total = entries.size();
		boolean truncated = total > limit;
		var result = new LinkedHashMap<String, Object>();
		result.put("path", path);
		result.put("entries", truncated ? new ArrayList<>(entries.subList(0, limit)) : entries);
		result.put("total", total);
		result.put("truncated", truncated);
		return result;
	}

	private static Map<String, Object> describeEntry(String name, SftpClient.Attributes attrs) {
		var entry = new LinkedHashMap<String, Object>();
		entry.put("name", name);
		entry.put("type", typeOf(attrs));
		entry.put("size", attrs.getSize());
		FileTime modified = attrs.getModifyTime();
		entry.put("modified", modified == null ? null : modified.toInstant().toString());
		entry.put("permissions", permissions(attrs.getPermissions()));
		return entry;
	}

	private static String typeOf(SftpClient.Attributes attrs) {
		if (attrs.isSymbolicLink()) {
			return "symlink";
		}
		if (attrs.isDirectory()) {
			return "directory";
		}
		if (attrs.isRegularFile()) {
			return "file";
		}
		return "other";
	}

	/** Format the low nine permission bits as {@code ls} does, e.g. {@code rwxr-x---}. */
	static String permissions(int mode) {
		char[] symbols = { 'r', 'w', 'x' };
		var out = new StringBuilder(9);
		for (int bit = 8; bit >= 0; bit--) {
			out.append((mode & (1 << bit)) != 0 ? symbols[(8 - bit) % 3] : '-');
		}
		return out.toString();
	}

	// =========================================================================
	// net.read
	// =========================================================================

	private Map<String, Object> read(Map<String, Object> args) throws ActionException, IOException {

		ServerConfig config = server(text(args, "server", true));
		int maxBytes = integer(args, "maxBytes", DEFAULT_READ_BYTES, 1, MAX_READ_BYTES);
		NetConnection connection = connect(config);
		String path = resolve(connection, text(args, "path", true));

		SftpClient.Attributes attrs = connection.statOrNull(path);
		if (attrs == null) {
			throw new ActionException("Not found on " + config.displayName() + ": " + path);
		}
		if (attrs.isDirectory()) {
			throw new ActionException(path + " is a folder. Use net.list to see what it contains.");
		}

		byte[] data;
		boolean truncated;
		try (SftpClient sftp = connection.sftp(); InputStream in = sftp.read(path)) {
			data = in.readNBytes(maxBytes);
			truncated = in.read() != -1;
		}

		String text = decodeUtf8(data, truncated);
		var result = new LinkedHashMap<String, Object>();
		result.put("path", path);
		result.put("size", attrs.getSize());
		result.put("encoding", text != null ? "utf-8" : "base64");
		result.put("content", text != null ? text : Base64.getEncoder().encodeToString(data));
		result.put("truncated", truncated);
		return result;
	}

	/**
	 * Decode as UTF-8 text, or return {@code null} for binary data. When the data
	 * was cut short, up to three trailing bytes may be a split character and are
	 * dropped rather than counted as binary.
	 */
	static String decodeUtf8(byte[] data, boolean truncated) {
		int maxTrim = truncated ? Math.min(3, data.length) : 0;
		for (int trim = 0; trim <= maxTrim; trim++) {
			try {
				String text = StandardCharsets.UTF_8.newDecoder()
						.onMalformedInput(CodingErrorAction.REPORT)
						.onUnmappableCharacter(CodingErrorAction.REPORT)
						.decode(ByteBuffer.wrap(data, 0, data.length - trim))
						.toString();
				return text.indexOf('\0') >= 0 ? null : text;
			} catch (CharacterCodingException e) {
				// try again without a possibly split last character
			}
		}
		return null;
	}

	// =========================================================================
	// net.exec
	// =========================================================================

	private Map<String, Object> exec(Map<String, Object> args, NuclrPluginCallback callback)
			throws ActionException, IOException {

		ServerConfig config = server(text(args, "server", true));
		String command = text(args, "command", true);
		int timeoutSeconds = integer(args, "timeoutSeconds", DEFAULT_TIMEOUT_SECONDS, 1, MAX_TIMEOUT_SECONDS);
		NetConnection connection = connect(config);
		String cwd = text(args, "cwd", false);
		String commandLine = cwd == null ? command : withWorkingDirectory(resolve(connection, cwd), command);

		// Connecting can take a while, prompts included; a Cancel pressed meanwhile
		// must mean the command never starts.
		checkCancelled(callback, "Cancelled before the command started; nothing ran.");

		var stdout = new OutputCapture(callback, MAX_CAPTURED_OUTPUT);
		var stderr = new OutputCapture(callback, MAX_CAPTURED_OUTPUT);
		boolean timedOut = false;
		Integer exitStatus;

		// execChannel only holds the connection's lock while creating the channel, so
		// a long command does not block the panels browsing the same server.
		ChannelExec channel = connection.execChannel(commandLine);
		try {
			// An empty, already finished stdin: commands that read input see end of
			// input at once instead of waiting until the timeout.
			channel.setIn(new ByteArrayInputStream(new byte[0]));
			channel.setOut(stdout);
			channel.setErr(stderr);
			channel.open().verify(CHANNEL_OPEN_TIMEOUT);

			long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
			while (true) {
				Set<ClientChannelEvent> events = channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), POLL_MILLIS);
				if (events.contains(ClientChannelEvent.CLOSED)) {
					break;
				}
				if (callback.isCancelled()) {
					channel.close(true);
					throw new ActionException("Cancelled. The command was stopped and may have partly run.");
				}
				if (System.nanoTime() > deadline) {
					timedOut = true;
					channel.close(true);
					break;
				}
			}
			exitStatus = channel.getExitStatus();
		} finally {
			channel.close(false);
			stdout.finish();
			stderr.finish();
		}

		var result = new LinkedHashMap<String, Object>();
		result.put("exitCode", exitStatus == null ? -1 : exitStatus);
		result.put("stdout", stdout.text());
		result.put("stderr", stderr.text());
		result.put("timedOut", timedOut);
		result.put("truncated", stdout.truncated() || stderr.truncated());
		return result;
	}

	/**
	 * Prefix a command line with a change of folder that guards all of it. With
	 * {@code cd dir && a; b} only {@code a} depends on the {@code cd}, so {@code b}
	 * would run in the login folder after a failed {@code cd}. Here a failed
	 * {@code cd} ends the shell, with its status, before any of the line runs. The
	 * command goes on its own line so nothing in it can join the guard.
	 */
	static String withWorkingDirectory(String folder, String command) {
		return "cd " + ShellEscape.quote(folder) + " || exit\n" + command;
	}

	// =========================================================================
	// net.write
	// =========================================================================

	private Map<String, Object> write(Map<String, Object> args, NuclrPluginCallback callback)
			throws ActionException, IOException {

		ServerConfig config = server(text(args, "server", true));
		if (!(args.get("content") instanceof String content)) {
			throw new ActionException("Missing argument 'content' (the file's full new content).");
		}
		String encoding = text(args, "encoding", false);
		byte[] bytes = encode(content, encoding == null ? "utf-8" : encoding);
		boolean createDirs = flag(args, "createDirs", false);

		NetConnection connection = connect(config);
		checkCancelled(callback, "Cancelled; nothing was written.");
		String target = resolve(connection, text(args, "path", true));
		if (RemotePaths.isRoot(target)) {
			throw new ActionException("Cannot write to the root folder itself; give a file path.");
		}

		SftpClient.Attributes existing = connection.statOrNull(target);
		if (existing != null && existing.isDirectory()) {
			throw new ActionException(target + " is a folder; give the path of a file inside it.");
		}
		String parent = parentOf(target);
		ensureFolder(connection, parent, createDirs);

		if (existing != null) {
			byte[] old = existing.getSize() <= MAX_DIFF_BYTES ? readAll(connection, target) : null;
			if (old != null && Arrays.equals(old, bytes)) {
				return writeResult(target, 0, false, true);
			}
			String diff = old == null ? null : unifiedDiff(target, old, bytes);
			String details = "Server: " + config.displayName() + " (" + config.address() + ")\n"
					+ "File: " + target + "\n"
					+ "Size: " + existing.getSize() + " -> " + bytes.length + " bytes"
					+ (diff == null ? "\nNo diff to show: the file is binary or larger than 1 MB." : "");
			if (!callback.confirm("Overwrite " + target + " on " + config.displayName() + "?", details, diff)) {
				throw new ActionException("Not written: overwriting " + target + " was not approved.");
			}
		}

		// Upload to a hidden sibling, then rename over the target: readers never see a
		// half-written file, and a failure or Cancel at any point before the rename
		// leaves the target exactly as it was and removes the sibling.
		String sibling = NetEditService.uploadSiblingPath(target, HexFormat.of().formatHex(randomBytes(4)));
		boolean replaced = false;
		try {
			checkCancelled(callback, "Cancelled; " + target + " was not written.");
			// A replaced file keeps its mode (a 0600 secret stays 0600, a script stays
			// executable); a new one gets the server's default.
			createStagingFile(connection, sibling, existing == null ? null : existing.getPermissions(), target);
			Files.write(connection.path(sibling), bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
			checkCancelled(callback, "Cancelled; " + target + " was not written.");
			connection.atomicReplace(sibling, target);
			replaced = true;
		} finally {
			if (!replaced) {
				deleteQuietly(connection, sibling);
			}
		}
		NetDirectoryCache.invalidate(config.getId(), parent);
		return writeResult(target, bytes.length, existing == null, false);
	}

	private static Map<String, Object> writeResult(String path, long bytesWritten, boolean created, boolean unchanged) {
		var result = new LinkedHashMap<String, Object>();
		result.put("path", path);
		result.put("bytesWritten", bytesWritten);
		result.put("created", created);
		result.put("unchanged", unchanged);
		return result;
	}

	private static byte[] encode(String content, String encoding) throws ActionException {
		return switch (encoding) {
			case "utf-8" -> content.getBytes(StandardCharsets.UTF_8);
			case "base64" -> {
				try {
					yield Base64.getDecoder().decode(content);
				} catch (IllegalArgumentException e) {
					throw new ActionException("'content' is not valid base64: " + e.getMessage());
				}
			}
			default -> throw new ActionException("Unknown encoding '" + encoding + "'; use utf-8 or base64.");
		};
	}

	/** A unified diff of two text versions, or {@code null} if either is not text. */
	static String unifiedDiff(String path, byte[] before, byte[] after) {
		String oldText = decodeUtf8(before, false);
		String newText = decodeUtf8(after, false);
		if (oldText == null || newText == null) {
			return null;
		}
		List<String> oldLines = Arrays.asList(oldText.split("\n", -1));
		List<String> newLines = Arrays.asList(newText.split("\n", -1));
		List<String> diff = UnifiedDiffUtils.generateUnifiedDiff(path, path, oldLines,
				DiffUtils.diff(oldLines, newLines), 3);
		return String.join("\n", diff);
	}

	/**
	 * Create the empty staging file that will be renamed over {@code destination},
	 * with its final mode already set when one is given, so the content never sits
	 * in it under looser permissions than the file will have.
	 *
	 * @param mode the permission bits the file must end up with, or {@code null}
	 *             for the server's default (a new file nobody asked to restrict)
	 */
	private static void createStagingFile(NetConnection connection, String sibling, Integer mode, String destination)
			throws IOException, ActionException {
		Files.newOutputStream(connection.path(sibling), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).close();
		if (mode == null) {
			return;
		}
		int wanted = mode & 07777;
		try (SftpClient sftp = connection.sftp()) {
			sftp.setStat(sibling, new SftpClient.Attributes().perms(wanted));
		} catch (IOException e) {
			log.debug("Server refused permissions {} on {}: {}", Integer.toOctalString(wanted), sibling, e.getMessage());
		}
		// Whatever the server said, check what it did, and require the exact mode.
		// Any difference changes the file: extra bits widen who can read it (a 0600
		// secret turning 0644), missing ones break it (a 0755 script turning 0644
		// can no longer run). If the server will not set the mode, nothing is
		// replaced or created.
		SftpClient.Attributes actual = connection.statOrNull(sibling);
		int got = actual == null ? 0 : actual.getPermissions() & 07777;
		if (got != wanted) {
			String effect = (got & ~wanted & 0777) != 0 ? "more widely accessible"
					: (wanted & ~got & 0111) != 0 ? "no longer executable" : "different";
			throw new ActionException(destination + " was not written: the server would not give it permissions "
					+ octal(wanted) + " (the new version got " + octal(got) + "), which would make it " + effect
					+ ". Set the permissions with net.exec (chmod) if a change is intended.");
		}
	}

	private static String octal(int mode) {
		return String.format("%04o", mode & 07777);
	}

	// =========================================================================
	// net.copy
	// =========================================================================

	private Map<String, Object> copy(Map<String, Object> args, NuclrPluginCallback callback)
			throws ActionException, IOException {

		ServerConfig fromConfig = server(text(args, "fromServer", true));
		ServerConfig toConfig = server(text(args, "toServer", true));
		boolean overwrite = flag(args, "overwrite", false);

		NetConnection from = connect(fromConfig);
		NetConnection to = connect(toConfig);
		String source = resolve(from, text(args, "fromPath", true));
		String target = resolve(to, text(args, "toPath", true));

		SftpClient.Attributes sourceAttrs = from.statOrNull(source);
		if (sourceAttrs == null) {
			throw new ActionException("Not found on " + fromConfig.displayName() + ": " + source);
		}
		// Two profiles can name one machine (saved twice, or by name and by address),
		// so compare where they connect, not which profile was named; and compare
		// the paths as the server resolves them, through symlinks and "..".
		if (sameEndpoint(fromConfig, toConfig)) {
			String realSource = canonical(from, source);
			String realTarget = canonicalTarget(to, target);
			if (realSource.equals(realTarget) || realTarget.startsWith(withSlash(realSource))) {
				throw new ActionException("Cannot copy " + source + " onto itself or into itself ("
						+ fromConfig.displayName() + " and " + toConfig.displayName() + " are the same server).");
			}
		}

		// Work out every folder and file first, so nothing is written before the
		// overwrite check has passed.
		var folders = new ArrayList<String>();
		var files = new ArrayList<String>();
		int skippedLinks = 0;
		if (sourceAttrs.isDirectory()) {
			folders.add("");
			Path root = from.path(source);
			try (Stream<Path> walk = Files.walk(root)) {
				for (Path path : (Iterable<Path>) walk::iterator) {
					if (callback.isCancelled()) {
						throw new ActionException("Cancelled. Nothing was copied.");
					}
					if (path.equals(root)) {
						continue;
					}
					String relative = root.relativize(path).toString().replace('\\', '/');
					if (Files.isSymbolicLink(path)) {
						skippedLinks++;
					} else if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
						folders.add(relative);
					} else {
						files.add(relative);
					}
				}
			}
		} else if (sourceAttrs.isSymbolicLink()) {
			throw new ActionException(source + " is a symbolic link; copy the file it points to instead.");
		} else {
			files.add("");
		}

		var existing = new ArrayList<String>();
		// The mode each copy must end up with, as cp does: a file being replaced keeps
		// its own mode, a new file takes the source's. Either way a 0600 secret never
		// lands readable by others, and an executable stays executable.
		var modes = new HashMap<String, Integer>();
		long totalBytes = 0;
		for (String file : files) {
			String destination = join(target, file);
			SftpClient.Attributes fileAttrs = from.statOrNull(join(source, file));
			SftpClient.Attributes destinationAttrs = to.statOrNull(destination);
			if (destinationAttrs != null) {
				if (destinationAttrs.isDirectory()) {
					throw new ActionException(destination + " on " + toConfig.displayName()
							+ " is a folder, so the file " + join(source, file) + " cannot replace it.");
				}
				existing.add(destination);
				modes.put(file, destinationAttrs.getPermissions());
			} else if (fileAttrs != null) {
				modes.put(file, fileAttrs.getPermissions());
			}
			totalBytes += fileAttrs == null ? 0 : fileAttrs.getSize();
		}
		if (!existing.isEmpty()) {
			if (!overwrite) {
				throw new ActionException(existing.size() + " file(s) already exist at the destination (first: "
						+ existing.get(0) + "). Nothing was copied. Pass overwrite: true to replace them.");
			}
			String list = existing.stream().limit(10).map(path -> "  " + path).collect(Collectors.joining("\n"))
					+ (existing.size() > 10 ? "\n  ... and " + (existing.size() - 10) + " more" : "");
			if (!callback.confirm("Replace " + existing.size() + " existing file(s) on " + toConfig.displayName() + "?",
					"Copying from " + fromConfig.displayName() + ":" + source + "\nto " + toConfig.displayName() + ":"
							+ target + "\n\nThese files will be replaced:\n" + list,
					null)) {
				throw new ActionException("Nothing was copied: replacing existing files was not approved.");
			}
		}

		checkCancelled(callback, "Cancelled; nothing was copied.");
		callback.onStart("Copying " + source + " to " + toConfig.displayName() + ":" + target);
		for (String folder : folders) {
			Files.createDirectories(to.path(join(target, folder)));
		}
		long copied = 0;
		int done = 0;
		byte[] buffer = new byte[COPY_BUFFER];
		for (String file : files) {
			String destination = join(target, file);
			// Streamed host to host through this process: the bytes never reach the
			// caller, so an agent can move a file of secrets without reading it.
			// Each file lands in a hidden sibling and is renamed into place only when
			// complete: a failed or cancelled copy never leaves a half-written file,
			// and a source that turns out to be the destination itself is read in
			// full before anything replaces it.
			String sibling = NetEditService.uploadSiblingPath(destination, HexFormat.of().formatHex(randomBytes(4)));
			boolean replaced = false;
			try {
				createStagingFile(to, sibling, modes.get(file), destination);
				try (InputStream in = Files.newInputStream(from.path(join(source, file)));
						OutputStream out = Files.newOutputStream(to.path(sibling), StandardOpenOption.WRITE,
								StandardOpenOption.TRUNCATE_EXISTING)) {
					int read;
					while ((read = in.read(buffer)) != -1) {
						checkCancelled(callback, cancelledCopy(done, destination));
						out.write(buffer, 0, read);
						copied += read;
						callback.onProgress(copied, totalBytes);
					}
				}
				checkCancelled(callback, cancelledCopy(done, destination));
				to.atomicReplace(sibling, destination);
				replaced = true;
				done++;
			} finally {
				if (!replaced) {
					deleteQuietly(to, sibling);
				}
			}
		}
		NetDirectoryCache.invalidate(toConfig.getId(), parentOf(target));
		NetDirectoryCache.invalidate(toConfig.getId(), target);

		var result = new LinkedHashMap<String, Object>();
		result.put("files", files.size());
		result.put("folders", sourceAttrs.isDirectory() ? folders.size() : 0);
		result.put("bytes", copied);
		result.put("skippedSymlinks", skippedLinks);
		return result;
	}

	private static String cancelledCopy(int done, String current) {
		return "Cancelled after copying " + done + " file(s); " + current + " was left as it was.";
	}

	/**
	 * Whether two profiles reach the same SSH endpoint: the same profile, the same
	 * host and port, or host names that resolve to a common address on the same
	 * port. A name that does not resolve is not considered the same: connecting to
	 * it fails anyway. Copies are staged, so even an alias this cannot recognise
	 * never truncates a source before it is read.
	 */
	static boolean sameEndpoint(ServerConfig a, ServerConfig b) {
		if (a.getId().equals(b.getId())) {
			return true;
		}
		if (a.getPort() != b.getPort()) {
			return false;
		}
		String hostA = normalizeHost(a.getHost());
		String hostB = normalizeHost(b.getHost());
		if (hostA.equals(hostB)) {
			return true;
		}
		try {
			Set<InetAddress> addressesA = Set.of(InetAddress.getAllByName(hostA));
			for (InetAddress address : InetAddress.getAllByName(hostB)) {
				if (addressesA.contains(address)) {
					return true;
				}
			}
		} catch (UnknownHostException | IllegalArgumentException e) {
			return false;
		}
		return false;
	}

	private static String normalizeHost(String host) {
		String value = host == null ? "" : host.strip().toLowerCase(Locale.ROOT);
		if (value.endsWith(".")) {
			value = value.substring(0, value.length() - 1);
		}
		if (value.startsWith("[") && value.endsWith("]")) {
			value = value.substring(1, value.length() - 1);
		}
		return value;
	}

	/** The server's own absolute form of an existing path: symlinks and ".." resolved. */
	private static String canonical(NetConnection connection, String path) {
		try (SftpClient sftp = connection.sftp()) {
			String real = sftp.canonicalPath(path);
			return real == null || real.isBlank() ? path : RemotePaths.normalize(real);
		} catch (IOException e) {
			return path;
		}
	}

	/** Like {@link #canonical}, for a path that may not exist yet: resolves its deepest existing folder. */
	private static String canonicalTarget(NetConnection connection, String path) throws IOException {
		String existing = path;
		var missing = new ArrayList<String>();
		while (!RemotePaths.isRoot(existing) && connection.statOrNull(existing) == null) {
			missing.add(0, RemotePaths.name(existing));
			existing = parentOf(existing);
		}
		String result = canonical(connection, existing);
		for (String name : missing) {
			result = RemotePaths.join(result, name);
		}
		return RemotePaths.normalize(result);
	}

	private static String withSlash(String path) {
		return path.endsWith("/") ? path : path + "/";
	}

	// =========================================================================
	// Shared helpers
	// =========================================================================

	/** Find a saved server by id, or by name ignoring case. */
	ServerConfig server(String reference) throws ActionException {
		List<ServerConfig> configs = store.load();
		for (ServerConfig config : configs) {
			if (config.getId().equals(reference)) {
				return config;
			}
		}
		List<ServerConfig> byName = configs.stream()
				.filter(config -> reference.equalsIgnoreCase(config.displayName())
						|| reference.equalsIgnoreCase(config.getName()))
				.toList();
		if (byName.size() == 1) {
			return byName.get(0);
		}
		String names = configs.stream().map(ServerConfig::displayName).collect(Collectors.joining(", "));
		if (byName.isEmpty()) {
			throw new ActionException("No saved server '" + reference + "'. Saved servers: "
					+ (names.isEmpty() ? "none - add one in the Net panel first." : names + "."));
		}
		throw new ActionException("'" + reference + "' matches " + byName.size()
				+ " saved servers; use the id from net.servers.list instead.");
	}

	private NetConnection connect(ServerConfig config) throws ActionException {
		try {
			return opener.open(config);
		} catch (IOException e) {
			throw new ActionException("Cannot connect to " + config.displayName() + ": " + describe(e), e);
		}
	}

	/** Absolute paths as given; {@code ~}, {@code ~/x} and relative paths from the login folder. */
	static String resolve(NetConnection connection, String path) {
		if (path == null || path.isBlank() || "~".equals(path)) {
			return connection.home();
		}
		if (path.startsWith("/")) {
			return RemotePaths.normalize(path);
		}
		String relative = path.startsWith("~/") ? path.substring(2) : path;
		return RemotePaths.normalize(RemotePaths.join(connection.home(), relative));
	}

	private static String parentOf(String path) {
		String parent = RemotePaths.parent(path);
		return parent == null ? "/" : parent;
	}

	private static String join(String base, String relative) {
		return relative.isEmpty() ? base : RemotePaths.normalize(RemotePaths.join(base, relative));
	}

	private static void ensureFolder(NetConnection connection, String folder, boolean create)
			throws ActionException, IOException {
		SftpClient.Attributes attrs = connection.statOrNull(folder);
		if (attrs == null) {
			if (!create) {
				throw new ActionException("Folder " + folder + " does not exist. Pass createDirs: true to create it.");
			}
			Files.createDirectories(connection.path(folder));
		} else if (!attrs.isDirectory()) {
			throw new ActionException(folder + " is a file, so nothing can be written inside it.");
		}
	}

	private static byte[] readAll(NetConnection connection, String path) throws IOException {
		try (SftpClient sftp = connection.sftp(); InputStream in = sftp.read(path)) {
			return in.readAllBytes();
		}
	}

	private static void checkCancelled(NuclrPluginCallback callback, String message) throws ActionException {
		if (callback.isCancelled()) {
			throw new ActionException(message);
		}
	}

	private static void deleteQuietly(NetConnection connection, String path) {
		try (SftpClient sftp = connection.sftp()) {
			sftp.remove(path);
		} catch (IOException e) {
			log.debug("Could not remove {}: {}", path, e.getMessage());
		}
	}

	private static byte[] randomBytes(int count) {
		byte[] bytes = new byte[count];
		RANDOM.nextBytes(bytes);
		return bytes;
	}

	private static String describe(Exception e) {
		String message = e.getMessage();
		return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
	}

	static String text(Map<String, Object> args, String name, boolean required) throws ActionException {
		Object value = args.get(name);
		if (value instanceof String text && !text.isBlank()) {
			return text;
		}
		if (value != null && !(value instanceof String)) {
			throw new ActionException("Argument '" + name + "' must be a string.");
		}
		if (required) {
			throw new ActionException("Missing argument '" + name + "'.");
		}
		return null;
	}

	static int integer(Map<String, Object> args, String name, int defaultValue, int min, int max)
			throws ActionException {
		Object value = args.get(name);
		if (value == null) {
			return defaultValue;
		}
		if (!(value instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue())) {
			throw new ActionException("Argument '" + name + "' must be a whole number.");
		}
		long whole = number.longValue();
		if (whole < min || whole > max) {
			throw new ActionException("Argument '" + name + "' must be between " + min + " and " + max + ".");
		}
		return (int) whole;
	}

	static boolean flag(Map<String, Object> args, String name, boolean defaultValue) throws ActionException {
		Object value = args.get(name);
		if (value == null) {
			return defaultValue;
		}
		if (!(value instanceof Boolean bool)) {
			throw new ActionException("Argument '" + name + "' must be true or false.");
		}
		return bool;
	}

	/** A failure whose message is meant for the caller as it stands. */
	static final class ActionException extends Exception {

		private static final long serialVersionUID = 1L;

		ActionException(String message) {
			super(message);
		}

		ActionException(String message, Throwable cause) {
			super(message, cause);
		}
	}

}
