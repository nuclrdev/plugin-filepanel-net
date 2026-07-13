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

import java.io.Serializable;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/**
 * A saved SSH server profile. Persisted to {@code ~/.nuclr/net/servers.json}
 * by {@link ServerStore}; passwords and key passphrases are deliberately
 * <b>not</b> part of this class — they are held in memory only for the
 * lifetime of the session (see {@code ConnectionRegistry}).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServerConfig implements Serializable {

	private static final long serialVersionUID = 1L;

	/** How the session authenticates against the server. */
	public enum AuthMethod {
		/** Password authentication; the password is prompted for and cached in memory. */
		PASSWORD,
		/** Public-key authentication with a private key file (OpenSSH, PEM/PKCS#8 or PuTTY PPK). */
		KEY
	}

	/** Stable unique profile identifier. */
	private String id = UUID.randomUUID().toString();

	/** Optional display label; falls back to {@code user@host} when blank. */
	private String name = "";

	/** Hostname or IP address. */
	private String host = "";

	/** SSH port, {@code 22} by default. */
	private int port = 22;

	/** Login user name. */
	private String username = "";

	/** Authentication method for this profile. */
	private AuthMethod authMethod = AuthMethod.PASSWORD;

	/** Absolute local path of the private key file, used when {@link #authMethod} is {@code KEY}. */
	private String privateKeyPath = "";

	/** Initial remote directory to open after connecting; blank means the login home directory. */
	private String initialPath = "";

	/** Creates a profile with a fresh id and defaults. */
	public ServerConfig() {
	}

	/**
	 * Return the label shown for this server in the panel and menus.
	 *
	 * @return the profile name, or {@code user@host} when no name is set
	 */
	public String displayName() {
		if (name != null && !name.isBlank()) {
			return name;
		}
		return address();
	}

	/**
	 * Return the {@code user@host[:port]} form of this profile (port omitted
	 * when it is the default 22).
	 *
	 * @return the address string
	 */
	public String address() {
		String base = (username == null || username.isBlank() ? "" : username + "@") + host;
		return port == 22 ? base : base + ":" + port;
	}

	/**
	 * Return {@code true} when this profile authenticates with a private key.
	 *
	 * @return {@code true} for key authentication
	 */
	public boolean usesKey() {
		return authMethod == AuthMethod.KEY;
	}

}
