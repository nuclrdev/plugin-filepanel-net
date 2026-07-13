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
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import dev.nuclr.plugin.core.panel.net.ssh.NetConnection;
import lombok.extern.slf4j.Slf4j;

/**
 * Non-modal window streaming Alt+F7 Find matches as {@link NetFindService}
 * discovers them. Double-clicking (or Enter on) a result navigates the
 * originating panel there via {@code onNavigate}.
 */
@Slf4j
public final class NetFindResultsWindow extends JDialog {

	private static final long serialVersionUID = 1L;

	private final DefaultListModel<String> model = new DefaultListModel<>();
	private final JLabel statusLabel = new JLabel("Searching…");
	private final AtomicBoolean cancelled = new AtomicBoolean(false);

	/**
	 * Build the window and start the search on a background virtual thread.
	 *
	 * @param owner      the owning window
	 * @param connection the server connection to search over
	 * @param request    the search parameters
	 * @param onNavigate invoked with the absolute remote path of a chosen result
	 */
	public NetFindResultsWindow(Window owner, NetConnection connection, NetFindRequest request,
			Consumer<String> onNavigate) {
		super(owner, "Find results: " + request.namePattern(), ModalityType.MODELESS);

		var list = new JList<>(model);
		list.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2) {
					navigateSelected(list, onNavigate);
				}
			}
		});
		list.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("ENTER"), "navigate");
		list.getActionMap().put("navigate", new javax.swing.AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				navigateSelected(list, onNavigate);
			}
		});

		var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		var stopButton = new JButton("Stop");
		var closeButton = new JButton("Close");
		stopButton.addActionListener(e -> {
			cancelled.set(true);
			stopButton.setEnabled(false);
		});
		closeButton.addActionListener(e -> {
			cancelled.set(true);
			dispose();
		});
		buttons.add(stopButton);
		buttons.add(closeButton);

		var south = new JPanel(new BorderLayout());
		south.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
		south.add(statusLabel, BorderLayout.WEST);
		south.add(buttons, BorderLayout.EAST);

		setLayout(new BorderLayout());
		add(new JScrollPane(list), BorderLayout.CENTER);
		add(south, BorderLayout.SOUTH);

		setSize(680, 420);
		setLocationRelativeTo(owner);

		startSearch(connection, request);
	}

	private void navigateSelected(JList<String> list, Consumer<String> onNavigate) {
		String selected = list.getSelectedValue();
		if (selected != null) {
			onNavigate.accept(selected);
		}
	}

	private void startSearch(NetConnection connection, NetFindRequest request) {
		Thread.ofVirtual().name("net-find").start(() -> {
			int[] count = { 0 };
			try {
				NetFindService.search(connection, request, cancelled, path -> {
					count[0]++;
					SwingUtilities.invokeLater(() -> model.addElement(path));
				});
				finish(cancelled.get() ? "Stopped: " + count[0] + " match(es)" : count[0] + " match(es)");
			} catch (Exception e) {
				log.warn("Find search failed for {}: {}", request.rootPath(), e.getMessage());
				finish("Search failed: " + e.getMessage());
			}
		});
	}

	private void finish(String status) {
		SwingUtilities.invokeLater(() -> statusLabel.setText(status));
	}

}
