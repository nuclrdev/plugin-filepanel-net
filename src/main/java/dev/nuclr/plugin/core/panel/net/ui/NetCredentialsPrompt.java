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

import java.net.SocketAddress;

import javax.swing.JOptionPane;
import javax.swing.JPasswordField;

import dev.nuclr.plugin.core.panel.net.service.Alerts;
import dev.nuclr.plugin.core.panel.net.ssh.ConnectionRegistry;
import dev.nuclr.plugin.core.panel.net.ssh.HostKeyGate;
import dev.nuclr.plugin.core.panel.net.ssh.NetConnection;
import dev.nuclr.plugin.core.panel.net.ssh.ServerConfig;
import lombok.extern.slf4j.Slf4j;

/**
 * Modal secret and host-key prompts backing {@link NetConnection.CredentialsProvider}
 * and {@link HostKeyGate.Prompt}. Successful entries are cached in
 * {@link ConnectionRegistry} for the session lifetime so a reconnect (or a
 * second panel opening the same server) does not re-prompt; a failed attempt
 * drops the cached value so the next try prompts again.
 */
@Slf4j
public final class NetCredentialsPrompt implements NetConnection.CredentialsProvider, HostKeyGate.Prompt {

	/** Shared instance; the provider carries no per-server state of its own. */
	public static final NetCredentialsPrompt INSTANCE = new NetCredentialsPrompt();

	private NetCredentialsPrompt() {
	}

	@Override
	public String password(ServerConfig config, int retryIndex) {

		String cached = ConnectionRegistry.cachedPassword(config.getId());
		if (cached != null && retryIndex == 0) {
			return cached;
		}
		ConnectionRegistry.dropPassword(config.getId());

		String entered = promptSecret(
				(retryIndex == 0 ? "" : "Incorrect password. ") + "Password for " + config.address() + ":",
				"Password");
		if (entered != null) {
			ConnectionRegistry.cachePassword(config.getId(), entered);
		}
		return entered;
	}

	@Override
	public String passphrase(ServerConfig config, int retryIndex) {

		String cached = ConnectionRegistry.cachedPassphrase(config.getId());
		if (cached != null && retryIndex == 0) {
			return cached;
		}
		ConnectionRegistry.dropPassphrase(config.getId());

		String entered = promptSecret(
				(retryIndex == 0 ? "" : "Incorrect passphrase. ") + "Passphrase for key " + config.getPrivateKeyPath()
						+ ":",
				"Private Key Passphrase");
		if (entered != null) {
			ConnectionRegistry.cachePassphrase(config.getId(), entered);
		}
		return entered;
	}

	private String promptSecret(String message, String title) {

		var field = new JPasswordField(20);
		var panel = new javax.swing.JPanel(new java.awt.BorderLayout(0, 8));
		panel.add(new javax.swing.JLabel(message), java.awt.BorderLayout.NORTH);
		panel.add(field, java.awt.BorderLayout.CENTER);

		final int[] choice = new int[1];
		Alerts.runOnEdtAndWait(() -> {
			// showConfirmDialog blocks until dismissed, so requesting focus AFTER it
			// returns would be a no-op; queuing it first means it runs once the modal
			// dialog is up and pumping the event queue, while it's still visible.
			javax.swing.SwingUtilities.invokeLater(field::requestFocusInWindow);
			choice[0] = JOptionPane.showConfirmDialog(null, panel, title,
					JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
		});

		if (choice[0] != JOptionPane.OK_OPTION) {
			return null;
		}
		char[] value = field.getPassword();
		return value.length == 0 ? null : new String(value);
	}

	@Override
	public boolean acceptUnknown(SocketAddress address, String keyType, String fingerprint) {
		final boolean[] accepted = new boolean[1];
		Alerts.runOnEdtAndWait(() -> accepted[0] = JOptionPane.showConfirmDialog(
				null,
				"<html>The authenticity of host <b>" + address + "</b> can't be established.<br/>"
						+ keyType + " key fingerprint:<br/><b>" + fingerprint + "</b><br/><br/>"
						+ "Are you sure you want to continue connecting?</html>",
				"Unknown Host",
				JOptionPane.YES_NO_OPTION,
				JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION);
		return accepted[0];
	}

	@Override
	public boolean acceptChanged(SocketAddress address, String recordedFingerprint, String presentedKeyType,
			String presentedFingerprint) {
		final boolean[] accepted = new boolean[1];
		Alerts.runOnEdtAndWait(() -> accepted[0] = JOptionPane.showConfirmDialog(
				null,
				"<html><b><font color='red'>WARNING: HOST KEY HAS CHANGED!</font></b><br/><br/>"
						+ "The " + presentedKeyType + " key fingerprint presented by <b>" + address
						+ "</b> is:<br/><b>" + presentedFingerprint + "</b><br/><br/>"
						+ "The fingerprint previously recorded was:<br/><b>" + recordedFingerprint + "</b><br/><br/>"
						+ "This <b>could</b> mean someone is intercepting your connection (a man-in-the-middle attack), "
						+ "or it could mean the server's key was legitimately regenerated.<br/><br/>"
						+ "Only continue if you can verify the new fingerprint through another channel.<br/>"
						+ "Continue connecting and update the stored key?</html>",
				"HOST KEY CHANGED",
				JOptionPane.YES_NO_OPTION,
				JOptionPane.ERROR_MESSAGE) == JOptionPane.YES_OPTION);
		return accepted[0];
	}

}
