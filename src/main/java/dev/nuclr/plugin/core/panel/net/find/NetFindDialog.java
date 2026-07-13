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
package dev.nuclr.plugin.core.panel.net.find;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;

/**
 * Alt+F7 Find dialog for the Net panel: a filename pattern (glob or regex),
 * case sensitivity and the search root (fixed to the panel's current remote
 * folder, shown for context). Non-modal so the panel stays usable while a
 * search runs in {@link NetFindResultsWindow}.
 */
public final class NetFindDialog extends JDialog {

	private static final long serialVersionUID = 1L;

	private final JTextField patternField = new JTextField("*", 24);
	private final JRadioButton globButton = new JRadioButton("Glob (* ?)", true);
	private final JRadioButton regexButton = new JRadioButton("Regular expression");
	private final JCheckBox caseSensitiveBox = new JCheckBox("Case sensitive");

	/**
	 * Build and lay out the dialog.
	 *
	 * @param owner    the owning window
	 * @param rootPath the remote folder the search starts from (display only)
	 * @param onSubmit invoked with the built request when the user clicks Find
	 */
	public NetFindDialog(Window owner, String rootPath, Consumer<NetFindRequest> onSubmit) {
		super(owner, "Find File", ModalityType.MODELESS);

		var group = new ButtonGroup();
		group.add(globButton);
		group.add(regexButton);

		var form = new JPanel(new GridBagLayout());
		form.setBorder(BorderFactory.createEmptyBorder(12, 14, 4, 14));
		var c = new GridBagConstraints();
		c.insets = new Insets(4, 4, 4, 4);
		c.anchor = GridBagConstraints.WEST;

		c.gridx = 0;
		c.gridy = 0;
		form.add(new JLabel("Search in:"), c);
		c.gridx = 1;
		c.gridwidth = 2;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1;
		form.add(new JLabel(rootPath), c);

		c.gridx = 0;
		c.gridy = 1;
		c.gridwidth = 1;
		c.fill = GridBagConstraints.NONE;
		c.weightx = 0;
		form.add(new JLabel("File name:"), c);
		c.gridx = 1;
		c.gridwidth = 2;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1;
		form.add(patternField, c);

		c.gridx = 1;
		c.gridy = 2;
		c.gridwidth = 1;
		c.fill = GridBagConstraints.NONE;
		c.weightx = 0;
		form.add(globButton, c);
		c.gridx = 2;
		form.add(regexButton, c);

		c.gridx = 1;
		c.gridy = 3;
		form.add(caseSensitiveBox, c);

		var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		var findButton = new JButton("Find");
		var cancelButton = new JButton("Cancel");
		buttons.add(findButton);
		buttons.add(cancelButton);

		getRootPane().setDefaultButton(findButton);
		findButton.addActionListener(e -> submit(rootPath, onSubmit));
		cancelButton.addActionListener(e -> dispose());
		patternField.addActionListener(e -> submit(rootPath, onSubmit));

		setLayout(new BorderLayout());
		add(form, BorderLayout.CENTER);
		add(buttons, BorderLayout.SOUTH);

		pack();
		setMinimumSize(getSize());
		setLocationRelativeTo(owner);
	}

	private void submit(String rootPath, Consumer<NetFindRequest> onSubmit) {
		String pattern = patternField.getText().trim();
		if (pattern.isEmpty()) {
			return;
		}
		var mode = globButton.isSelected() ? FindMatchMode.GLOB : FindMatchMode.REGEX;
		try {
			var request = new NetFindRequest(rootPath, pattern, mode, caseSensitiveBox.isSelected());
			dispose();
			onSubmit.accept(request);
		} catch (IllegalArgumentException invalid) {
			patternField.requestFocusInWindow();
		}
	}

}
