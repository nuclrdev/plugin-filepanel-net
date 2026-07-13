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

import javax.swing.JOptionPane;

import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import lombok.extern.slf4j.Slf4j;

/**
 * F7 make-folder and Shift+F4 create-file for the current remote directory.
 * Prompts for a name and creates the entry over SFTP.
 */
@Slf4j
public class NetMakeFolderService {

	/**
	 * Prompt for a folder name and create it in the given remote folder.
	 *
	 * @param currentFolder the panel's current remote folder
	 * @param context       plugin context
	 * @return the created path, or {@code null} when cancelled or failed
	 */
	public Path makeFolder(NuclrResource currentFolder, NuclrPluginContext context) {
		return create(currentFolder, context, "Make Folder", "Folder name:", true);
	}

	/**
	 * Prompt for a file name and create an empty file in the given remote folder.
	 *
	 * @param currentFolder the panel's current remote folder
	 * @param context       plugin context
	 * @return the created path, or {@code null} when cancelled or failed
	 */
	public Path makeFile(NuclrResource currentFolder, NuclrPluginContext context) {
		return create(currentFolder, context, "Create File", "File name:", false);
	}

	private Path create(NuclrResource currentFolder, NuclrPluginContext context, String title, String prompt,
			boolean folder) {

		Path base = currentFolder != null ? currentFolder.getPath() : null;
		if (base == null) {
			return null;
		}

		final String[] entered = new String[1];
		Alerts.popup(context);
		Alerts.runOnEdtAndWait(() -> entered[0] = JOptionPane.showInputDialog(null, prompt, title,
				JOptionPane.PLAIN_MESSAGE));

		String name = entered[0];
		if (name == null || name.isBlank()) {
			return null;
		}
		name = name.trim();
		if (name.contains("/") || name.contains("\0") || name.equals(".") || name.equals("..")) {
			Alerts.showError(context, title, "Invalid name: " + name);
			return null;
		}

		Path target = base.resolve(name);
		try {
			if (Files.exists(target)) {
				Alerts.showError(context, title, "<html>Already exists:<br/><b>" + target + "</b></html>");
				return null;
			}
			if (folder) {
				Files.createDirectory(target);
			} else {
				Files.createFile(target);
			}
			Alerts.confirmation(context);
			return target;
		} catch (IOException e) {
			log.warn("Could not create [{}]: {}", target, e.getMessage());
			Alerts.showError(context, title,
					"<html>Could not create <b>" + target + "</b><br/>" + e.getMessage() + "</html>");
			return null;
		}
	}

}
