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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.panel.net.remote.NetConnection;

class ShellAccessTest {

	@Test
	void requireRefusesConnectionWithoutShell() {
		var config = new ServerConfig();
		config.setHost("files.example.com");
		config.setPort(21);

		// A connection that only implements the protocol-neutral interface, as an
		// FTP one will.
		NetConnection plain = (NetConnection) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[] { NetConnection.class },
				(proxy, method, args) -> "config".equals(method.getName()) ? config : null);

		IOException e = assertThrows(IOException.class, () -> ShellAccess.require(plain));
		assertTrue(e.getMessage().contains("files.example.com"), e.getMessage());
	}

}
