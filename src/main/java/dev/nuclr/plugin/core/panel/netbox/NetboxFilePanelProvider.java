package dev.nuclr.plugin.core.panel.netbox;

import java.awt.GridLayout;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.sftp.client.fs.SftpFileSystemProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.nuclr.plugin.panel.CollisionAction;
import dev.nuclr.plugin.panel.CopyOptions;
import dev.nuclr.plugin.panel.CopyProgress;
import dev.nuclr.plugin.panel.CopyResult;
import dev.nuclr.plugin.panel.CopyStatus;
import dev.nuclr.plugin.panel.FilePanelProvider;
import dev.nuclr.plugin.panel.PanelFunctionKeyHandler;
import dev.nuclr.plugin.panel.PanelRoot;

public class NetboxFilePanelProvider implements FilePanelProvider {
    private static final Logger log = LoggerFactory.getLogger(NetboxFilePanelProvider.class);

    private static final int SSH_CONNECT_TIMEOUT_MS = 4_000;
    private static final int SSH_AUTH_TIMEOUT_MS = 8_000;

    private final ObjectMapper mapper = new ObjectMapper();
    private final SftpFileSystemProvider sftpProvider = new SftpFileSystemProvider();
    private final Map<String, MountedConnection> mounts = new ConcurrentHashMap<>();

    private final Path mountBase = Path.of(System.getProperty("java.io.tmpdir"), "nuclr", "netbox");
    private final Path serverListRoot = mountBase.resolve("Servers");
    private final Path unsupportedRoot = mountBase.resolve("unsupported");
    private final Path configFile = Path.of(System.getProperty("user.home"), ".nuclr", "netbox", "servers.json");

    @Override
    public String id() {
        return "netbox";
    }

    @Override
    public String displayName() {
        return "NetBox";
    }

    @Override
    public int priority() {
        return 15;
    }

    @Override
    public synchronized List<PanelRoot> roots() {
        ensureLayout();
        var configs = loadConfigs();
        var roots = new ArrayList<PanelRoot>();
        roots.add(new PanelRoot("NetBox Servers", serverListRoot));

        var activeIds = new java.util.HashSet<String>();
        for (ServerConfig cfg : configs) {
            activeIds.add(cfg.id());
            String label = cfg.protocol().toUpperCase() + ": " + cfg.host() + ":" + cfg.port() + " (" + cfg.username() + ")";

            if ("ftp".equalsIgnoreCase(cfg.protocol())) {
                Path placeholder = unsupportedRoot.resolve(cfg.id());
                writeStatus(placeholder, "FTP support is planned.\nThis build supports SFTP and SCP now.");
                roots.add(new PanelRoot(label, placeholder));
                continue;
            }

            try {
                MountedConnection mounted = mounts.get(cfg.id());
                if (mounted == null || mounted.closed()) {
                    mounted = openConnection(cfg);
                    mounts.put(cfg.id(), mounted);
                }
                roots.add(new PanelRoot(label, mounted.root()));
            } catch (Exception ex) {
                Path placeholder = unsupportedRoot.resolve(cfg.id());
                writeStatus(placeholder, "Cannot connect:\n" + ex.getMessage());
                roots.add(new PanelRoot(label, placeholder));
            }
        }

        // Drop stale mounts removed from configuration.
        for (String id : List.copyOf(mounts.keySet())) {
            if (!activeIds.contains(id)) {
                closeQuietly(mounts.remove(id));
            }
        }

        return roots;
    }

    @Override
    public boolean supportsPath(Path path) {
        if (path == null) {
            return false;
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(serverListRoot.toAbsolutePath().normalize())
                || normalized.startsWith(unsupportedRoot.toAbsolutePath().normalize())) {
            return true;
        }
        for (MountedConnection mount : mounts.values()) {
            if (!mount.closed() && path.getFileSystem().equals(mount.fileSystem())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public PanelFunctionKeyHandler functionKeyHandler() {
        return (functionKeyNumber, shiftDown, currentDirectory, selectedPath) -> {
            if (functionKeyNumber != 4) {
                return false;
            }
            if (shiftDown) {
                return createServerConfig();
            }
            return editServerConfig(currentDirectory, selectedPath);
        };
    }

    @Override
    public String validateCopy(List<Path> items, Path targetDirectory) {
        if (items == null || items.isEmpty()) {
            return "Nothing selected.";
        }
        if (targetDirectory == null || !Files.exists(targetDirectory) || !Files.isDirectory(targetDirectory)) {
            return "Target directory is not available.";
        }
        if (isUnsupportedPath(targetDirectory)) {
            return "Target server protocol is not supported for file operations.";
        }
        for (Path item : items) {
            if (isUnsupportedPath(item)) {
                return "Source server protocol is not supported for file operations.";
            }
        }
        return null;
    }

    @Override
    public CopyResult copy(List<Path> items, Path targetDirectory, CopyOptions options, CopyProgress progress) {
        var errors = new ArrayList<String>();
        int copied = 0;

        for (Path source : items) {
            if (progress.isCancelled()) {
                return CopyResult.cancelled(copied, errors);
            }

            String targetName = options.targetNameOverride() != null
                    ? options.targetNameOverride()
                    : source.getFileName().toString();
            Path target = targetDirectory.resolve(targetName);
            try {
                copied += copyPath(source, target, options, progress);
            } catch (IOException ex) {
                errors.add(source + " -> " + ex.getMessage());
            }
        }

        if (!errors.isEmpty()) {
            return copied > 0
                    ? CopyResult.partial(copied, errors)
                    : new CopyResult(CopyStatus.FAILED, copied, List.copyOf(errors), "Copy failed.");
        }
        return CopyResult.success(copied);
    }

    private int copyPath(Path source, Path target, CopyOptions options, CopyProgress progress) throws IOException {
        if (progress.isCancelled()) {
            return 0;
        }
        progress.onItemStarted(source, target);

        Path resolvedTarget = resolveTarget(target, options.collisionAction());
        if (resolvedTarget == null) {
            return 0;
        }

        if (Files.isDirectory(source)) {
            Files.createDirectories(resolvedTarget);
            int copied = 1;
            if (!options.recursive()) {
                progress.onItemCompleted(source, resolvedTarget);
                return copied;
            }
            try (var stream = Files.list(source)) {
                for (Path child : stream.toList()) {
                    copied += copyPath(child, resolvedTarget.resolve(child.getFileName().toString()), options, progress);
                }
            }
            progress.onItemCompleted(source, resolvedTarget);
            return copied;
        }

        Path parent = resolvedTarget.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(source, resolvedTarget, StandardCopyOption.REPLACE_EXISTING);
        progress.onItemCompleted(source, resolvedTarget);
        return 1;
    }

    private Path resolveTarget(Path target, CollisionAction action) throws IOException {
        if (!Files.exists(target)) {
            return target;
        }
        return switch (action) {
            case OVERWRITE -> target;
            case SKIP -> null;
            case KEEP_BOTH -> uniqueSibling(target);
        };
    }

    private Path uniqueSibling(Path target) {
        String name = target.getFileName().toString();
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        Path candidate = target.resolveSibling(base + " - Copy" + ext);
        int index = 2;
        while (Files.exists(candidate)) {
            candidate = target.resolveSibling(base + " - Copy (" + index + ")" + ext);
            index++;
        }
        return candidate;
    }

    private MountedConnection openConnection(ServerConfig cfg) throws IOException, GeneralSecurityException {
        int port = cfg.port() > 0 ? cfg.port() : defaultPort(cfg.protocol());
        if ("scp".equalsIgnoreCase(cfg.protocol()) || "sftp".equalsIgnoreCase(cfg.protocol())) {
            if (cfg.privateKeyPath() != null && !cfg.privateKeyPath().isBlank()) {
                return connectWithKey(cfg, port);
            }
            var uri = SftpFileSystemProvider.createFileSystemURI(cfg.host(), port, cfg.username(), cfg.password());
            FileSystem fs;
            try {
                fs = sftpProvider.newFileSystem(uri, Map.of());
            } catch (FileSystemAlreadyExistsException ex) {
                fs = sftpProvider.getFileSystem(uri);
            }
            return new MountedConnection(cfg.id(), cfg.protocol(), fs, fs.getPath("/"), null, null);
        }
        throw new IOException("Unsupported protocol: " + cfg.protocol());
    }

    private MountedConnection connectWithKey(ServerConfig cfg, int port) throws IOException, GeneralSecurityException {
        var client = SshClient.setUpDefaultClient();
        client.start();

        var session = client.connect(cfg.username(), cfg.host(), port)
                .verify(SSH_CONNECT_TIMEOUT_MS)
                .getSession();

        if (cfg.password() != null && !cfg.password().isBlank()) {
            session.addPasswordIdentity(cfg.password());
        }

        Path keyPath = Path.of(cfg.privateKeyPath());
        try (InputStream in = Files.newInputStream(keyPath)) {
            var pairs = SecurityUtils.loadKeyPairIdentities(
                    session,
                    NamedResource.ofName(keyPath.toString()),
                    in,
                    FilePasswordProvider.of(cfg.password()));
            for (var pair : pairs) {
                session.addPublicKeyIdentity(pair);
            }
        }

        session.auth().verify(SSH_AUTH_TIMEOUT_MS);
        FileSystem fs = sftpProvider.newFileSystem(session);
        return new MountedConnection(cfg.id(), cfg.protocol(), fs, fs.getPath("/"), client, session);
    }

    private boolean createServerConfig() {
        ServerConfig draft = new ServerConfig(
                UUID.randomUUID().toString(),
                "sftp",
                "",
                22,
                "",
                "",
                "");
        ServerConfig created = showConfigDialog("Create NetBox Server", draft);
        if (created == null) {
            return true;
        }
        var all = new ArrayList<>(loadConfigs());
        all.add(created);
        saveConfigs(all);
        return true;
    }

    private boolean editServerConfig(Path currentDirectory, Path selectedPath) {
        var all = new ArrayList<>(loadConfigs());
        if (all.isEmpty()) {
            JOptionPane.showMessageDialog(null, "No NetBox server configs yet. Use Shift+F4 to create one.");
            return true;
        }

        ServerConfig selected = resolveSelectedConfig(currentDirectory, selectedPath, all);
        if (selected == null) {
            Object[] names = all.stream().map(this::configTitle).toArray();
            Object picked = JOptionPane.showInputDialog(
                    null,
                    "Choose server to edit:",
                    "Edit NetBox Server",
                    JOptionPane.PLAIN_MESSAGE,
                    null,
                    names,
                    names[0]);
            if (picked == null) {
                return true;
            }
            selected = all.stream().filter(c -> Objects.equals(configTitle(c), picked)).findFirst().orElse(null);
        }
        if (selected == null) {
            return true;
        }

        ServerConfig edited = showConfigDialog("Edit NetBox Server", selected);
        if (edited == null) {
            return true;
        }

        for (int i = 0; i < all.size(); i++) {
            if (Objects.equals(all.get(i).id(), edited.id())) {
                all.set(i, edited);
                break;
            }
        }
        saveConfigs(all);
        closeQuietly(mounts.remove(edited.id()));
        return true;
    }

    private ServerConfig resolveSelectedConfig(Path currentDirectory, Path selectedPath, List<ServerConfig> all) {
        Path probe = selectedPath != null ? selectedPath : currentDirectory;
        if (probe == null) {
            return null;
        }

        for (var entry : mounts.entrySet()) {
            MountedConnection mount = entry.getValue();
            if (!mount.closed() && probe.getFileSystem().equals(mount.fileSystem())) {
                String id = entry.getKey();
                return all.stream().filter(c -> Objects.equals(c.id(), id)).findFirst().orElse(null);
            }
        }

        Path normalized = probe.toAbsolutePath().normalize();
        if (normalized.startsWith(unsupportedRoot.toAbsolutePath().normalize())) {
            if (normalized.getNameCount() > unsupportedRoot.toAbsolutePath().normalize().getNameCount()) {
                String id = normalized.getName(unsupportedRoot.toAbsolutePath().normalize().getNameCount()).toString();
                return all.stream().filter(c -> Objects.equals(c.id(), id)).findFirst().orElse(null);
            }
        }
        return null;
    }

    private ServerConfig showConfigDialog(String title, ServerConfig initial) {
        JTextField host = new JTextField(initial.host());
        JTextField port = new JTextField(String.valueOf(initial.port()));
        JTextField username = new JTextField(initial.username());
        JPasswordField password = new JPasswordField(initial.password());
        JTextField privateKey = new JTextField(initial.privateKeyPath());
        JComboBox<String> protocol = new JComboBox<>(new String[] {"ftp", "sftp", "scp"});
        protocol.setSelectedItem(initial.protocol());

        JPanel panel = new JPanel(new GridLayout(0, 2, 8, 4));
        panel.add(new JLabel("Protocol"));
        panel.add(protocol);
        panel.add(new JLabel("Host"));
        panel.add(host);
        panel.add(new JLabel("Port"));
        panel.add(port);
        panel.add(new JLabel("Username"));
        panel.add(username);
        panel.add(new JLabel("Password"));
        panel.add(password);
        panel.add(new JLabel("Private Key Path"));
        panel.add(privateKey);

        int result = JOptionPane.showConfirmDialog(null, panel, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return null;
        }

        String hostValue = host.getText().strip();
        String userValue = username.getText().strip();
        if (hostValue.isBlank() || userValue.isBlank()) {
            JOptionPane.showMessageDialog(null, "Host and username are required.");
            return null;
        }

        int portValue;
        try {
            String rawPort = port.getText().strip();
            portValue = rawPort.isBlank() ? defaultPort((String) protocol.getSelectedItem()) : Integer.parseInt(rawPort);
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(null, "Port must be numeric.");
            return null;
        }

        return new ServerConfig(
                initial.id(),
                String.valueOf(protocol.getSelectedItem()),
                hostValue,
                portValue,
                userValue,
                new String(password.getPassword()),
                privateKey.getText().strip());
    }

    private String configTitle(ServerConfig cfg) {
        return cfg.protocol().toUpperCase() + " " + cfg.host() + ":" + cfg.port() + " (" + cfg.username() + ")";
    }

    private synchronized void ensureLayout() {
        try {
            Files.createDirectories(serverListRoot);
            Files.createDirectories(unsupportedRoot);
            Files.createDirectories(configFile.getParent());
            if (!Files.exists(configFile)) {
                saveConfigs(List.of());
            }
            Files.writeString(
                    serverListRoot.resolve("README.txt"),
                    "NetBox server list is configured via shortcuts:\n"
                            + "Shift+F4 - create server\n"
                            + "F4 - edit server\n\n"
                            + "Configuration file:\n"
                            + configFile,
                    StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new RuntimeException("Cannot initialize NetBox layout: " + ex.getMessage(), ex);
        }
    }

    private List<ServerConfig> loadConfigs() {
        ensureLayout();
        try {
            List<ServerConfig> raw = mapper.readValue(configFile.toFile(), new TypeReference<List<ServerConfig>>() {});
            var dedup = new LinkedHashMap<String, ServerConfig>();
            for (ServerConfig cfg : raw) {
                String id = (cfg.id() == null || cfg.id().isBlank()) ? UUID.randomUUID().toString() : cfg.id();
                dedup.put(id, new ServerConfig(
                        id,
                        normalizeProtocol(cfg.protocol()),
                        safe(cfg.host()),
                        cfg.port(),
                        safe(cfg.username()),
                        safe(cfg.password()),
                        safe(cfg.privateKeyPath())));
            }
            return dedup.values().stream()
                    .sorted(Comparator.comparing(ServerConfig::protocol).thenComparing(ServerConfig::host))
                    .toList();
        } catch (Exception ex) {
            log.warn("Cannot read NetBox config: {}", ex.getMessage());
            return List.of();
        }
    }

    private void saveConfigs(List<ServerConfig> configs) {
        try {
            Files.createDirectories(configFile.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(configFile.toFile(), configs);
        } catch (IOException ex) {
            throw new RuntimeException("Cannot save NetBox config: " + ex.getMessage(), ex);
        }
    }

    private void writeStatus(Path dir, String text) {
        try {
            Files.createDirectories(dir);
            Files.writeString(
                    dir.resolve("README.txt"),
                    text + "\n\nTimestamp: " + Instant.now(),
                    StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private boolean isUnsupportedPath(Path path) {
        if (path == null) {
            return false;
        }
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.startsWith(unsupportedRoot.toAbsolutePath().normalize());
    }

    private static String normalizeProtocol(String protocol) {
        String p = safe(protocol).toLowerCase();
        return switch (p) {
            case "ftp", "sftp", "scp" -> p;
            default -> "sftp";
        };
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static int defaultPort(String protocol) {
        if ("ftp".equalsIgnoreCase(protocol)) {
            return 21;
        }
        return 22;
    }

    private void closeQuietly(MountedConnection mount) {
        if (mount == null) {
            return;
        }
        try {
            mount.close();
        } catch (Exception ignored) {
        }
    }

    private record ServerConfig(
            String id,
            String protocol,
            String host,
            int port,
            String username,
            String password,
            String privateKeyPath) {
    }

    private static final class MountedConnection implements AutoCloseable {
        private final String id;
        private final String protocol;
        private final FileSystem fileSystem;
        private final Path root;
        private final SshClient client;
        private final ClientSession session;

        private MountedConnection(
                String id,
                String protocol,
                FileSystem fileSystem,
                Path root,
                SshClient client,
                ClientSession session) {
            this.id = id;
            this.protocol = protocol;
            this.fileSystem = fileSystem;
            this.root = root;
            this.client = client;
            this.session = session;
        }

        private FileSystem fileSystem() {
            return fileSystem;
        }

        private Path root() {
            return root;
        }

        private boolean closed() {
            return !fileSystem.isOpen();
        }

        @Override
        public void close() throws Exception {
            try {
                if (fileSystem.isOpen()) {
                    fileSystem.close();
                }
            } finally {
                try {
                    if (session != null && session.isOpen()) {
                        session.close();
                    }
                } finally {
                    if (client != null && client.isOpen()) {
                        client.close();
                    }
                }
            }
        }
    }
}
