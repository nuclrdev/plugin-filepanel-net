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

import java.nio.file.Path;

import javax.swing.JOptionPane;

import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.plugin.core.panel.net.service.NetTransferEngine.Action;

/**
 * Per-file conflict prompt for Net transfers with sticky "… all" choices.
 * One instance covers a single copy/move run; once the user picks
 * "Overwrite All" or "Skip All" the remaining clashes are resolved silently.
 */
final class TransferConflictDialog {

	private final NuclrPluginContext context;

	private Action stickyAction;

	TransferConflictDialog(NuclrPluginContext context) {
		this.context = context;
	}

	/**
	 * Ask the user how to resolve an existing target.
	 *
	 * @param source the source entry
	 * @param target the clashing target
	 * @return the chosen action (never {@code null}; Cancel aborts the run)
	 */
	Action resolve(Path source, Path target) {

		if (stickyAction != null) {
			return stickyAction;
		}

		final String[] options = { "Overwrite", "Overwrite All", "Skip", "Skip All", "Cancel" };
		final int[] choice = new int[] { 4 };

		Alerts.popup(context);
		Alerts.runOnEdtAndWait(() -> choice[0] = JOptionPane.showOptionDialog(
				null,
				"<html>The target already exists:<br/><b>" + target + "</b><br/><br/>Overwrite it?</html>",
				"File exists",
				JOptionPane.DEFAULT_OPTION,
				JOptionPane.WARNING_MESSAGE,
				null,
				options,
				options[2]));

		switch (choice[0]) {
			case 0:
				return Action.OVERWRITE;
			case 1:
				stickyAction = Action.OVERWRITE;
				return stickyAction;
			case 2:
				return Action.SKIP;
			case 3:
				stickyAction = Action.SKIP;
				return stickyAction;
			default:
				return Action.CANCEL;
		}
	}

}
