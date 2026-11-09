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
package dev.nuclr.plugin.core.panel.net.ssh;

import java.io.IOException;

import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.scp.client.ScpClient;

import dev.nuclr.plugin.core.panel.net.remote.NetConnection;

/**
 * Capability of a {@link NetConnection}
 * that can run commands on the server: remote {@code find}, {@code tail -F},
 * the embedded console, {@code net.exec} and SCP transfers. Callers check for
 * it with {@code instanceof} and fall back (or refuse) when it is missing.
 */
public interface ShellAccess {

	/**
	 * Return the shell capability of {@code connection}, or fail when its
	 * protocol has none.
	 *
	 * @param connection the server connection
	 * @return the connection as {@link ShellAccess}
	 * @throws IOException if the connection cannot run commands
	 */
	static ShellAccess require(NetConnection connection) throws IOException {
		if (connection instanceof ShellAccess shell) {
			return shell;
		}
		throw new IOException(connection.config().address() + " does not support running commands.");
	}

	/**
	 * Create an SCP client over the shared session.
	 *
	 * @return the SCP client
	 * @throws IOException if the connection cannot be (re)opened
	 */
	ScpClient scp() throws IOException;

	/**
	 * Open a raw exec channel for a remote command. The caller owns the
	 * channel (open, stream wiring, close). Arguments embedded in
	 * {@code command} must be quoted with {@link ShellEscape#quote}.
	 *
	 * @param command the remote command line
	 * @return the unopened exec channel
	 * @throws IOException if the connection cannot be (re)opened
	 */
	ChannelExec execChannel(String command) throws IOException;

	/**
	 * Open a raw interactive shell channel on the shared session, for the
	 * commander's embedded console (Ctrl+O). The caller owns the channel (pty
	 * setup, stream wiring, open, close); see {@code NetTerminalSession}.
	 * Closing it must never close the session the panel is browsing with.
	 *
	 * @return the unopened shell channel
	 * @throws IOException if the connection cannot be (re)opened
	 */
	ChannelShell shellChannel() throws IOException;

	/**
	 * Run a short remote command and return its stdout. Fails (with stderr in
	 * the exception message) when the command exits non-zero.
	 *
	 * @param command the remote command line (quote arguments with {@link ShellEscape#quote})
	 * @return the command's standard output
	 * @throws IOException if the connection fails or the command exits non-zero
	 */
	String exec(String command) throws IOException;

}
