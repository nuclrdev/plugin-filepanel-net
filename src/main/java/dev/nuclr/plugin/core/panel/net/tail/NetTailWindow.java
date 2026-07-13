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
package dev.nuclr.plugin.core.panel.net.tail;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import org.apache.sshd.client.channel.ChannelExec;

import dev.nuclr.plugin.core.panel.net.ssh.NetConnection;
import dev.nuclr.plugin.core.panel.net.ssh.ShellEscape;
import lombok.extern.slf4j.Slf4j;

/**
 * Live {@code tail -F} viewer for one remote file. Runs {@code tail -F -n 200}
 * over an exec channel on the file's connection and appends each line as it
 * arrives; {@code -F} (capital) makes the remote {@code tail} itself follow
 * across log rotation (reopening the file by name), so no client-side restart
 * is needed for that case.
 *
 * <p>If the underlying session drops, the window reconnects transparently:
 * {@link NetConnection#ensureOpen()} rebuilds the session and a fresh
 * {@code tail -F -n 0} channel resumes from "now" (avoiding a duplicate replay
 * of history already shown).
 */
@Slf4j
public final class NetTailWindow extends JDialog {

	private static final long serialVersionUID = 1L;

	private static final int MAX_LINES = 5_000;
	private static final int INITIAL_LINES = 200;

	private final JTextArea textArea = new JTextArea();
	private final AtomicBoolean stopped = new AtomicBoolean(false);

	/**
	 * Build the window and start following the given remote file.
	 *
	 * @param owner      the owning window
	 * @param connection the file's server connection
	 * @param remotePath the absolute remote path to follow
	 */
	public NetTailWindow(Window owner, NetConnection connection, String remotePath) {
		super(owner, "Tail: " + remotePath, ModalityType.MODELESS);

		textArea.setEditable(false);
		textArea.setLineWrap(false);
		textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		textArea.setBackground(Color.BLACK);
		textArea.setForeground(new Color(0xE0, 0xE0, 0xE0));

		var statusLabel = new JLabel("Connecting…");
		var closeButton = new JButton("Close");
		closeButton.addActionListener(e -> {
			stopped.set(true);
			dispose();
		});

		var south = new JPanel(new BorderLayout());
		south.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
		south.add(statusLabel, BorderLayout.WEST);
		var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		buttons.add(closeButton);
		south.add(buttons, BorderLayout.EAST);

		setLayout(new BorderLayout());
		add(new JScrollPane(textArea), BorderLayout.CENTER);
		add(south, BorderLayout.SOUTH);
		setSize(900, 560);
		setLocationRelativeTo(owner);

		addWindowListener(new java.awt.event.WindowAdapter() {
			@Override
			public void windowClosing(java.awt.event.WindowEvent e) {
				stopped.set(true);
			}
		});

		Thread.ofVirtual().name("net-tail").start(() -> run(connection, remotePath, statusLabel));
	}

	private void run(NetConnection connection, String remotePath, JLabel statusLabel) {

		boolean first = true;

		while (!stopped.get()) {
			try {
				connection.ensureOpen();

				String command = "tail -F -n " + (first ? INITIAL_LINES : 0) + " " + ShellEscape.quote(remotePath);
				first = false;

				setStatus(statusLabel, "Following " + remotePath);

				try (ChannelExec channel = connection.execChannel(command)) {
					channel.open().verify(java.time.Duration.ofSeconds(15));

					try (var reader = new BufferedReader(
							new InputStreamReader(channel.getInvertedOut(), StandardCharsets.UTF_8))) {

						String line;
						while (!stopped.get() && (line = reader.readLine()) != null) {
							appendLine(line);
						}
					}
				}

				if (stopped.get()) {
					return;
				}

				setStatus(statusLabel, "Connection lost; reconnecting…");
				Thread.sleep(2_000);

			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (IOException e) {
				if (stopped.get()) {
					return;
				}
				log.info("Tail of {} interrupted: {}; retrying", remotePath, e.getMessage());
				setStatus(statusLabel, "Connection lost (" + e.getMessage() + "); reconnecting…");
				try {
					Thread.sleep(2_000);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
	}

	private void setStatus(JLabel label, String text) {
		SwingUtilities.invokeLater(() -> label.setText(text));
	}

	private void appendLine(String line) {
		SwingUtilities.invokeLater(() -> {
			textArea.append(line);
			textArea.append("\n");
			trimIfNeeded();
			JScrollBar vertical = ((JScrollPane) textArea.getParent().getParent()).getVerticalScrollBar();
			vertical.setValue(vertical.getMaximum());
		});
	}

	private void trimIfNeeded() {
		int excess = textArea.getLineCount() - MAX_LINES;
		if (excess <= 0) {
			return;
		}
		try {
			int end = textArea.getLineEndOffset(excess - 1);
			textArea.replaceRange("", 0, end);
		} catch (javax.swing.text.BadLocationException ignored) {
			// benign race with concurrent appends; skip this trim pass
		}
	}

}
