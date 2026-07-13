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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JOptionPane;

import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.net.ssh.NetConnection;
import lombok.extern.slf4j.Slf4j;

/**
 * F6 move/rename for the Net panel.
 *
 * <ul>
 *   <li><b>Move into</b> the panel's current remote folder: a fast SFTP rename
 *       when the sources already live on the same server filesystem, otherwise
 *       a transfer (SCP/stream) followed by deletion of the sources.</li>
 *   <li><b>Rename in place</b> (move-to-self): prompts for the new name of the
 *       focused entry and renames it via SFTP.</li>
 * </ul>
 */
@Slf4j
public class NetMoveService {

	private static final String DialogTitle = "Move";

	/**
	 * Move the selection into the destination remote folder.
	 *
	 * @param connection        destination server connection
	 * @param destinationFolder the receiving panel's current remote folder
	 * @param selectedResources marked resources; used when non-empty
	 * @param focusedResource   the cursor item, used when nothing is marked
	 * @param context           plugin context
	 * @return {@code true} when the move ran to completion
	 */
	public boolean move(NetConnection connection, NuclrResource destinationFolder,
			List<NuclrResource> selectedResources, NuclrResource focusedResource, NuclrPluginContext context) {

		Path destination = destinationFolder != null ? destinationFolder.getPath() : null;
		if (destination == null) {
			Alerts.showError(context, DialogTitle, "The destination is not a remote folder.");
			return false;
		}

		List<Path> sources = NetCopyService.collectSources(selectedResources, focusedResource);
		if (sources.isEmpty()) {
			Alerts.showError(context, DialogTitle, "There is nothing to move.");
			return false;
		}

		if (!Alerts.confirm(context, DialogTitle,
				"<html>Move <b>" + NetCopyService.header(sources) + "</b> to<br/><b>"
						+ destinationFolder.getFullPath() + "</b> ?</html>")) {
			Alerts.cancel(context);
			return false;
		}

		if (NetCopyService.sameFileSystem(sources, destination)) {
			return renameWithin(sources, destination, context);
		}
		return transferAndDelete(connection, sources, destination, context);
	}

	/** Same server: per-entry SFTP rename, no data transfer. */
	private boolean renameWithin(List<Path> sources, Path destination, NuclrPluginContext context) {

		var completed = new AtomicBoolean(true);

		NetProgressDialog.run(DialogTitle, callback -> {
			int done = 0;
			for (Path source : sources) {
				if (callback.isCancelled()) {
					completed.set(false);
					return;
				}
				Path target = destination.resolve(fileName(source));
				callback.onStart("Moving " + fileName(source));
				try {
					if (source.equals(target)) {
						continue; // moving into its own folder is a no-op
					}
					Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
					callback.onProgress(++done, sources.size());
					callback.onComplete();
				} catch (IOException e) {
					completed.set(false);
					Alerts.showError(context, DialogTitle,
							"<html>Could not move <b>" + source + "</b><br/>" + e.getMessage() + "</html>");
				}
			}
		}, context);

		if (completed.get()) {
			Alerts.processComplete(context);
		}
		return completed.get();
	}

	/** Cross-filesystem: copy through the transfer engine, then delete the sources. */
	private boolean transferAndDelete(NetConnection connection, List<Path> sources, Path destination,
			NuclrPluginContext context) {

		var conflictDialog = new TransferConflictDialog(context);
		var copied = new AtomicBoolean(false);

		NetProgressDialog.run(DialogTitle, callback -> {
			var engine = new NetTransferEngine(connection, callback, conflictDialog::resolve, (source, e) -> {
				Alerts.showError(context, DialogTitle,
						"<html>Could not move <b>" + source + "</b><br/>" + e.getMessage() + "</html>");
				return false; // abort: do not delete sources after a partial copy
			});
			copied.set(engine.copy(sources, destination));

			if (copied.get() && !callback.isCancelled()) {
				for (Path source : sources) {
					callback.onStart("Removing " + fileName(source));
					try {
						NetDeleteService.deleteRecursively(source, callback);
					} catch (IOException e) {
						log.warn("Moved but could not remove source [{}]: {}", source, e.getMessage());
						Alerts.showError(context, DialogTitle, "<html>Copied, but could not remove the source <b>"
								+ source + "</b><br/>" + e.getMessage() + "</html>");
					}
				}
			}
		}, context);

		if (copied.get()) {
			Alerts.processComplete(context);
		}
		return copied.get();
	}

	/**
	 * Rename the focused entry in place (F6 with both panels on the same
	 * location, FAR-style Rename/Move).
	 *
	 * @param focusedResource the entry under the cursor
	 * @param context         plugin context
	 * @return {@code true} when a rename happened
	 */
	public boolean renameInPlace(NuclrResource focusedResource, NuclrPluginContext context) {

		if (focusedResource == null || focusedResource.getPath() == null
				|| "..".equals(focusedResource.getName())) {
			return false;
		}

		Path source = focusedResource.getPath();

		final String[] entered = new String[1];
		Alerts.popup(context);
		Alerts.runOnEdtAndWait(() -> entered[0] = (String) JOptionPane.showInputDialog(
				null,
				"New name:",
				"Rename",
				JOptionPane.PLAIN_MESSAGE,
				null,
				null,
				focusedResource.getName()));

		String newName = entered[0];
		if (newName == null || newName.isBlank() || newName.equals(focusedResource.getName())) {
			return false;
		}
		if (newName.contains("/") || newName.contains("\0")) {
			Alerts.showError(context, "Rename", "The name must not contain '/' characters.");
			return false;
		}

		Path target = source.resolveSibling(newName);
		var renamed = new AtomicBoolean(false);

		NetProgressDialog.run("Rename", callback -> {
			callback.onStart("Renaming " + focusedResource.getName());
			try {
				if (Files.exists(target)) {
					Alerts.showError(context, "Rename",
							"<html>The target already exists:<br/><b>" + target + "</b></html>");
					return;
				}
				Files.move(source, target);
				renamed.set(true);
			} catch (IOException e) {
				Alerts.showError(context, "Rename",
						"<html>Could not rename <b>" + source + "</b><br/>" + e.getMessage() + "</html>");
			}
		}, context);

		if (renamed.get()) {
			Alerts.confirmation(context);
		}
		return renamed.get();
	}

	private static String fileName(Path path) {
		Path name = path.getFileName();
		return name != null ? name.toString() : path.toString();
	}

}
