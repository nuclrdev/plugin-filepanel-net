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

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;

import org.apache.sshd.sftp.client.SftpClient;

import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.net.ssh.RemotePaths;

/**
 * A remote file or directory shown in the Net panel. Backed by a NIO
 * {@code Path} on the connection's mounted SFTP filesystem, so host features
 * that work on paths (copy engines, editors, viewers, archive mounting)
 * operate on it directly. The metadata additionally records the owning server
 * id and the remote path as text, allowing the plugin to re-resolve the entry
 * after a reconnect replaced the filesystem instance.
 */
public final class NetResource extends NuclrResource {

	private static final long serialVersionUID = 1L;

	/** Columns displayed for remote directory listings. */
	public static final List<String> ColumnNames = List.of(
			"Name",
			"Extension",
			"Size",
			"Type",
			"Modified",
			"Permissions",
			"Full Path");

	/** Metadata key: owning server profile id (String). */
	public static final String KeyServerId = "net.server";

	/** Metadata key: absolute remote path as text (String). */
	public static final String KeyRemotePath = "net.path";

	private static final DateTimeFormatter DISPLAY_DATE_TIME = DateTimeFormatter.ofLocalizedDateTime(
			FormatStyle.SHORT,
			FormatStyle.MEDIUM);

	/**
	 * Build a remote entry from one SFTP listing element. All attributes come
	 * from the already-fetched {@code attrs}; no extra round trips are made.
	 *
	 * @param ctx        plugin context (locale for date formatting), may be {@code null}
	 * @param serverId   owning server profile id
	 * @param sftpPath   NIO path on the mounted SFTP filesystem
	 * @param remotePath absolute remote path as text
	 * @param attrs      attributes as returned by the SFTP listing (already
	 *                   symlink-resolved where the caller chose to)
	 * @param link       whether the directory entry itself is a symlink
	 */
	public NetResource(NuclrPluginContext ctx, String serverId, Path sftpPath, String remotePath,
			SftpClient.Attributes attrs, boolean link) {

		super(sftpPath);

		String entryName = RemotePaths.name(remotePath);
		this.name = entryName;
		this.setFullPath(remotePath);
		this.setUuid(serverId + ":" + remotePath);

		boolean directory = attrs != null && attrs.isDirectory();
		long size = attrs != null && !directory ? attrs.getSize() : 0L;

		this.setFolder(directory);
		this.setLink(link);
		this.setLength(size);
		this.setHidden(entryName.startsWith(".") && !entryName.equals("..") && !entryName.equals("."));
		this.setReadable(true);

		LocalDateTime modified = toLocal(attrs != null ? attrs.getModifyTime() : null);
		LocalDateTime accessed = toLocal(attrs != null ? attrs.getAccessTime() : null);
		LocalDateTime created = toLocal(attrs != null ? attrs.getCreateTime() : null);
		this.setLastModifiedDateTime(modified);
		this.setLastAccessDateTime(accessed != null ? accessed : modified);
		this.setCreatedDateTime(created != null ? created : modified);

		this.getMetadata().put(KeyServerId, serverId);
		this.getMetadata().put(KeyRemotePath, remotePath);

		DateTimeFormatter formatter = DISPLAY_DATE_TIME.withLocale(
				ctx != null && ctx.getLocale() != null ? ctx.getLocale() : Locale.getDefault());

		this.getMetadata().put("Name", entryName);
		this.getMetadata().put("Extension", directory ? "" : extensionOf(entryName));
		this.getMetadata().put("Size", directory ? (entryName.equals("..") ? "Up" : "") : humanReadableSize(size));
		this.getMetadata().put("Type", typeLabel(directory, link));
		this.getMetadata().put("Modified", modified == null ? "-" : formatter.format(modified));
		this.getMetadata().put("Permissions", attrs != null ? permissionsString(attrs.getPermissions()) : "-");
		this.getMetadata().put("Full Path", remotePath);
	}

	/**
	 * Rename the display entry (used for the synthetic {@code ..} parent).
	 *
	 * @param name the new display name
	 */
	public void rename(String name) {
		this.name = name;
		this.getMetadata().put("Name", name);
		if (name.equals("..")) {
			this.getMetadata().put("Size", "Up");
		}
	}

	/**
	 * Return the absolute remote path recorded for this entry.
	 *
	 * @return the remote path text
	 */
	public String remotePath() {
		return getMetadata(KeyRemotePath, "");
	}

	/**
	 * Return the owning server profile id recorded for this entry.
	 *
	 * @return the server id
	 */
	public String serverId() {
		return getMetadata(KeyServerId, "");
	}

	@Override
	public InputStream openInputStream(OpenOption... options) throws Exception {
		return Files.newInputStream(getPath(), options);
	}

	/**
	 * Render a POSIX permission bit mask as the familiar {@code rwxr-xr-x}
	 * string (special bits ignored).
	 *
	 * @param permissions the SFTP permission bits
	 * @return the nine-character permission string
	 */
	public static String permissionsString(int permissions) {
		char[] out = new char[9];
		String symbols = "rwx";
		for (int i = 0; i < 9; i++) {
			int bit = 1 << (8 - i);
			out[i] = (permissions & bit) != 0 ? symbols.charAt(i % 3) : '-';
		}
		return new String(out);
	}

	private static String typeLabel(boolean directory, boolean link) {
		if (directory) {
			return link ? "Folder link" : "Folder";
		}
		return link ? "File link" : "File";
	}

	private static LocalDateTime toLocal(FileTime time) {
		if (time == null) {
			return null;
		}
		return time.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
	}

	private static String extensionOf(String name) {
		if (name == null || name.equals("..")) {
			return "";
		}
		int dot = name.lastIndexOf('.');
		if (dot <= 0 || dot == name.length() - 1) {
			return "";
		}
		return name.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	/**
	 * Format a byte count using binary units, matching the panel style of the
	 * other file-panel plugins.
	 *
	 * @param sizeBytes the byte count
	 * @return a human-readable size string
	 */
	public static String humanReadableSize(long sizeBytes) {
		if (sizeBytes < 1024) {
			return sizeBytes + " B";
		}
		double value = sizeBytes;
		final String[] units = { "KB", "MB", "GB", "TB", "PB" };
		int unitIndex = -1;
		while (value >= 1024 && unitIndex < units.length - 1) {
			value /= 1024;
			unitIndex++;
		}
		return String.format(Locale.ROOT, unitIndex == 0 ? "%.0f %s" : "%.1f %s", value, units[unitIndex]);
	}

}
