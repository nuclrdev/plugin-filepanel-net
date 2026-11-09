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
package dev.nuclr.plugin.core.panel.net.remote;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.Path;

import dev.nuclr.plugin.core.panel.net.ssh.ServerConfig;

/**
 * One live connection to a saved server, independent of the protocol behind
 * it. Remote files reach the rest of the commander as NIO {@link Path}s on
 * {@link #fileSystem()} (copy engines, editors, viewers all go through those);
 * this interface adds what the plugin itself needs beyond plain NIO: fast
 * listings with full attributes, permissions and atomic replacement.
 *
 * <p>Protocol-specific extras are separate capabilities the caller checks for,
 * such as {@link dev.nuclr.plugin.core.panel.net.ssh.ShellAccess} for remote
 * commands, shells and SCP.
 *
 * <p>Implementations open lazily and reconnect transparently: every operation
 * goes through {@link #ensureOpen()}. They are thread-safe and shared by both
 * panel sides through {@code ConnectionRegistry}.
 */
public interface NetConnection extends Closeable {

	/**
	 * Supplies secrets when the connection (re)opens. Implementations prompt
	 * the user on first use and cache the value for the session lifetime.
	 */
	interface CredentialsProvider {

		/**
		 * Return the login password for a password-authenticated profile.
		 *
		 * @param config     the profile being opened
		 * @param retryIndex 0 on first attempt, incremented after a failed try
		 * @return the password, or {@code null} if the user cancelled
		 */
		String password(ServerConfig config, int retryIndex);

		/**
		 * Return the private-key passphrase for a key-authenticated profile.
		 * Only invoked when the key file is actually encrypted.
		 *
		 * @param config     the profile being opened
		 * @param retryIndex 0 on first attempt, incremented after a failed try
		 * @return the passphrase, or {@code null} if the user cancelled
		 */
		String passphrase(ServerConfig config, int retryIndex);
	}

	/**
	 * Return the profile this connection belongs to.
	 *
	 * @return the server profile
	 */
	ServerConfig config();

	/**
	 * Return the profile id (registry key).
	 *
	 * @return the server id
	 */
	default String serverId() {
		return config().getId();
	}

	/**
	 * Return whether the connection is currently usable without reconnecting.
	 *
	 * @return {@code true} when open
	 */
	boolean isOpen();

	/**
	 * Open the connection if it is not already open, transparently rebuilding a
	 * dropped one. All remote work must call this first.
	 *
	 * @throws IOException if connecting, server verification or authentication fails
	 */
	void ensureOpen() throws IOException;

	/**
	 * Return the login home directory resolved when the connection opened.
	 *
	 * @return the remote home directory, or {@code /} when unknown
	 */
	String home();

	/**
	 * Return the NIO filesystem the server's files are exposed through.
	 *
	 * @return the remote filesystem
	 * @throws IOException if the connection cannot be (re)opened
	 */
	FileSystem fileSystem() throws IOException;

	/**
	 * Resolve an absolute remote path string against {@link #fileSystem()}.
	 *
	 * @param remotePath the absolute remote path
	 * @return the NIO path
	 * @throws IOException if the connection cannot be (re)opened
	 */
	Path path(String remotePath) throws IOException;

	/**
	 * List a remote directory. Close the listing when done.
	 *
	 * @param remotePath the absolute remote directory path
	 * @return the entries, without {@code .} and {@code ..}
	 * @throws IOException if the directory cannot be opened
	 */
	RemoteListing list(String remotePath) throws IOException;

	/**
	 * Stat a remote path, following symlinks.
	 *
	 * @param remotePath the absolute remote path
	 * @return the attributes, or {@code null} when the path does not exist
	 * @throws IOException on transport errors
	 */
	RemoteAttributes statOrNull(String remotePath) throws IOException;

	/**
	 * Return the server's own absolute form of an existing path, with symlinks
	 * and {@code ..} resolved.
	 *
	 * @param remotePath the remote path
	 * @return the canonical path, or {@code remotePath} itself when the server
	 *         cannot resolve it
	 * @throws IOException on transport errors
	 */
	String canonicalPath(String remotePath) throws IOException;

	/**
	 * Open a remote file for reading.
	 *
	 * @param remotePath the absolute remote file path
	 * @return the content stream; the caller closes it
	 * @throws IOException if the file cannot be opened
	 */
	InputStream read(String remotePath) throws IOException;

	/**
	 * Delete a remote file (not a directory).
	 *
	 * @param remotePath the absolute remote file path
	 * @throws IOException if the file cannot be deleted
	 */
	void delete(String remotePath) throws IOException;

	/**
	 * Set the POSIX permission bits of a remote path.
	 *
	 * @param remotePath  the absolute remote path
	 * @param permissions the mode bits, e.g. {@code 0644}
	 * @throws IOException if the server refuses
	 */
	void setPermissions(String remotePath, int permissions) throws IOException;

	/**
	 * Replace {@code targetPath} with {@code sourcePath}, atomically where the
	 * server supports it.
	 *
	 * @param sourcePath the freshly uploaded sibling file
	 * @param targetPath the final destination
	 * @throws IOException if the replacement fails
	 */
	void atomicReplace(String sourcePath, String targetPath) throws IOException;

	/**
	 * Close the connection. It can be reopened later via {@link #ensureOpen()}.
	 */
	@Override
	void close();

}
