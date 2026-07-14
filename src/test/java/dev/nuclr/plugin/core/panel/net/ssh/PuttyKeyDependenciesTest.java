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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.putty.PuttyKeyUtils;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for a real bug: {@code sshd-putty} declares BouncyCastle
 * ({@code bcprov`/`bcpkix`/`bcpg`/`bcutil-jdk18on}) and {@code eddsa} as
 * <em>optional</em> Maven dependencies, so they are not pulled in
 * transitively. Without them, {@link PuttyKeyUtils#DEFAULT_INSTANCE} doesn't
 * throw when it can't parse a PuTTY-Gen v3 key (the current PuTTYgen default,
 * using Argon2 KDF for encrypted keys) — it just silently returns zero usable
 * keys, which {@link KeyLoader#load} then reports as "No usable keys found".
 * These tests fail loudly if those dependencies are ever removed from
 * {@code pom.xml} again, instead of that surfacing only as a confusing
 * runtime error against a real PPK v3 file.
 */
class PuttyKeyDependenciesTest {

	@Test
	void argon2ClassesForPpkV3AreOnTheClasspath() {
		// PuttyKeyPairResourceParser imports these directly for the Argon2 KDF
		// used by encrypted PPK v3 keys; a NoClassDefFoundError here would only
		// surface when an actual v3 key is loaded, not at build time.
		assertDoesNotThrow(() -> Class.forName("org.bouncycastle.crypto.generators.Argon2BytesGenerator"));
		assertDoesNotThrow(() -> Class.forName("org.bouncycastle.crypto.params.Argon2Parameters"));
	}

	@Test
	void bouncyCastleProviderIsRegistered() {
		assertTrue(SecurityUtils.isBouncyCastleRegistered(),
				"BouncyCastle must be on the classpath for MINA's SecurityUtils to auto-register it");
	}

	@Test
	void eccAndEdDsaPuttyParsersAreRegistered() {
		// SecurityUtils.isECCSupported()/isEDDSACurveSupported() gate whether
		// PuttyKeyUtils registers the ECDSA/EdDSA decoders at all (see its static
		// initializer); without bcprov+eddsa on the classpath these silently
		// come back false and those key types are never even attempted.
		assertTrue(SecurityUtils.isECCSupported());
		assertTrue(SecurityUtils.isEDDSACurveSupported());

		assertTrue(PuttyKeyUtils.BY_KEY_TYPE.containsKey("ssh-rsa"));
		assertTrue(PuttyKeyUtils.BY_KEY_TYPE.containsKey("ssh-dss"));
		assertTrue(PuttyKeyUtils.BY_KEY_TYPE.keySet().stream().anyMatch(k -> k.startsWith("ecdsa-sha2-")),
				"ECDSA PPK support requires bcprov to be present");
		assertTrue(PuttyKeyUtils.BY_KEY_TYPE.containsKey("ssh-ed25519"),
				"Ed25519 PPK support requires the eddsa dependency to be present");
	}

}
