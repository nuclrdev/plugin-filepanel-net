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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.net.ssh.NetConnection;
import lombok.extern.slf4j.Slf4j;

/**
 * F5 copy <b>into</b> the Net panel's current remote folder. Sources may come
 * from any panel: local files upload over SCP, entries from other virtual
 * filesystems (another server, an archive) are streamed. Owns the progress and
 * conflict dialogs; the actual work is done by {@link NetTransferEngine}.
 */
@Slf4j
public class NetCopyService {

	private static final String DialogTitle = "Copy";

	/**
	 * Copy the selection into the destination remote folder, driving the
	 * confirmation, progress and conflict dialogs.
	 *
	 * @param connection        destination server connection
	 * @param destinationFolder the receiving panel's current remote folder
	 * @param selectedResources marked resources to copy; used when non-empty
	 * @param focusedResource   the cursor item, used when nothing is marked
	 * @param context           plugin context
	 * @return {@code true} when the copy ran to completion
	 */
	public boolean copy(NetConnection connection, NuclrResource destinationFolder,
			List<NuclrResource> selectedResources, NuclrResource focusedResource, NuclrPluginContext context) {

		Path destination = destinationFolder != null ? destinationFolder.getPath() : null;
		if (destination == null) {
			Alerts.showError(context, DialogTitle, "The destination is not a remote folder.");
			return false;
		}

		List<Path> sources = collectSources(selectedResources, focusedResource);
		if (sources.isEmpty()) {
			Alerts.showError(context, DialogTitle, "There is nothing to copy.");
			return false;
		}

		if (!Alerts.confirm(context, DialogTitle,
				"<html>Copy <b>" + header(sources) + "</b> to<br/><b>" + destinationFolder.getFullPath()
						+ "</b> ?</html>")) {
			Alerts.cancel(context);
			return false;
		}

		var conflictDialog = new TransferConflictDialog(context);
		var completed = new AtomicBoolean(false);

		NetProgressDialog.run(DialogTitle, callback -> {
			var engine = new NetTransferEngine(connection, callback, conflictDialog::resolve, (source, e) -> {
				Alerts.showError(context, DialogTitle,
						"<html>Could not copy <b>" + source + "</b><br/>" + e.getMessage() + "</html>");
				return true; // skip and continue
			});
			completed.set(engine.copy(sources, destination));
		}, context);

		if (completed.get()) {
			Alerts.processComplete(context);
		}
		return completed.get();
	}

	/** Resolve the entries to act on: marked selection if present, otherwise the cursor item. */
	static List<Path> collectSources(List<NuclrResource> selectedResources, NuclrResource focusedResource) {

		List<NuclrResource> chosen = new ArrayList<>();
		if (selectedResources != null && !selectedResources.isEmpty()) {
			chosen.addAll(selectedResources);
		} else if (focusedResource != null) {
			chosen.add(focusedResource);
		}

		List<Path> paths = new ArrayList<>();
		for (NuclrResource resource : chosen) {
			if (resource == null || resource.getPath() == null) {
				continue;
			}
			if ("..".equals(resource.getName())) {
				continue; // never copy the parent navigation entry
			}
			paths.add(resource.getPath());
		}
		return paths;
	}

	static String header(List<Path> sources) {
		if (sources.size() == 1) {
			Path name = sources.get(0).getFileName();
			return name != null ? name.toString() : sources.get(0).toString();
		}
		return sources.size() + " items";
	}

	/**
	 * Return {@code true} when every source path lives on the same filesystem
	 * as {@code destination} (allowing a fast server-side rename for moves).
	 *
	 * @param sources     the source paths
	 * @param destination the destination directory
	 * @return {@code true} when all filesystems match
	 */
	static boolean sameFileSystem(List<Path> sources, Path destination) {
		for (Path source : sources) {
			if (source.getFileSystem() != destination.getFileSystem()) {
				return false;
			}
		}
		return !sources.isEmpty() && Files.exists(destination);
	}

}
