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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;

import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.channel.ClientChannelEvent;

import dev.nuclr.platform.plugin.NuclrTerminalSession;
import lombok.extern.slf4j.Slf4j;

/**
 * An interactive shell on the remote server, rendered in the commander's embedded console
 * (Ctrl+O) — so the console shows a shell <em>where the panel already is</em> rather than on
 * the local machine.
 *
 * <p>The shell is an SSH channel on the very session {@link NetConnection} already keeps open for
 * SFTP listings and SCP transfers, so opening one costs no new connection and no second
 * authentication. It also means {@link #close()} must close the channel and nothing else: the
 * session underneath belongs to the connection, and the panel (or the other panel side) is still
 * browsing with it.
 */
@Slf4j
public final class NetTerminalSession implements NuclrTerminalSession {

	private static final Duration OPEN_TIMEOUT = Duration.ofSeconds(15);
	private static final String TERM = "xterm-256color";

	/**
	 * Marker prefixing the working directory the shell reports in its terminal title (OSC 0). The
	 * commander reads that title back when the console closes and hands it to
	 * {@code NetFilePanelPlugin}, which follows the shell only when this marker is present — so a
	 * shell that never ran our reporting (dash, or one that sets its own title) leaves the panel
	 * where it is rather than sending it to a decorated, non-path title.
	 */
	public static final String CWD_TITLE_PREFIX = "NUCLRCWD:";

	private final ChannelShell channel;
	private final String name;

	private NetTerminalSession(ChannelShell channel, String name) {
		this.channel = channel;
		this.name = name;
	}

	/**
	 * Open a shell on {@code connection} and put it in {@code remoteDir}.
	 *
	 * @param connection the live connection whose session carries the channel
	 * @param remoteDir  the remote directory the shell should start in
	 * @param columns    initial terminal width in character cells
	 * @param rows       initial terminal height in character cells
	 * @return the running shell
	 * @throws IOException if the channel cannot be opened
	 */
	public static NetTerminalSession open(NetConnection connection, String remoteDir, int columns, int rows)
			throws IOException {

		ChannelShell channel = connection.shellChannel();
		channel.setUsePty(true);
		channel.setPtyType(TERM);
		channel.setPtyColumns(columns);
		channel.setPtyLines(rows);

		// Fold stderr into stdout: a real pty merges them anyway, and this keeps the shell readable
		// on a server that declines to allocate one.
		channel.setRedirectErrorStream(true);

		// Leaving in/out unset is what makes MINA expose the channel as a pair of "inverted" streams
		// (an OutputStream into the remote stdin, an InputStream out of its stdout) — exactly the
		// shape the terminal wants.
		try {
			channel.open().verify(OPEN_TIMEOUT);
		} catch (IOException e) {
			closeQuietly(channel);
			throw new IOException("Could not open a shell on " + connection.config().address() + ": "
					+ e.getMessage(), e);
		}

		var session = new NetTerminalSession(channel, connection.config().address());
		session.primeShell(remoteDir);
		return session;
	}

	/**
	 * Prime the freshly opened shell: put it in the panel's folder, and make it report its working
	 * directory in the terminal title so the panel can follow a {@code cd} once the console closes.
	 *
	 * <p>Both halves are typed into the shell's stdin, because SSH offers no request for either a
	 * channel's starting directory or its live cwd. The {@code cd} is echoed back by the remote and
	 * shows on the first line — which is honest, it is what ran. The reporting installs a bash
	 * {@code PROMPT_COMMAND} and a zsh {@code precmd} that both emit {@code OSC 0} with a
	 * {@link #CWD_TITLE_PREFIX}-tagged {@code $PWD} before every prompt; the commander has no other
	 * way to learn the cwd, and the tag lets it tell our report from a shell's own title. A shell
	 * that honours neither hook (dash) simply never tags a title, and the panel stays put.
	 *
	 * <p>The immediate emit is deliberately omitted: bash/zsh run the hook when they draw the very
	 * next prompt, so the title is set without it — whereas a {@code dash} that ignores the hooks
	 * would, if we emitted once here, tag its <em>starting</em> directory and mislead the follow
	 * after the user has {@code cd}'d elsewhere.
	 */
	private void primeShell(String remoteDir) {

		var command = new StringBuilder();
		if (remoteDir != null && !remoteDir.isBlank()) {
			command.append("cd ").append(ShellEscape.quote(RemotePaths.normalize(remoteDir))).append("; ");
		}
		// Single quotes stop the shell touching the escapes; printf itself interprets \033 (ESC) and
		// \007 (BEL), the OSC 0 delimiters.
		String emit = "printf '\\033]0;" + CWD_TITLE_PREFIX + "%s\\007' \"$PWD\"";
		command.append("_nuclr_cwd() { ").append(emit).append("; }; ")
				.append("PROMPT_COMMAND=_nuclr_cwd; ")
				.append("precmd() { _nuclr_cwd; }\n");

		try {
			OutputStream stdin = channel.getInvertedIn();
			stdin.write(command.toString().getBytes(StandardCharsets.UTF_8));
			stdin.flush();
		} catch (IOException e) {
			// The shell is up and usable; it just opened in the login directory and won't report its
			// cwd. Not worth failing the console over — the user can cd themselves.
			log.warn("Could not prime the remote shell for {}: {}", remoteDir, e.getMessage());
		}
	}

	@Override
	public InputStream output() {
		return channel.getInvertedOut();
	}

	@Override
	public OutputStream input() {
		return channel.getInvertedIn();
	}

	@Override
	public boolean isAlive() {
		return channel.isOpen() && !channel.isClosing();
	}

	@Override
	public int waitFor() {
		channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 0L);
		Integer status = channel.getExitStatus();
		return status == null ? 0 : status;
	}

	@Override
	public void resize(int columns, int rows) {
		try {
			channel.sendWindowChange(columns, rows);
		} catch (IOException e) {
			log.debug("Could not send a window change to the remote shell: {}", e.getMessage());
		}
	}

	@Override
	public String name() {
		return name;
	}

	@Override
	public void close() {
		closeQuietly(channel);
	}

	/** Close the channel only — never the session, which the connection owns and the panel shares. */
	private static void closeQuietly(ChannelShell channel) {
		try {
			channel.close(true);
		} catch (RuntimeException e) {
			log.debug("Error closing the remote shell channel: {}", e.getMessage());
		}
	}

}
