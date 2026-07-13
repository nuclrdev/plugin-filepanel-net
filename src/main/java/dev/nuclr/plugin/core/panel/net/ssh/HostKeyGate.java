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
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;

import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;

import lombok.extern.slf4j.Slf4j;

/**
 * Secure host-key verification backed by a {@code known_hosts} file
 * ({@code ~/.nuclr/net/known_hosts} in production). Unknown hosts are referred
 * to a {@link Prompt} (trust-on-first-use with an explicit fingerprint
 * confirmation); once accepted, the key is recorded. A key that <b>changed</b>
 * for a known host is also referred to the prompt, which is expected to warn
 * loudly — accepting a changed key rewrites the recorded entry.
 */
@Slf4j
public final class HostKeyGate {

	/** User decision callback for unknown and changed host keys. */
	public interface Prompt {

		/**
		 * Decide whether to trust a host seen for the first time.
		 *
		 * @param address     the remote endpoint
		 * @param keyType     the algorithm of the presented key (e.g. {@code ssh-ed25519})
		 * @param fingerprint the SHA-256 fingerprint of the presented key
		 * @return {@code true} to trust and record the key
		 */
		boolean acceptUnknown(SocketAddress address, String keyType, String fingerprint);

		/**
		 * Decide whether to trust a host whose key differs from the recorded one
		 * (possible man-in-the-middle). Implementations should present a strong
		 * warning and default to rejection.
		 *
		 * @param address             the remote endpoint
		 * @param recordedFingerprint the fingerprint stored in known_hosts
		 * @param presentedKeyType    the algorithm of the newly presented key
		 * @param presentedFingerprint the fingerprint of the newly presented key
		 * @return {@code true} to trust the new key and update the record
		 */
		boolean acceptChanged(SocketAddress address, String recordedFingerprint,
				String presentedKeyType, String presentedFingerprint);
	}

	private HostKeyGate() {
	}

	/**
	 * Build the verifier over the given {@code known_hosts} file.
	 *
	 * @param knownHostsFile the persistent known-hosts file (created when missing)
	 * @param prompt         the user decision callback
	 * @return the configured verifier
	 * @throws IOException if the known-hosts file cannot be created
	 */
	public static ServerKeyVerifier create(Path knownHostsFile, Prompt prompt) throws IOException {

		Path parent = knownHostsFile.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		if (!Files.exists(knownHostsFile)) {
			Files.createFile(knownHostsFile);
		}

		ServerKeyVerifier unknownHostDelegate = (session, address, serverKey) -> {
			boolean accepted = prompt.acceptUnknown(address, KeyUtils.getKeyType(serverKey), fingerprint(serverKey));
			log.info("Unknown host key for {} ({}): {}", address, fingerprint(serverKey),
					accepted ? "accepted" : "rejected");
			return accepted;
		};

		var verifier = new KnownHostsServerKeyVerifier(unknownHostDelegate, knownHostsFile);
		verifier.setModifiedServerKeyAcceptor((session, address, entry, expected, actual) -> {
			boolean accepted = prompt.acceptChanged(address, fingerprint(expected),
					KeyUtils.getKeyType(actual), fingerprint(actual));
			log.warn("CHANGED host key for {} (recorded {}, presented {}): {}", address,
					fingerprint(expected), fingerprint(actual), accepted ? "ACCEPTED by user" : "rejected");
			return accepted;
		});
		return verifier;
	}

	/**
	 * Return the SHA-256 fingerprint of a public key in OpenSSH notation.
	 *
	 * @param key the key to fingerprint
	 * @return the fingerprint string
	 */
	public static String fingerprint(PublicKey key) {
		return KeyUtils.getFingerPrint(BuiltinDigests.sha256, key);
	}

}
