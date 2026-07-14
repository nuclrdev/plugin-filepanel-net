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
package dev.nuclr.plugin.core.panel.net.ui;

import java.awt.Component;
import java.awt.FocusTraversalPolicy;
import java.awt.KeyboardFocusManager;

import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.KeyStroke;

/**
 * Small dialog-wide behaviors shared across every confirm/prompt dialog in
 * the Net panel.
 */
public final class DialogSupport {

	private DialogSupport() {
	}

	/**
	 * Let Left/Right arrow keys move focus between a dialog's buttons (and any
	 * other non-text controls) the same way Tab/Shift+Tab already do — the
	 * standard "Yes/No" muscle memory from native OS dialogs, which plain
	 * {@link javax.swing.JOptionPane} does not provide on its own.
	 *
	 * <p>Bound at the window level via the focus traversal policy rather than
	 * on specific buttons, so it works regardless of how many options a
	 * particular dialog has. It never fights a focused text field's own
	 * Left/Right caret-movement keys: those are bound on the field itself
	 * ({@code WHEN_FOCUSED}), which Swing always consults before a
	 * {@code WHEN_IN_FOCUSED_WINDOW} binding like this one, so typing in a
	 * field is unaffected — this only fires when focus is on a control (a
	 * button, a checkbox, …) that doesn't already claim the key.
	 *
	 * @param dialog the dialog to enhance
	 */
	public static void installArrowKeyFocusTraversal(JDialog dialog) {
		dialog.getRootPane().registerKeyboardAction(e -> moveFocus(dialog, true),
				KeyStroke.getKeyStroke("RIGHT"), JComponent.WHEN_IN_FOCUSED_WINDOW);
		dialog.getRootPane().registerKeyboardAction(e -> moveFocus(dialog, false),
				KeyStroke.getKeyStroke("LEFT"), JComponent.WHEN_IN_FOCUSED_WINDOW);
	}

	private static void moveFocus(JDialog dialog, boolean forward) {
		Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
		if (focused == null) {
			return;
		}
		FocusTraversalPolicy policy = dialog.getFocusTraversalPolicy();
		if (policy == null) {
			return;
		}
		Component next = forward
				? policy.getComponentAfter(dialog, focused)
				: policy.getComponentBefore(dialog, focused);
		if (next != null) {
			next.requestFocusInWindow();
		}
	}

}
