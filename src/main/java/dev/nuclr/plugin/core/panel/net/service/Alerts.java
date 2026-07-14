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

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.plugin.core.panel.net.ui.DialogSupport;
import lombok.extern.slf4j.Slf4j;

/**
 * Modal alert and sound helpers for the Net panel. All dialogs marshal to the
 * EDT and block the calling (background) thread until dismissed, mirroring the
 * conventions of the local-filesystem plugin.
 */
@Slf4j
public final class Alerts {

	private Alerts() {
	}

	/**
	 * Show an error dialog (with the error sound).
	 *
	 * @param context plugin context for the sound event bus, may be {@code null}
	 * @param title   dialog title
	 * @param message dialog message (may be HTML)
	 */
	public static void showError(NuclrPluginContext context, String title, String message) {
		error(context);
		runOnEdtAndWait(() -> JOptionPane.showMessageDialog(null, message, title, JOptionPane.ERROR_MESSAGE));
	}

	/**
	 * Show an information dialog.
	 *
	 * @param context plugin context, may be {@code null}
	 * @param title   dialog title
	 * @param message dialog message (may be HTML)
	 */
	public static void showInfo(NuclrPluginContext context, String title, String message) {
		runOnEdtAndWait(() -> JOptionPane.showMessageDialog(null, message, title, JOptionPane.INFORMATION_MESSAGE));
	}

	/**
	 * Show a yes/no confirmation.
	 *
	 * @param context plugin context, may be {@code null}
	 * @param title   dialog title
	 * @param message dialog message (may be HTML)
	 * @return {@code true} when the user confirmed
	 */
	public static boolean confirm(NuclrPluginContext context, String title, String message) {
		var optionPane = new JOptionPane(message, JOptionPane.QUESTION_MESSAGE, JOptionPane.YES_NO_OPTION);
		var result = new boolean[1];
		runOnEdtAndWait(() -> {
			JDialog dialog = optionPane.createDialog(null, title);
			DialogSupport.installArrowKeyFocusTraversal(dialog);
			dialog.setVisible(true);
			dialog.dispose();
			result[0] = optionPane.getValue() instanceof Integer choice && choice == JOptionPane.YES_OPTION;
		});
		return result[0];
	}

	/**
	 * Show a yes/no confirmation for an irreversible action (delete, remove),
	 * with "No" as the default-focused button so pressing Enter (or dismissing
	 * with a stray keystroke) never confirms the destructive choice by accident.
	 *
	 * @param context plugin context, may be {@code null}
	 * @param title   dialog title
	 * @param message dialog message (may be HTML)
	 * @return {@code true} when the user confirmed
	 */
	public static boolean confirmDestructive(NuclrPluginContext context, String title, String message) {
		Object[] options = { "Yes", "No" };
		var optionPane = new JOptionPane(message, JOptionPane.WARNING_MESSAGE, JOptionPane.DEFAULT_OPTION, null,
				options, options[1]);
		var result = new boolean[1];
		runOnEdtAndWait(() -> {
			JDialog dialog = optionPane.createDialog(null, title);
			DialogSupport.installArrowKeyFocusTraversal(dialog);
			dialog.setVisible(true);
			dialog.dispose();
			result[0] = options[0].equals(optionPane.getValue());
		});
		return result[0];
	}

	/**
	 * Run the given UI code on the EDT, waiting for it to finish.
	 *
	 * @param runnable the UI code
	 */
	public static void runOnEdtAndWait(Runnable runnable) {
		if (SwingUtilities.isEventDispatchThread()) {
			runnable.run();
			return;
		}
		try {
			SwingUtilities.invokeAndWait(runnable);
		} catch (Exception e) {
			log.warn("Failed to run dialog on EDT: {}", e.getMessage(), e);
		}
	}

	/** Emit the popup sound. */
	public static void popup(NuclrPluginContext context) {
		sound(context, "PopupSound");
	}

	/** Emit the cancel sound. */
	public static void cancel(NuclrPluginContext context) {
		sound(context, "CancelSound");
	}

	/** Emit the confirmation sound. */
	public static void confirmation(NuclrPluginContext context) {
		sound(context, "ConfirmationSound");
	}

	/** Emit the error sound. */
	public static void error(NuclrPluginContext context) {
		sound(context, "ErrorSound");
	}

	/** Emit the process-complete sound. */
	public static void processComplete(NuclrPluginContext context) {
		sound(context, "ProcessCompleteSound");
	}

	private static void sound(NuclrPluginContext context, String type) {
		try {
			if (context != null && context.getEventBus() != null) {
				context.getEventBus().emit(type);
			}
		} catch (RuntimeException e) {
			log.debug("Failed to emit sound event {}: {}", type, e.getMessage());
		}
	}

}
