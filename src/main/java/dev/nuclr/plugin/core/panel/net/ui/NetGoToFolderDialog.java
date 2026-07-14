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

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;

/**
 * F9 "Go to Folder" dialog for the Net panel: a single remote path field,
 * pre-filled with (and initially selecting) the panel's current folder so
 * typing immediately replaces it with a fresh absolute path, while leaving
 * relative edits (e.g. appending a subfolder name) just as easy.
 *
 * <p>Deliberately does not validate or stat the entered path itself: "OK"
 * hands the raw text to the caller, which navigates the panel there the same
 * way any other navigation does — so a bad path surfaces through the panel's
 * existing "not found" error handling instead of duplicating it here.
 */
public final class NetGoToFolderDialog extends JDialog {

	private static final long serialVersionUID = 1L;

	private final JTextField pathField;

	/**
	 * Build and lay out the dialog.
	 *
	 * @param owner       the owning window
	 * @param currentPath the panel's current remote folder (pre-fills the field)
	 * @param onSubmit    invoked with the entered path when the user clicks OK
	 */
	public NetGoToFolderDialog(Window owner, String currentPath, Consumer<String> onSubmit) {
		super(owner, "Go to Folder", ModalityType.APPLICATION_MODAL);

		pathField = new JTextField(currentPath, 32);
		TextFieldSupport.install(pathField);

		var form = new JPanel(new GridBagLayout());
		form.setBorder(BorderFactory.createEmptyBorder(12, 14, 4, 14));
		var c = new GridBagConstraints();
		c.insets = new Insets(4, 4, 4, 4);
		c.anchor = GridBagConstraints.WEST;

		c.gridx = 0;
		c.gridy = 0;
		form.add(new JLabel("Folder:"), c);
		c.gridx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1;
		form.add(pathField, c);

		var okButton = new JButton("OK");
		var cancelButton = new JButton("Cancel");
		okButton.addActionListener(e -> submit(onSubmit));
		cancelButton.addActionListener(e -> dispose());
		pathField.addActionListener(e -> submit(onSubmit));

		getRootPane().setDefaultButton(okButton);
		getRootPane().registerKeyboardAction(e -> dispose(),
				KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);

		var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		buttons.add(okButton);
		buttons.add(cancelButton);

		setLayout(new BorderLayout());
		add(form, BorderLayout.CENTER);
		add(buttons, BorderLayout.SOUTH);

		// A modal dialog still pumps its own event queue once visible, so a
		// windowOpened listener (unlike code after setVisible(true)) reliably runs
		// while the dialog is showing — the standard way to focus a field in a
		// custom modal JDialog.
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowOpened(WindowEvent e) {
				pathField.requestFocusInWindow();
				pathField.selectAll();
			}
		});

		pack();
		setMinimumSize(getSize());
		setLocationRelativeTo(owner);
	}

	private void submit(Consumer<String> onSubmit) {
		String path = pathField.getText().trim();
		if (path.isEmpty()) {
			return;
		}
		dispose();
		onSubmit.accept(path);
	}

}
