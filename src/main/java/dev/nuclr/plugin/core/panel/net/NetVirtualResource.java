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
package dev.nuclr.plugin.core.panel.net;

import java.util.List;

import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.net.ssh.ServerConfig;

/**
 * Virtual, path-less resources of the Net panel: the {@code Net} root shown in
 * the Alt+F1/Alt+F2 drive selector and one entry per saved server profile.
 * Both carry {@link #MARKER} so {@code NetFilePanelPlugin#supports} can claim
 * them; the {@code null} path keeps the local-filesystem plugin away (the
 * same pattern the GCP panel uses).
 */
public final class NetVirtualResource extends NuclrResource {

	private static final long serialVersionUID = 1L;

	/** Metadata flag marking a resource as belonging to the Net panel. */
	public static final String MARKER = "nuclr.net.panel";

	/** Metadata key distinguishing the kind of virtual resource. */
	public static final String KIND = "nuclr.net.kind";

	/** The server-list root. */
	public static final String KIND_ROOT = "root";

	/** One saved server profile. */
	public static final String KIND_SERVER = "server";

	/** Metadata key: the server profile id of a {@link #KIND_SERVER} entry. */
	public static final String KEY_SERVER_ID = "net.server";

	/** Columns displayed for the server-list view. */
	public static final List<String> ColumnNames = List.of("Name", "Address", "User", "Status");

	private NetVirtualResource(String name, String uuid) {
		super(null);
		this.name = name;
		this.setUuid(uuid);
		this.setFullPath(uuid);
		this.setFolder(true);
		this.setReadable(true);
		this.getMetadata().put(MARKER, Boolean.TRUE);
		this.getMetadata().put("Name", name);
	}

	/**
	 * Build the {@code Net} root (the server-list location).
	 *
	 * @return the root resource
	 */
	public static NetVirtualResource root() {
		var resource = new NetVirtualResource("Net", "net:root");
		resource.getMetadata().put(KIND, KIND_ROOT);
		return resource;
	}

	/**
	 * Build the synthetic {@code ..} entry that leads from a server's remote
	 * root back to the server list.
	 *
	 * @return the parent navigation resource
	 */
	public static NetVirtualResource parentToServerList() {
		var resource = new NetVirtualResource("..", "net:root");
		resource.getMetadata().put(KIND, KIND_ROOT);
		resource.getMetadata().put("Size", "Up");
		return resource;
	}

	/**
	 * Build the server-list entry for one saved profile.
	 *
	 * @param config    the server profile
	 * @param connected whether a live session currently exists
	 * @return the server entry resource
	 */
	public static NetVirtualResource server(ServerConfig config, boolean connected) {
		var resource = new NetVirtualResource(config.displayName(), "net:server:" + config.getId());
		resource.getMetadata().put(KIND, KIND_SERVER);
		resource.getMetadata().put(KEY_SERVER_ID, config.getId());
		resource.getMetadata().put("Address", config.getHost() + ":" + config.getPort());
		resource.getMetadata().put("User", config.getUsername());
		resource.getMetadata().put("Status", connected ? "Connected" : "");
		return resource;
	}

	/**
	 * Return the kind marker of a resource, or {@code null} when the resource
	 * does not belong to the Net panel.
	 *
	 * @param resource any resource
	 * @return {@link #KIND_ROOT}, {@link #KIND_SERVER} or {@code null}
	 */
	public static String kindOf(NuclrResource resource) {
		if (resource == null || !resource.getMetadata(MARKER, Boolean.FALSE)) {
			return null;
		}
		return resource.getMetadata(KIND, (String) null);
	}

	/**
	 * Return the server id carried by a {@link #KIND_SERVER} entry.
	 *
	 * @param resource the server entry
	 * @return the profile id, or {@code null}
	 */
	public static String serverIdOf(NuclrResource resource) {
		return resource == null ? null : resource.getMetadata(KEY_SERVER_ID, (String) null);
	}

}
