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

import java.awt.event.ActionEvent;

import javax.swing.AbstractAction;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import javax.swing.text.JTextComponent;
import javax.swing.undo.UndoManager;

import com.formdev.flatlaf.extras.FlatSVGIcon;

/**
 * Editing conveniences shared by every text field in the Net panel's dialogs:
 * undo/redo (keyboard and context menu), and a standard cut/copy/paste/select-all
 * context menu. Plain {@link javax.swing.JTextField}/{@link javax.swing.JPasswordField}
 * carry none of this by default (Cut/Copy/Paste/Select-All keystrokes already work
 * out of the box via the platform's default key bindings; only Undo/Redo and the
 * right-click menu need adding here).
 */
public final class TextFieldSupport {

	private TextFieldSupport() {
	}

	/**
	 * Wire undo/redo (Ctrl+Z, Ctrl+Y / Ctrl+Shift+Z, plus a right-click menu
	 * entry for each) and a Cut/Copy/Paste/Select All context menu onto
	 * {@code field}.
	 *
	 * @param field the text field to enhance
	 */
	public static void install(JTextComponent field) {

		var undoManager = new UndoManager();
		field.getDocument().addUndoableEditListener(undoManager);

		Runnable undo = () -> {
			if (undoManager.canUndo()) {
				undoManager.undo();
			}
		};
		Runnable redo = () -> {
			if (undoManager.canRedo()) {
				undoManager.redo();
			}
		};

		bindKey(field, "control Z", "net-undo", undo);
		bindKey(field, "control Y", "net-redo", redo);
		bindKey(field, "control shift Z", "net-redo-alt", redo);

		var undoItem = new JMenuItem("Undo", menuIcon("undo"));
		undoItem.addActionListener(e -> undo.run());
		var redoItem = new JMenuItem("Redo", menuIcon("redo"));
		redoItem.addActionListener(e -> redo.run());
		var cutItem = new JMenuItem("Cut", menuIcon("cut"));
		cutItem.addActionListener(e -> field.cut());
		var copyItem = new JMenuItem("Copy", menuIcon("copy"));
		copyItem.addActionListener(e -> field.copy());
		var pasteItem = new JMenuItem("Paste", menuIcon("paste"));
		pasteItem.addActionListener(e -> field.paste());
		var selectAllItem = new JMenuItem("Select All", menuIcon("select-all"));
		selectAllItem.addActionListener(e -> field.selectAll());

		var menu = new JPopupMenu();
		menu.add(undoItem);
		menu.add(redoItem);
		menu.addSeparator();
		menu.add(cutItem);
		menu.add(copyItem);
		menu.add(pasteItem);
		menu.addSeparator();
		menu.add(selectAllItem);

		menu.addPopupMenuListener(new PopupMenuListener() {
			@Override
			public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
				undoItem.setEnabled(undoManager.canUndo());
				redoItem.setEnabled(undoManager.canRedo());
				boolean editable = field.isEditable() && field.isEnabled();
				boolean hasSelection = field.getSelectedText() != null;
				cutItem.setEnabled(editable && hasSelection);
				copyItem.setEnabled(hasSelection);
				pasteItem.setEnabled(editable);
				selectAllItem.setEnabled(!field.getText().isEmpty());
			}

			@Override
			public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
			}

			@Override
			public void popupMenuCanceled(PopupMenuEvent e) {
			}
		});

		field.setComponentPopupMenu(menu);
	}

	/**
	 * Build a "Show" checkbox that toggles {@code field} between its normal
	 * masked display and plain text, so the user can verify what they typed or
	 * pasted before submitting. Captures the field's own echo character (rather
	 * than assuming {@code '•'}) so it restores correctly regardless of the
	 * look-and-feel's default.
	 *
	 * @param field the password field to toggle
	 * @return a checkbox wired to {@code field}; add it next to the field
	 */
	public static JCheckBox showPasswordToggle(JPasswordField field) {
		char hiddenEchoChar = field.getEchoChar();
		var checkbox = new JCheckBox("Show");
		checkbox.setToolTipText("Show the typed characters");
		checkbox.addActionListener(e -> field.setEchoChar(checkbox.isSelected() ? (char) 0 : hiddenEchoChar));
		return checkbox;
	}

	/**
	 * Load a 16x16 menu icon bundled with this plugin ({@code icons/<name>.svg})
	 * and recolor it to the current look-and-feel's menu-item foreground, so it
	 * tracks light/dark theme changes — the same pattern the commander itself
	 * uses for its own menu icons.
	 */
	private static FlatSVGIcon menuIcon(String name) {
		var icon = new FlatSVGIcon("icons/" + name + ".svg", 16, 16, TextFieldSupport.class.getClassLoader());
		icon.setColorFilter(new FlatSVGIcon.ColorFilter(color -> UIManager.getColor("MenuItem.foreground")));
		return icon;
	}

	private static void bindKey(JTextComponent field, String keyStroke, String actionKey, Runnable action) {
		field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(keyStroke), actionKey);
		field.getActionMap().put(actionKey, new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent e) {
				action.run();
			}
		});
	}

	/**
	 * Truncate any insert/replace that would push {@code field}'s content past
	 * {@code maxLength} characters, rather than rejecting it outright (so
	 * pasting text longer than the limit keeps as much as fits).
	 *
	 * @param field     the text field to limit
	 * @param maxLength the maximum character count
	 */
	public static void limitLength(JTextComponent field, int maxLength) {
		if (!(field.getDocument() instanceof AbstractDocument document)) {
			return;
		}
		document.setDocumentFilter(new DocumentFilter() {
			@Override
			public void insertString(FilterBypass fb, int offset, String text, AttributeSet attrs)
					throws BadLocationException {
				if (text == null) {
					return;
				}
				String truncated = truncateToFit(fb.getDocument().getLength(), 0, text, maxLength);
				if (!truncated.isEmpty()) {
					super.insertString(fb, offset, truncated, attrs);
				}
			}

			@Override
			public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs)
					throws BadLocationException {
				if (text == null) {
					super.replace(fb, offset, length, text, attrs);
					return;
				}
				String truncated = truncateToFit(fb.getDocument().getLength(), length, text, maxLength);
				super.replace(fb, offset, length, truncated, attrs);
			}
		});
	}

	private static String truncateToFit(int currentLength, int replacedLength, String incoming, int maxLength) {
		int room = maxLength - (currentLength - replacedLength);
		if (room <= 0) {
			return "";
		}
		return incoming.length() <= room ? incoming : incoming.substring(0, room);
	}

}
