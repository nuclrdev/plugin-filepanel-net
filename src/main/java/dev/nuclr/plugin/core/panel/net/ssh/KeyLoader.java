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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.putty.PuttyKeyUtils;

/**
 * Loads client identities from a private key file. OpenSSH, PEM and PKCS#8
 * formats are parsed by MINA's registered resource parsers; PuTTY PPK files
 * (v2, and v3 where the underlying MINA parser supports the KDF) are detected
 * by header or {@code .ppk} extension and routed to the PuTTY parser.
 */
public final class KeyLoader {

	private static final String PPK_HEADER = "PuTTY-User-Key-File-";

	private KeyLoader() {
	}

	/**
	 * Load all key pairs contained in the given key file.
	 *
	 * @param keyFile          the private key file
	 * @param passwordProvider prompts for/supplies the key passphrase when the
	 *                         key is encrypted; may be invoked multiple times
	 *                         on retry
	 * @return the parsed identities, never {@code null} or empty
	 * @throws IOException              if the file cannot be read or parsed
	 * @throws GeneralSecurityException if key material cannot be reconstructed
	 *                                  (bad passphrase, unsupported cipher, …)
	 */
	public static List<KeyPair> load(Path keyFile, FilePasswordProvider passwordProvider)
			throws IOException, GeneralSecurityException {

		if (keyFile == null || !Files.isRegularFile(keyFile)) {
			throw new IOException("Private key file not found: " + keyFile);
		}

		Iterable<KeyPair> pairs;

		if (isPpk(keyFile)) {
			pairs = PuttyKeyUtils.DEFAULT_INSTANCE.loadKeyPairs(null, keyFile, passwordProvider);
		} else {
			try (InputStream in = Files.newInputStream(keyFile)) {
				pairs = SecurityUtils.loadKeyPairIdentities(
						null, NamedResource.ofName(keyFile.toString()), in, passwordProvider);
			}
		}

		var result = new ArrayList<KeyPair>();
		if (pairs != null) {
			pairs.forEach(result::add);
		}
		if (result.isEmpty()) {
			throw new IOException("No usable keys found in " + keyFile.getFileName()
					+ " (supported formats: OpenSSH, PEM, PKCS#8, PuTTY PPK)");
		}
		return result;
	}

	/**
	 * Detect a PuTTY PPK key file by its header line or file extension.
	 *
	 * @param keyFile the file to probe
	 * @return {@code true} when the file looks like a PPK key
	 * @throws IOException if the file cannot be read
	 */
	public static boolean isPpk(Path keyFile) throws IOException {
		String name = keyFile.getFileName() != null
				? keyFile.getFileName().toString().toLowerCase(Locale.ROOT)
				: "";
		if (name.endsWith(".ppk")) {
			return true;
		}
		try (InputStream in = Files.newInputStream(keyFile)) {
			byte[] head = in.readNBytes(PPK_HEADER.length());
			return new String(head, StandardCharsets.US_ASCII).startsWith(PPK_HEADER);
		}
	}

}
