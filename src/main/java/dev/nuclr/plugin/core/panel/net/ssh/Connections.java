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
import java.nio.file.Path;

import org.apache.sshd.client.keyverifier.ServerKeyVerifier;

import dev.nuclr.plugin.core.panel.net.ui.NetCredentialsPrompt;
import lombok.extern.slf4j.Slf4j;

/**
 * The one way the plugin obtains a connection for a server profile, shared by the
 * panels and by actions: the same registry entry (so the same live session), the
 * same {@code known_hosts} verifier and the same credential prompts. Whoever asks
 * - a person browsing or an agent running an action - host-key and password
 * decisions are always made by the person, in this plugin's dialogs.
 */
@Slf4j
public final class Connections {

	private static volatile ServerKeyVerifier hostKeyVerifier;

	private Connections() {
	}

	/**
	 * Return the shared, possibly not yet opened, connection for a profile.
	 * Call {@link NetConnection#ensureOpen()} before using it.
	 *
	 * @param config the server profile
	 * @return the registry's connection for that profile
	 */
	public static NetConnection forConfig(ServerConfig config) {
		return ConnectionRegistry.getOrCreate(config.getId(),
				id -> new NetConnection(config, hostKeyVerifier(), NetCredentialsPrompt.INSTANCE));
	}

	/**
	 * Return the shared connection for a profile, opened.
	 *
	 * @param config the server profile
	 * @return the open connection
	 * @throws IOException if connecting, host verification or authentication fails
	 */
	public static NetConnection open(ServerConfig config) throws IOException {
		NetConnection connection = forConfig(config);
		connection.ensureOpen();
		return connection;
	}

	/**
	 * Return the process-wide host-key verifier backed by {@code known_hosts}.
	 *
	 * @return the verifier; refuses every host if the store cannot be set up
	 */
	public static synchronized ServerKeyVerifier hostKeyVerifier() {
		if (hostKeyVerifier == null) {
			try {
				hostKeyVerifier = HostKeyGate.create(knownHostsFile(), NetCredentialsPrompt.INSTANCE);
			} catch (IOException e) {
				log.error("Cannot initialize known_hosts store at {}: {}", knownHostsFile(), e.getMessage());
				hostKeyVerifier = (session, address, serverKey) -> false; // fail safe: refuse all hosts
			}
		}
		return hostKeyVerifier;
	}

	private static Path knownHostsFile() {
		return Path.of(System.getProperty("user.home"), ".nuclr", "net", "known_hosts");
	}

}
