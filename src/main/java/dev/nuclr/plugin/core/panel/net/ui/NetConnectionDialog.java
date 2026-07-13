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

import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.UIManager;

import dev.nuclr.plugin.core.panel.net.ssh.KeyLoader;
import dev.nuclr.plugin.core.panel.net.ssh.ServerConfig;

/**
 * Create/edit dialog for one {@link ServerConfig} server profile.
 *
 * <p>Collects hostname/port, username, password-or-key authentication
 * (OpenSSH, PEM/PKCS#8 and PuTTY PPK are all accepted — {@link KeyLoader}
 * detects the format automatically) with an optional passphrase, and the
 * initial remote directory. The password/passphrase are intentionally not
 * fields of {@link ServerConfig}: when set here they are only handed to the
 * caller for immediate in-memory caching, never persisted to disk.
 */
public final class NetConnectionDialog extends JDialog {

	private static final long serialVersionUID = 1L;

	private static final int NAME_MAX_LENGTH = 50;

	/** The edited profile plus any secret entered alongside it (never persisted). */
	public record Result(ServerConfig config, String password, String passphrase) {
	}

	private final JTextField nameField = new JTextField(20);
	private final JTextField hostField = new JTextField(20);
	private final JTextField portField = new JTextField("22", 5);
	private final JTextField userField = new JTextField(20);
	private final JRadioButton passwordAuth = new JRadioButton("Password", true);
	private final JRadioButton keyAuth = new JRadioButton("Private key");
	private final JPasswordField passwordField = new JPasswordField(20);
	private final JTextField keyPathField = new JTextField(20);
	private final JButton browseKeyButton = new JButton("Browse…");
	private final JPasswordField passphraseField = new JPasswordField(20);
	private final JTextField initialPathField = new JTextField(20);
	private final JCheckBox showPasswordBox = new JCheckBox("Show");

	private Result result;

	/**
	 * Build the dialog pre-filled from an existing profile (or defaults for a
	 * new one).
	 *
	 * @param owner  the owning window
	 * @param title  dialog title ("New Server" / "Edit Server")
	 * @param config the profile to edit; its own field values seed the form
	 */
	public NetConnectionDialog(Window owner, String title, ServerConfig config) {
		super(owner, title, ModalityType.APPLICATION_MODAL);

		nameField.setText(config.getName());
		hostField.setText(config.getHost());
		portField.setText(String.valueOf(config.getPort() > 0 ? config.getPort() : 22));
		userField.setText(config.getUsername());
		keyPathField.setText(config.getPrivateKeyPath());
		initialPathField.setText(config.getInitialPath());

		for (var field : java.util.List.of(nameField, hostField, portField, userField, passwordField, keyPathField,
				passphraseField, initialPathField)) {
			TextFieldSupport.install(field);
		}
		TextFieldSupport.limitLength(nameField, NAME_MAX_LENGTH);

		boolean useKey = config.usesKey();
		passwordAuth.setSelected(!useKey);
		keyAuth.setSelected(useKey);
		var group = new ButtonGroup();
		group.add(passwordAuth);
		group.add(keyAuth);

		setLayout(new GridBagLayout());
		var c = new GridBagConstraints();
		c.insets = new Insets(4, 6, 4, 6);
		c.anchor = GridBagConstraints.WEST;
		int row = 0;

		row = addRow(c, row, "Name (optional):", nameField);
		row = addRow(c, row, "Host:", hostField);
		row = addRow(c, row, "Port:", portField);
		row = addRow(c, row, "Username:", userField);

		c.gridx = 0;
		c.gridy = row;
		c.gridwidth = 3;
		var authPanel = new JPanel();
		authPanel.add(passwordAuth);
		authPanel.add(keyAuth);
		add(authPanel, c);
		row++;
		c.gridwidth = 1;

		row = addRow(c, row, "Password:", withTrailing(passwordField, showPasswordBox));
		showPasswordBox.addActionListener(e -> passwordField
				.setEchoChar(showPasswordBox.isSelected() ? (char) 0 : '•'));

		row = addRow(c, row, "Key file:", withTrailing(keyPathField, browseKeyButton));
		browseKeyButton.addActionListener(e -> browseForKey());

		row = addRow(c, row, "Key passphrase:", passphraseField);
		row = addRow(c, row, "Initial directory:", initialPathField);

		passwordAuth.addActionListener(e -> updateAuthEnablement());
		keyAuth.addActionListener(e -> updateAuthEnablement());
		updateAuthEnablement();

		var okButton = new JButton("OK");
		var cancelButton = new JButton("Cancel");
		okButton.addActionListener(e -> submit(config));
		cancelButton.addActionListener(e -> dispose());
		getRootPane().setDefaultButton(okButton);

		// Right-aligned, per the platform's own affirmative/dismiss button order
		// where the look-and-feel exposes one (the same UIManager key JOptionPane
		// itself consults for Yes/No ordering; e.g. FlatLaf sets it on macOS).
		var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		if (UIManager.getBoolean("OptionPane.isYesLast")) {
			buttons.add(cancelButton);
			buttons.add(okButton);
		} else {
			buttons.add(okButton);
			buttons.add(cancelButton);
		}
		c.gridx = 0;
		c.gridy = row;
		c.gridwidth = 3;
		add(buttons, c);

		getRootPane().registerKeyboardAction(e -> dispose(),
				KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);

		getRootPane().setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		pack();
		setMinimumSize(getSize());
		setLocationRelativeTo(owner);
	}

	private int addRow(GridBagConstraints c, int row, String label, java.awt.Component field) {
		c.gridx = 0;
		c.gridy = row;
		c.gridwidth = 1;
		add(new JLabel(label), c);
		c.gridx = 1;
		c.gridwidth = 2;
		add(field, c);
		return row + 1;
	}

	private JPanel withTrailing(java.awt.Component main, java.awt.Component trailing) {
		var panel = new JPanel(new java.awt.BorderLayout(4, 0));
		panel.add(main, java.awt.BorderLayout.CENTER);
		panel.add(trailing, java.awt.BorderLayout.EAST);
		return panel;
	}

	private void updateAuthEnablement() {
		boolean useKey = keyAuth.isSelected();
		passwordField.setEnabled(!useKey);
		keyPathField.setEnabled(useKey);
		browseKeyButton.setEnabled(useKey);
		passphraseField.setEnabled(useKey);
	}

	private void browseForKey() {
		var chooser = new JFileChooser();
		chooser.setDialogTitle("Select private key file");
		if (!keyPathField.getText().isBlank()) {
			File current = new File(keyPathField.getText());
			if (current.getParentFile() != null) {
				chooser.setCurrentDirectory(current.getParentFile());
			}
		}
		if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
			keyPathField.setText(chooser.getSelectedFile().getAbsolutePath());
		}
	}

	private void submit(ServerConfig original) {

		String host = hostField.getText().trim();
		String user = userField.getText().trim();

		if (host.isBlank()) {
			showValidationError("Host is required.");
			return;
		}
		if (user.isBlank()) {
			showValidationError("Username is required.");
			return;
		}

		int port;
		try {
			port = Integer.parseInt(portField.getText().trim());
			if (port < 1 || port > 65535) {
				throw new NumberFormatException();
			}
		} catch (NumberFormatException e) {
			showValidationError("Port must be a number between 1 and 65535.");
			return;
		}

		String password = null;
		String passphrase = null;

		var config = new ServerConfig();
		config.setId(original.getId());
		config.setName(nameField.getText().trim());
		config.setHost(host);
		config.setPort(port);
		config.setUsername(user);
		config.setInitialPath(initialPathField.getText().trim());

		if (keyAuth.isSelected()) {
			String keyPath = keyPathField.getText().trim();
			if (keyPath.isBlank()) {
				showValidationError("A private key file is required for key authentication.");
				return;
			}
			Path path = Path.of(keyPath);
			if (!Files.isRegularFile(path)) {
				showValidationError("The private key file does not exist:\n" + keyPath);
				return;
			}
			config.setAuthMethod(ServerConfig.AuthMethod.KEY);
			config.setPrivateKeyPath(keyPath);
			char[] enteredPassphrase = passphraseField.getPassword();
			if (enteredPassphrase.length > 0) {
				passphrase = new String(enteredPassphrase);
			}
		} else {
			config.setAuthMethod(ServerConfig.AuthMethod.PASSWORD);
			char[] enteredPassword = passwordField.getPassword();
			if (enteredPassword.length > 0) {
				password = new String(enteredPassword);
			}
		}

		this.result = new Result(config, password, passphrase);
		dispose();
	}

	private void showValidationError(String message) {
		JOptionPane.showMessageDialog(this, message, "Invalid Server Profile", JOptionPane.ERROR_MESSAGE);
	}

	/**
	 * Show the dialog modally and return the entered profile, or {@code null}
	 * when the user cancelled.
	 *
	 * @param owner  the owning window
	 * @param title  dialog title
	 * @param config seed values (a fresh {@link ServerConfig} for "New Server")
	 * @return the result, or {@code null} if cancelled
	 */
	public static Result show(Window owner, String title, ServerConfig config) {
		var dialog = new NetConnectionDialog(owner, title, config);
		dialog.setVisible(true);
		return dialog.result;
	}

}
