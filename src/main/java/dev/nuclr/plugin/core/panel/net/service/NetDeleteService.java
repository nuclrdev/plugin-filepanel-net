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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import lombok.extern.slf4j.Slf4j;

/**
 * F8 delete for remote entries. Remote deletion is always permanent (there is
 * no trash over SFTP), so the confirmation says so explicitly. Directories are
 * removed depth-first over SFTP with per-entry progress and cancellation;
 * symbolic links are removed as links, never followed.
 */
@Slf4j
public class NetDeleteService {

	private static final String DialogTitle = "Delete";

	private static final int MAX_LISTED = 10;

	/**
	 * Confirm and delete the given resources.
	 *
	 * @param sources the entries to delete
	 * @param context plugin context
	 * @return {@code true} when everything selected was deleted
	 */
	public boolean delete(List<NuclrResource> sources, NuclrPluginContext context) {

		var paths = NetCopyService.collectSources(sources, null);
		if (paths.isEmpty()) {
			return false;
		}

		if (!confirm(paths, context)) {
			Alerts.cancel(context);
			return false;
		}

		var completed = new AtomicBoolean(true);

		NetProgressDialog.run(DialogTitle, callback -> {
			int done = 0;
			for (Path path : paths) {
				if (callback.isCancelled()) {
					completed.set(false);
					return;
				}
				try {
					deleteRecursively(path, callback);
					callback.onProgress(++done, paths.size());
				} catch (IOException e) {
					completed.set(false);
					Alerts.showError(context, DialogTitle,
							"<html>Could not delete <b>" + path + "</b><br/>" + e.getMessage() + "</html>");
				}
			}
		}, context);

		if (completed.get()) {
			Alerts.processComplete(context);
		}
		return completed.get();
	}

	private boolean confirm(List<Path> paths, NuclrPluginContext context) {

		var sb = new StringBuilder("<html>Permanently delete from the remote server:<br/><br/>");
		int listed = 0;
		for (Path path : paths) {
			if (listed++ == MAX_LISTED) {
				sb.append("… and ").append(paths.size() - MAX_LISTED).append(" more<br/>");
				break;
			}
			sb.append("<b>").append(path).append("</b><br/>");
		}
		sb.append("<br/>This cannot be undone.</html>");

		Alerts.popup(context);
		return Alerts.confirmDestructive(context, DialogTitle, sb.toString());
	}

	/**
	 * Delete one entry, descending into directories depth-first. Symbolic
	 * links are removed without following them.
	 *
	 * @param path     the entry to delete
	 * @param callback progress/cancellation bridge; checked before each entry
	 * @throws IOException if a deletion fails
	 */
	static void deleteRecursively(Path path, NuclrPluginCallback callback) throws IOException {

		if (callback != null && callback.isCancelled()) {
			return;
		}

		boolean directory;
		try {
			directory = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class,
					LinkOption.NOFOLLOW_LINKS).isDirectory();
		} catch (NoSuchFileException gone) {
			return;
		}

		if (directory) {
			try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
				for (Path child : children) {
					if (callback != null && callback.isCancelled()) {
						return;
					}
					deleteRecursively(child, callback);
				}
			}
		}

		if (callback != null) {
			callback.onStart("Deleting " + (path.getFileName() != null ? path.getFileName() : path));
			if (callback.isCancelled()) {
				return;
			}
		}
		Files.deleteIfExists(path);
	}

}
