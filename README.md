# Net Remote Panel

A [Nuclr Commander](https://nuclr.dev) plugin that adds a **Net** file panel root for browsing and transferring files over SFTP and SCP. Server profiles are managed directly from the panel using keyboard shortcuts and stored in a local JSON file.

## Features

- SFTP and SCP file browsing via Apache MINA SSHD
- Password and private key authentication
- Lazy connections — opened on first access, closed when the profile is removed
- Copy/move files between local and remote filesystems using the standard Commander copy workflow
- Server profiles managed with `Shift+F4` / `F4` shortcuts — no config file editing required
- FTP protocol placeholder (not yet functional — reserved for a future release)

## Requirements

- Nuclr Commander with plugin support
- Java 21+
- Nuclr Plugin SDK 1.0.0 installed in local Maven repository

## Building

The plugin SDK must be installed first:

```bash
cd plugins-sdk
mvn clean install
```

Then build the plugin:

```bash
cd plugins/core/filepanel-net
mvn clean verify -Djarsigner.storepass=<keystore-password>
```

The build produces a signed plugin archive in `target/`:

```
target/
  filepanel-net-1.0.0.zip      # plugin archive
  filepanel-net-1.0.0.zip.sig  # RSA SHA-256 signature
```

> Signing requires the keystore at `C:/nuclr/key/nuclr-signing.p12`.

## Installation

Copy the ZIP and its signature into the Commander `plugins/` directory:

```bash
copy target\filepanel-net-1.0.0.zip     <commander>/plugins/
copy target\filepanel-net-1.0.0.zip.sig <commander>/plugins/
```

Or run `deploy.bat` to build and deploy in one step (targets `C:\nuclr\sources\commander\plugins\`).

## Usage

After installation, a **Net** root appears in the drive selector. Navigate into it to see the server list.

| Shortcut    | Action                        |
|-------------|-------------------------------|
| `Shift+F4`  | Create a new server profile   |
| `F4`        | Edit the selected/current profile |
| `Enter`     | Connect and open the remote root  |

### Creating a server profile

Press `Shift+F4` from anywhere in the Net panel. Fill in the dialog:

| Field            | Description                                              |
|------------------|----------------------------------------------------------|
| Protocol         | `sftp` or `scp` (active); `ftp` (placeholder)           |
| Host             | Hostname or IP address                                   |
| Port             | Default: 22 for SFTP/SCP                                |
| Username         | SSH username                                             |
| Password         | Password (leave blank when using a private key)          |
| Private Key Path | Absolute path to a PEM private key file (optional)      |

### Server configuration file

Profiles are persisted at:

```
~/.nuclr/net/servers.json
```

Example:

```json
[
  {
    "id": "a1b2c3d4-...",
    "protocol": "sftp",
    "host": "example.com",
    "port": 22,
    "username": "alice",
    "password": "",
    "privateKeyPath": "/home/alice/.ssh/id_rsa"
  }
]
```

## Plugin metadata

| Field   | Value                              |
|---------|------------------------------------|
| ID      | `dev.nuclr.plugin.core.panel.net`  |
| Version | 1.0.0                              |
| License | Apache-2.0                         |
| Author  | Nuclr Development Team             |
