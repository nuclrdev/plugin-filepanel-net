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

import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.swing.SwingUtilities;

import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.sftp.client.SftpClient;

import dev.nuclr.platform.plugin.BaseNuclrPlugin;
import dev.nuclr.platform.plugin.FilePanelNuclrPlugin;
import dev.nuclr.platform.plugin.NuclrContextMenuItem;
import dev.nuclr.platform.plugin.NuclrMenuResource;
import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.net.find.NetFindDialog;
import dev.nuclr.plugin.core.panel.net.find.NetFindRequest;
import dev.nuclr.plugin.core.panel.net.find.NetFindResultsWindow;
import dev.nuclr.plugin.core.panel.net.service.Alerts;
import dev.nuclr.plugin.core.panel.net.service.NetCopyService;
import dev.nuclr.plugin.core.panel.net.service.NetDeleteService;
import dev.nuclr.plugin.core.panel.net.service.NetEditService;
import dev.nuclr.plugin.core.panel.net.service.NetMakeFolderService;
import dev.nuclr.plugin.core.panel.net.service.NetMoveService;
import dev.nuclr.plugin.core.panel.net.ssh.ConnectionRegistry;
import dev.nuclr.plugin.core.panel.net.ssh.HostKeyGate;
import dev.nuclr.plugin.core.panel.net.ssh.NetConnection;
import dev.nuclr.plugin.core.panel.net.ssh.RemotePaths;
import dev.nuclr.plugin.core.panel.net.ssh.ServerConfig;
import dev.nuclr.plugin.core.panel.net.ssh.ServerStore;
import dev.nuclr.plugin.core.panel.net.tail.NetTailWindow;
import dev.nuclr.plugin.core.panel.net.ui.NetConnectionDialog;
import dev.nuclr.plugin.core.panel.net.ui.NetCredentialsPrompt;
import lombok.extern.slf4j.Slf4j;

/**
 * File-panel plugin that browses and manages remote servers over SSH:
 * directory listing and filesystem operations via SFTP, transfers via SCP,
 * both sharing one SSH session per server ({@link NetConnection}).
 *
 * <p>One instance backs one panel side, mirroring
 * {@code LocalFileSystemPlugin} and {@code ZipFilePanelPlugin}
 * ({@link #singleton()} is {@code false}): each side keeps its own current
 * folder, but both draw on the same process-wide {@link ConnectionRegistry},
 * so opening the same server on both sides (or moving/copying between them)
 * uses one shared SSH session.
 *
 * <p>Navigation has two levels: the {@link NetVirtualResource} server list
 * (the {@code Net} root shown on Alt+F1/Alt+F2), and — once a server entry is
 * opened — the remote filesystem mounted by {@code NetConnection}'s SFTP
 * client, represented by ordinary {@link NetResource} entries backed by real
 * {@link Path}s. Because those paths are genuine {@code java.nio.file.Path}
 * instances, the host's existing quick-view, hex-editor and text-editor
 * plugins already work against them without any Net-specific glue; only F4
 * Edit is special-cased (see {@link NetEditService}) to give editors a fast
 * local scratch copy with conflict detection and an atomic upload.
 */
@Slf4j
public final class NetFilePanelPlugin implements FilePanelNuclrPlugin {

	public static final String PluginId = "dev.nuclr.plugin.core.panel.net";
	private static final String PluginName = "Net Remote Panel";
	private static final String PluginVersion = loadVersion();
	private static final String PluginDescription =
			"Browse and manage remote servers over SSH: SFTP filesystem operations, SCP transfers, remote find, tail -F and remote editing.";
	private static final String PluginAuthor = "Nuclr Development Team";
	private static final String PluginLicense = "Apache-2.0";
	private static final String PluginWebsite = "https://nuclr.dev";
	private static final String PluginPageUrl = "https://nuclr.dev/plugins/core/filepanel-net.html";
	private static final String PluginDocUrl = PluginPageUrl;

	/** Mirrors the generic file-panel plugin protocol used by filepanel-fs/filepanel-zip. */
	private static final String AcceptCopy = "accept.copy";
	private static final String AcceptMove = "accept.move";

	private final String uuid = java.util.UUID.randomUUID().toString();

	private static volatile ServerKeyVerifier hostKeyVerifier;

	private NuclrPluginContext context;
	private boolean focused;

	/** Either a {@link NetVirtualResource} (server list) or a {@link NetResource} (remote path). */
	private NuclrResource currentFolder;

	/** The server connection backing {@link #currentFolder}, or {@code null} at the server list. */
	private NetConnection currentConnection;

	private final ServerStore serverStore = ServerStore.defaultStore();

	// =========================================================================
	// Lifecycle
	// =========================================================================

	@Override
	public void preinit(NuclrPluginContext context) {
		this.context = context;
		this.currentFolder = NetVirtualResource.root();
		NetEditService.instance(context);
		log.info("Net panel plugin loaded");
	}

	@Override
	public NuclrPluginContext getContext() {
		return context;
	}

	@Override
	public void init() {
		log.info("Net panel plugin inited");
	}

	@Override
	public void unload() {
		log.info("Net panel plugin unloaded");
	}

	@Override
	public boolean singleton() {
		// Each panel side keeps its own current folder; connections are shared
		// separately through the process-wide ConnectionRegistry.
		return false;
	}

	@Override
	public String uuid() {
		return uuid;
	}

	@Override
	public void closeResource() {
	}

	@Override
	public NuclrResource getCurrentResource() {
		return currentFolder;
	}

	@Override
	public Developer developer() {
		return Developer.Official;
	}

	@Override
	public boolean onFocusGained() {
		focused = true;
		return true;
	}

	@Override
	public void onFocusLost() {
		focused = false;
	}

	@Override
	public boolean isFocused() {
		return focused;
	}

	// =========================================================================
	// Metadata
	// =========================================================================

	@Override
	public String id() {
		return PluginId;
	}

	@Override
	public String name() {
		return PluginName;
	}

	@Override
	public String version() {
		return PluginVersion;
	}

	private static String loadVersion() {
		try (var stream = NetFilePanelPlugin.class.getResourceAsStream("/plugin.properties")) {
			if (stream == null) {
				return "unknown";
			}
			var props = new java.util.Properties();
			props.load(stream);
			return props.getProperty("version", "unknown");
		} catch (IOException e) {
			return "unknown";
		}
	}

	@Override
	public String description() {
		return PluginDescription;
	}

	@Override
	public String author() {
		return PluginAuthor;
	}

	@Override
	public String license() {
		return PluginLicense;
	}

	@Override
	public String website() {
		return PluginWebsite;
	}

	@Override
	public String pageUrl() {
		return PluginPageUrl;
	}

	@Override
	public String docUrl() {
		return PluginDocUrl;
	}

	// =========================================================================
	// Drive selector (Alt+F1 / Alt+F2)
	// =========================================================================

	@Override
	public MenuItemsHolder getPluginMenuItems() {
		var holder = new MenuItemsHolder();
		var item = new MenuItem();
		item.setText("Net");
		item.setPath(NetVirtualResource.root());
		item.setUuid(id() + ":root");
		holder.setMenuItems(List.of(item));
		holder.setTitle("Net");
		return holder;
	}

	// =========================================================================
	// Navigation
	// =========================================================================

	@Override
	public boolean supports(NuclrResource resource) {

		if (resource == null) {
			return false;
		}
		if (NetVirtualResource.kindOf(resource) != null) {
			return true;
		}
		Path path = resource.getPath();
		if (path == null) {
			return false;
		}
		return ConnectionRegistry.serverIdFor(path.getFileSystem()) != null;
	}

	@Override
	public NuclrResourceData openResource(NuclrResource resourceToOpen, AtomicBoolean cancelled) {
		return openResource(resourceToOpen, cancelled, null);
	}

	@Override
	public NuclrResourceData openResource(NuclrResource resourceToOpen, AtomicBoolean cancelled, EntrySink sink) {

		if (resourceToOpen == null || isCancelled(cancelled)) {
			return null;
		}

		String kind = NetVirtualResource.kindOf(resourceToOpen);

		if (NetVirtualResource.KIND_ROOT.equals(kind)) {
			return listServerList(sink);
		}
		if (NetVirtualResource.KIND_SERVER.equals(kind)) {
			return connectAndOpen(resourceToOpen, sink);
		}

		Path path = resourceToOpen.getPath();
		if (path == null) {
			return null;
		}
		String serverId = ConnectionRegistry.serverIdFor(path.getFileSystem());
		NetConnection connection = serverId != null ? ConnectionRegistry.get(serverId) : null;
		if (connection == null) {
			return null;
		}

		this.currentConnection = connection;
		return listDirectory(connection, serverId, RemotePaths.normalize(path.toString().replace('\\', '/')),
				cancelled, sink);
	}

	/** List the saved server profiles (the {@code Net} root). */
	private NuclrResourceData listServerList(EntrySink sink) {

		this.currentConnection = null;
		this.currentFolder = NetVirtualResource.root();

		var data = new NuclrResourceData();
		data.setColumnNames(NetVirtualResource.ColumnNames);
		if (sink != null) {
			sink.columns(NetVirtualResource.ColumnNames);
		}

		for (ServerConfig config : serverStore.load()) {
			var entry = NetVirtualResource.server(config, ConnectionRegistry.isConnected(config.getId()));
			data.getEntries().add(entry);
			if (sink != null) {
				sink.add(entry);
			}
		}

		return data;
	}

	/** Connect (lazily) to the chosen server and list its initial directory. */
	private NuclrResourceData connectAndOpen(NuclrResource serverEntry, EntrySink sink) {

		String serverId = NetVirtualResource.serverIdOf(serverEntry);
		ServerConfig config = serverId != null ? serverStore.byId(serverId) : null;

		if (config == null) {
			Alerts.showError(context, "Connect", "Server profile not found. It may have been removed.");
			return listServerList(sink);
		}

		NetConnection connection = ConnectionRegistry.getOrCreate(serverId,
				id -> new NetConnection(config, hostKeyVerifier(), NetCredentialsPrompt.INSTANCE));

		try {
			connection.ensureOpen();
		} catch (IOException e) {
			log.warn("Could not connect to {}: {}", config.address(), e.getMessage());
			Alerts.showError(context, "Connect",
					"<html>Could not connect to <b>" + config.address() + "</b><br/>" + e.getMessage() + "</html>");
			return listServerList(sink);
		}

		this.currentConnection = connection;

		String initial = config.getInitialPath() == null || config.getInitialPath().isBlank()
				? connection.home()
				: RemotePaths.normalize(config.getInitialPath());

		return listDirectory(connection, serverId, initial, null, sink);
	}

	/** List one remote directory over SFTP, streaming entries to {@code sink} as they arrive. */
	private NuclrResourceData listDirectory(NetConnection connection, String serverId, String remotePath,
			AtomicBoolean cancelled, EntrySink sink) {

		SftpClient.Attributes dirAttrs;
		try {
			dirAttrs = connection.statOrNull(remotePath);
		} catch (IOException e) {
			Alerts.showError(context, "Open Folder",
					"<html>Could not open <b>" + remotePath + "</b><br/>" + e.getMessage() + "</html>");
			return null;
		}
		if (dirAttrs == null) {
			Alerts.showError(context, "Open Folder", "<html>Not found:<br/><b>" + remotePath + "</b></html>");
			return null;
		}

		Path dirPath;
		try {
			dirPath = connection.path(remotePath);
		} catch (IOException e) {
			Alerts.showError(context, "Open Folder", e.getMessage());
			return null;
		}

		this.currentFolder = new NetResource(context, serverId, dirPath, remotePath, dirAttrs, false);

		var data = new NuclrResourceData();
		data.setColumnNames(NetResource.ColumnNames);
		if (sink != null) {
			sink.columns(NetResource.ColumnNames);
		}

		NetResource parentEntry;
		if (RemotePaths.isRoot(remotePath)) {
			var closeEntry = NetVirtualResource.parentToServerList();
			data.getEntries().add(closeEntry);
			if (sink != null) {
				sink.add(closeEntry);
			}
			parentEntry = null;
		} else {
			String parentPath = RemotePaths.parent(remotePath);
			parentEntry = directoryPlaceholder(serverId, connection, parentPath, "..");
			data.getEntries().add(parentEntry);
			if (sink != null) {
				sink.add(parentEntry);
			}
		}

		List<NetResource> children = NetDirectoryCache.get(serverId, remotePath);

		if (children == null) {
			children = fetchChildren(connection, serverId, remotePath, cancelled);
			if (!isCancelled(cancelled)) {
				NetDirectoryCache.put(serverId, remotePath, children);
			}
		}

		for (var child : children) {
			data.getEntries().add(child);
			if (sink != null) {
				sink.add(child);
			}
		}

		return data;
	}

	/** SFTP {@code readDir} for one folder, sorted folders-first/alphabetically. Not cached itself. */
	private List<NetResource> fetchChildren(NetConnection connection, String serverId, String remotePath,
			AtomicBoolean cancelled) {

		var children = new ArrayList<NetResource>();

		try (SftpClient sftp = connection.sftp()) {
			for (SftpClient.DirEntry entry : sftp.readDir(remotePath)) {
				if (isCancelled(cancelled)) {
					break;
				}
				String name = entry.getFilename();
				if (".".equals(name) || "..".equals(name)) {
					continue;
				}
				String childPath = RemotePaths.join(remotePath, name);
				Path childNioPath = connection.path(childPath);
				var attrs = entry.getAttributes();
				var child = new NetResource(context, serverId, childNioPath, childPath, attrs,
						attrs.isSymbolicLink());
				children.add(child);
			}
		} catch (IOException e) {
			log.error("Failed to list remote directory {}: {}", remotePath, e.getMessage(), e);
			Alerts.showError(context, "Open Folder",
					"<html>Could not list <b>" + remotePath + "</b><br/>" + e.getMessage() + "</html>");
		}

		// Folders first, then files, both alphabetically — a sane default order
		// when the panel's sort mode is "Unsorted".
		children.sort(java.util.Comparator.comparing((NetResource r) -> !r.isFolder())
				.thenComparing(NetResource::getName, String.CASE_INSENSITIVE_ORDER));

		return children;
	}

	/**
	 * Build a directory entry without a network round trip (used for the
	 * synthetic {@code ..} row and Find-result navigation): correctness of
	 * {@code isFolder()} is asserted directly rather than fetched via stat.
	 */
	private NetResource directoryPlaceholder(String serverId, NetConnection connection, String remotePath,
			String displayName) {
		Path path;
		try {
			path = connection.path(remotePath);
		} catch (IOException e) {
			path = null;
		}
		var resource = new NetResource(context, serverId, path, remotePath, null, false);
		resource.setFolder(true);
		if (displayName != null) {
			resource.rename(displayName);
		}
		return resource;
	}

	private static boolean isCancelled(AtomicBoolean cancelled) {
		return cancelled != null && cancelled.get();
	}

	// =========================================================================
	// Descendant walk (e.g. quick folder-size)
	// =========================================================================

	@Override
	public void walkDescendants(NuclrResource folder, Consumer<NuclrResource> visitor, AtomicBoolean cancelled,
			boolean recursive) throws IOException {

		Path root = folder.getPath();
		if (root == null) {
			return;
		}
		String serverId = ConnectionRegistry.serverIdFor(root.getFileSystem());
		NetConnection connection = serverId != null ? ConnectionRegistry.get(serverId) : null;
		if (connection == null) {
			throw new IOException("Not connected");
		}

		String rootPath = RemotePaths.normalize(root.toString().replace('\\', '/'));
		walkOneServer(connection, serverId, rootPath, visitor, cancelled, recursive);
	}

	private void walkOneServer(NetConnection connection, String serverId, String dirPath,
			Consumer<NuclrResource> visitor, AtomicBoolean cancelled, boolean recursive) throws IOException {

		var subdirs = new ArrayList<String>();

		try (SftpClient sftp = connection.sftp()) {
			for (SftpClient.DirEntry entry : sftp.readDir(dirPath)) {
				if (isCancelled(cancelled)) {
					return;
				}
				String name = entry.getFilename();
				if (".".equals(name) || "..".equals(name)) {
					continue;
				}
				String childPath = RemotePaths.join(dirPath, name);
				var attrs = entry.getAttributes();
				var child = new NetResource(context, serverId, connection.path(childPath), childPath, attrs,
						attrs.isSymbolicLink());
				visitor.accept(child);
				if (recursive && child.isFolder()) {
					subdirs.add(childPath);
				}
			}
		}

		for (String subdir : subdirs) {
			if (isCancelled(cancelled)) {
				return;
			}
			try {
				walkOneServer(connection, serverId, subdir, visitor, cancelled, recursive);
			} catch (IOException e) {
				log.debug("Skipping unreadable remote directory {}: {}", subdir, e.getMessage());
			}
		}
	}

	// =========================================================================
	// Function-key bar / context menu
	// =========================================================================

	@Override
	public List<NuclrMenuResource> menuItems(NuclrResource source) {

		var items = new ArrayList<NuclrMenuResource>();

		if (currentConnection == null) {
			items.add(menu("Edit Server", "F4", "net.server.edit"));
			items.add(menu("New Server", "F7", "net.server.new"));
			items.add(menu("Remove Server", "F8", "net.server.remove"));
			return items;
		}

		boolean isDirectory = source != null && source.isFolder();

		items.add(menu("View", "F3", "filepanel.view"));
		items.add(menu("Edit", "F4", "filepanel.edit"));
		items.add(menu("Copy", "F5", "filepanel.copy"));
		items.add(menu(isDirectory ? "Move" : "Rename/Move", "F6", "filepanel.move"));
		items.add(menu("Make Folder", "F7", "filepanel.makeFolder"));
		items.add(menu("Delete", "F8", "filepanel.delete"));
		items.add(menu("Create file", "Shift+F4", "createFile"));
		items.add(menu("Find", "Alt+F7", "find"));
		addSortMenuItems(items);

		return items;
	}

	/**
	 * Ctrl+F3..F12 sort slots. Only sorts genuinely backed by an SFTP attribute
	 * are offered: {@code Created}/{@code Accessed} are omitted because most
	 * SFTP servers don't report a reliable creation time, and report the same
	 * access time as the modification time, which would make those entries
	 * silently duplicate "Modified" instead of doing something distinct.
	 */
	private static void addSortMenuItems(List<NuclrMenuResource> items) {
		items.add(sortByColumn("Name", "Ctrl+F3", "name"));
		items.add(sortByColumn("Extension", "Ctrl+F4", "ext"));
		items.add(sortByColumn("Modified", "Ctrl+F5", "modified"));
		items.add(sortByColumn("Size", "Ctrl+F6", "size"));
		items.add(menu("Unsort", "Ctrl+F7", "filepanel.sort:unsorted"));
		items.add(menu("Sort", "Ctrl+F12", "filepanel.sort:dialog"));
	}

	private static NuclrMenuResource sortByColumn(String columnName, String functionKey, String criterion) {
		return menu(columnName, functionKey, "filepanel.sort:" + criterion + ":" + columnName);
	}

	private static NuclrMenuResource menu(String name, String functionKey, String eventType) {
		return new NuclrMenuResource(name, functionKey, eventType);
	}

	@Override
	public List<NuclrContextMenuItem> contextMenuItems(NuclrResource focusedResource,
			List<NuclrResource> selectedResources) {

		// iconKey values follow the SDK's documented convention (a key the commander's icon theme
		// resolves, e.g. "delete", "copy"); the commander does not yet render context-menu icons for
		// any plugin, but these are set now so the menu picks them up automatically once it does.
		if (currentConnection == null) {
			return List.of(
					NuclrContextMenuItem.builder().label("New Server").actionType("net.server.new")
							.iconKey("server-add").build(),
					NuclrContextMenuItem.builder().label("Edit Server").actionType("net.server.edit")
							.iconKey("edit").build(),
					NuclrContextMenuItem.separator(),
					NuclrContextMenuItem.builder().label("Remove Server").actionType("net.server.remove")
							.iconKey("delete").destructive(true).build());
		}

		var items = new ArrayList<NuclrContextMenuItem>();
		if (focusedResource != null && !focusedResource.isFolder() && !"..".equals(focusedResource.getName())) {
			items.add(NuclrContextMenuItem.builder().label("Tail -F").actionType("net.tail")
					.iconKey("terminal").build());
			items.add(NuclrContextMenuItem.separator());
		}
		items.add(NuclrContextMenuItem.builder().label("Delete").actionType("filepanel.delete")
				.iconKey("delete").destructive(true).build());
		return items;
	}

	// =========================================================================
	// Location / selection display
	// =========================================================================

	@Override
	public String getCurrentLocationDisplayText() {
		if (currentConnection == null) {
			return "Net";
		}
		String path = currentFolder instanceof NetResource r ? r.remotePath() : "/";
		return currentConnection.config().address() + ":" + path;
	}

	@Override
	public String getWindowTitle() {
		return getCurrentLocationDisplayText();
	}

	@Override
	public String getSelectionSummaryText(List<NuclrResource> selectedResources) {

		if (selectedResources == null || selectedResources.isEmpty()) {
			return getCurrentLocationDisplayText();
		}
		if (selectedResources.size() == 1) {
			var resource = selectedResources.get(0);
			String type = resource.isFolder() ? "Folder" : NetResource.humanReadableSize(resource.getLength());
			return resource.getName() + "  |  " + type;
		}

		long totalBytes = 0L;
		int fileCount = 0;
		int folderCount = 0;
		for (var resource : selectedResources) {
			if (resource.isFolder()) {
				folderCount++;
			} else {
				fileCount++;
				totalBytes += resource.getLength();
			}
		}
		return "Bytes: " + NetResource.humanReadableSize(totalBytes) + ",  files: " + fileCount + ",  folders: "
				+ folderCount;
	}

	// =========================================================================
	// Actions
	// =========================================================================

	@Override
	public void act(BaseNuclrPlugin other, String actionType, List<NuclrResource> selectedResources,
			NuclrResource focusedResource, Map<String, Object> data, NuclrPluginCallback callback) {

		switch (actionType) {
			case "net.server.new" -> handleNewServer(data);
			case "net.server.edit" -> handleEditServer(focusedResource, data);
			case "net.server.remove" -> handleRemoveServer(selectedResources, focusedResource, data);
			case "net.tail" -> handleTail(focusedResource);
			case "find" -> handleFind();
			case "filepanel.view" -> handleView(focusedResource);
			case "filepanel.edit" -> handleEdit(focusedResource);
			case "filepanel.makeFolder" -> handleMakeFolder(data);
			case "createFile" -> handleCreateFile(data);
			case "filepanel.delete", "filepanel.deletePermanent" -> handleDelete(selectedResources, focusedResource,
					data);
			case "filepanel.copy" -> bridge(other, selectedResources, focusedResource, data, callback, AcceptCopy);
			case "filepanel.move" -> bridgeMove(other, selectedResources, focusedResource, data, callback);
			case AcceptCopy -> acceptCopy(selectedResources, focusedResource, data);
			case AcceptMove -> acceptMove(selectedResources, focusedResource, data);
			case "refresh.panel" -> invalidateCurrentFolderCache();
			default -> {
				// Unknown action: nothing to do.
			}
		}
	}

	/**
	 * Drop the cached listing for the panel's current remote folder, if any.
	 * Directory listings are cached (see {@link NetDirectoryCache}) to avoid a
	 * round trip on routine navigation; this is the one place that cache is
	 * invalidated on the user's explicit request (Ctrl+R "Refresh"). The
	 * commander re-opens the current resource right after this action returns,
	 * so the cache miss that follows is enough to force a fresh SFTP listing —
	 * no separate re-fetch is triggered here.
	 */
	private void invalidateCurrentFolderCache() {
		if (currentConnection != null && currentFolder instanceof NetResource folder) {
			NetDirectoryCache.invalidate(currentConnection.serverId(), folder.remotePath());
		}
	}

	/** Shared bridging for Copy: mirrors the fs/zip plugins' cross-plugin protocol. */
	private void bridge(BaseNuclrPlugin other, List<NuclrResource> selectedResources, NuclrResource focusedResource,
			Map<String, Object> data, NuclrPluginCallback callback, String acceptAction) {

		if (currentConnection == null) {
			return;
		}
		if (other == null || other.uuid().equals(this.uuid())
				|| other.is(BaseNuclrPlugin.Type.QuickView)) {
			this.act(null, acceptAction, selectedResources, focusedResource, data, callback);
			return;
		}
		other.act(null, acceptAction, selectedResources, focusedResource, data, callback);
	}

	/** Move additionally supports "move to itself" as a FAR-style in-place rename. */
	private void bridgeMove(BaseNuclrPlugin other, List<NuclrResource> selectedResources,
			NuclrResource focusedResource, Map<String, Object> data, NuclrPluginCallback callback) {

		if (currentConnection == null) {
			return;
		}
		if (other == null || other.uuid().equals(this.uuid()) || other.is(BaseNuclrPlugin.Type.QuickView)) {
			if (new NetMoveService().renameInPlace(focusedResource, context)) {
				invalidateCurrentFolderCache();
				data.put("result.refresh", true);
			}
			return;
		}
		other.act(null, AcceptMove, selectedResources, focusedResource, data, callback);
	}

	private void acceptCopy(List<NuclrResource> selectedResources, NuclrResource focusedResource,
			Map<String, Object> data) {
		if (currentConnection == null || currentFolder == null) {
			Alerts.showError(context, "Copy", "Open a remote folder first.");
			return;
		}
		new NetCopyService().copy(currentConnection, currentFolder, selectedResources, focusedResource, context);
		invalidateCurrentFolderCache();
		context.getEventBus().emit("refresh.plugin.file.panel", Map.of("plugin.uuid", uuid), null);
	}

	private void acceptMove(List<NuclrResource> selectedResources, NuclrResource focusedResource,
			Map<String, Object> data) {
		if (currentConnection == null || currentFolder == null) {
			Alerts.showError(context, "Move", "Open a remote folder first.");
			return;
		}
		new NetMoveService().move(currentConnection, currentFolder, selectedResources, focusedResource, context);
		invalidateCurrentFolderCache();
		context.getEventBus().emit("refresh.plugin.file.panel", Map.of("plugin.uuid", uuid), null);
	}

	private void handleView(NuclrResource focusedResource) {
		if (currentConnection == null || focusedResource == null || focusedResource.isFolder()) {
			return;
		}
		// Viewers only read; they can stream directly over the mounted SFTP path,
		// so (unlike Edit) no local temp copy is needed here.
		context.getEventBus().emit("mainpanel.view", Map.of("resource", focusedResource), null);
	}

	private void handleEdit(NuclrResource focusedResource) {
		if (currentConnection == null || focusedResource == null || focusedResource.isFolder()) {
			return;
		}
		NetEditService.instance(context).edit(currentConnection, focusedResource, context);
	}

	private void handleMakeFolder(Map<String, Object> data) {
		if (currentConnection == null) {
			return;
		}
		Path created = new NetMakeFolderService().makeFolder(currentFolder, context);
		if (created == null) {
			return;
		}
		invalidateCurrentFolderCache();
		data.put("result.refresh", true);
	}

	private void handleCreateFile(Map<String, Object> data) {
		if (currentConnection == null) {
			return;
		}
		Path created = new NetMakeFolderService().makeFile(currentFolder, context);
		if (created == null) {
			return;
		}
		invalidateCurrentFolderCache();
		data.put("result.refresh", true);
	}

	private void handleDelete(List<NuclrResource> selectedResources, NuclrResource focusedResource,
			Map<String, Object> data) {

		if (currentConnection == null) {
			return;
		}
		var targets = new ArrayList<NuclrResource>();
		if (selectedResources != null && !selectedResources.isEmpty()) {
			targets.addAll(selectedResources);
		} else if (focusedResource != null) {
			targets.add(focusedResource);
		}
		if (new NetDeleteService().delete(targets, context)) {
			invalidateCurrentFolderCache();
			data.put("result.refresh", true);
		}
	}

	private void handleTail(NuclrResource focusedResource) {
		if (currentConnection == null || focusedResource == null || focusedResource.isFolder()) {
			return;
		}
		String remotePath = focusedResource.getMetadata(NetResource.KeyRemotePath, (String) null);
		if (remotePath == null) {
			return;
		}
		Window owner = activeWindow();
		SwingUtilities.invokeLater(() -> new NetTailWindow(owner, currentConnection, remotePath).setVisible(true));
	}

	private void handleFind() {
		if (currentConnection == null || !(currentFolder instanceof NetResource folder)) {
			return;
		}
		Window owner = activeWindow();
		var connection = currentConnection;
		SwingUtilities.invokeLater(() -> new NetFindDialog(owner, folder.remotePath(),
				request -> openFindResults(owner, connection, request)).setVisible(true));
	}

	private void openFindResults(Window owner, NetConnection connection, NetFindRequest request) {
		new NetFindResultsWindow(owner, connection, request, this::navigateToFindResult).setVisible(true);
	}

	/** Navigate the panel to a Find result: open its parent folder and select it. */
	private void navigateToFindResult(String remotePath) {

		if (currentConnection == null) {
			return;
		}
		String serverId = currentConnection.serverId();
		String parentPath = RemotePaths.parent(remotePath);
		NetResource parentFolder = directoryPlaceholder(serverId, currentConnection,
				parentPath == null ? "/" : parentPath, null);

		NetResource target = null;
		try {
			var attrs = currentConnection.statOrNull(remotePath);
			if (attrs != null) {
				target = new NetResource(context, serverId, currentConnection.path(remotePath), remotePath, attrs,
						attrs.isSymbolicLink());
			}
		} catch (IOException e) {
			log.debug("Could not stat find result {}: {}", remotePath, e.getMessage());
		}

		var payload = new HashMap<String, Object>();
		payload.put("resource", parentFolder);
		if (target != null) {
			payload.put("selectChild", target);
		}
		context.getEventBus().emit(this, "filepanel.path.opened", payload);
	}

	// =========================================================================
	// Server profile CRUD
	// =========================================================================

	private void handleNewServer(Map<String, Object> data) {

		var result = NetConnectionDialog.show(activeWindow(), "New Server", new ServerConfig());
		if (result == null) {
			return;
		}
		if (!saveProfile(result)) {
			return;
		}
		data.put("result.refresh", true);
		Alerts.confirmation(context);
	}

	private void handleEditServer(NuclrResource focusedResource, Map<String, Object> data) {

		String serverId = NetVirtualResource.serverIdOf(focusedResource);
		if (serverId == null) {
			Alerts.showError(context, "Edit Server", "Select a server to edit.");
			return;
		}
		ServerConfig existing = serverStore.byId(serverId);
		if (existing == null) {
			Alerts.showError(context, "Edit Server", "Server profile not found.");
			return;
		}

		var result = NetConnectionDialog.show(activeWindow(), "Edit Server", existing);
		if (result == null) {
			return;
		}

		// Settings may have changed (host, auth, …): drop any live session and cached
		// listings so the next connect picks up the new configuration and content.
		ConnectionRegistry.closeAndRemove(serverId);
		NetDirectoryCache.invalidateServer(serverId);

		if (!saveProfile(result)) {
			return;
		}
		data.put("result.refresh", true);
		Alerts.confirmation(context);
	}

	private boolean saveProfile(NetConnectionDialog.Result result) {
		try {
			serverStore.upsert(result.config());
		} catch (IOException e) {
			Alerts.showError(context, "Server Profile", "Could not save the server profile: " + e.getMessage());
			return false;
		}
		if (result.password() != null) {
			ConnectionRegistry.cachePassword(result.config().getId(), result.password());
		}
		if (result.passphrase() != null) {
			ConnectionRegistry.cachePassphrase(result.config().getId(), result.passphrase());
		}
		return true;
	}

	private void handleRemoveServer(List<NuclrResource> selectedResources, NuclrResource focusedResource,
			Map<String, Object> data) {

		var targets = new ArrayList<NuclrResource>();
		if (selectedResources != null && !selectedResources.isEmpty()) {
			targets.addAll(selectedResources);
		} else if (focusedResource != null) {
			targets.add(focusedResource);
		}

		var ids = targets.stream()
				.map(NetVirtualResource::serverIdOf)
				.filter(Objects::nonNull)
				.toList();
		if (ids.isEmpty()) {
			return;
		}

		if (!Alerts.confirmDestructive(context, "Remove Server",
				"Remove " + ids.size() + " server profile(s)? Any open connection will be closed.")) {
			Alerts.cancel(context);
			return;
		}

		for (String id : ids) {
			ConnectionRegistry.closeAndRemove(id);
			NetDirectoryCache.invalidateServer(id);
			try {
				serverStore.remove(id);
			} catch (IOException e) {
				log.warn("Could not remove server profile {}: {}", id, e.getMessage());
			}
		}

		data.put("result.refresh", true);
		Alerts.processComplete(context);
	}

	// =========================================================================
	// Helpers
	// =========================================================================

	private static Window activeWindow() {
		return KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
	}

	private static synchronized ServerKeyVerifier hostKeyVerifier() {
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
