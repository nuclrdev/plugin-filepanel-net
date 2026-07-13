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

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * String-level helpers for absolute remote POSIX paths ({@code /}-separated).
 * Used wherever a remote location is handled as text (exec commands, config,
 * resource metadata) rather than as an SFTP NIO {@code Path}.
 */
public final class RemotePaths {

	private RemotePaths() {
	}

	/**
	 * Normalize a remote path: collapse repeated separators, resolve {@code .}
	 * and {@code ..} segments and drop any trailing separator. A blank input
	 * normalizes to {@code /}; {@code ..} never escapes the root.
	 *
	 * @param path the raw path text; may be {@code null} or blank
	 * @return the normalized absolute path, always starting with {@code /}
	 */
	public static String normalize(String path) {

		if (path == null || path.isBlank()) {
			return "/";
		}

		Deque<String> segments = new ArrayDeque<>();
		for (String segment : path.trim().split("/")) {
			if (segment.isEmpty() || segment.equals(".")) {
				continue;
			}
			if (segment.equals("..")) {
				segments.pollLast();
				continue;
			}
			segments.addLast(segment);
		}

		if (segments.isEmpty()) {
			return "/";
		}
		return "/" + String.join("/", segments);
	}

	/**
	 * Join a directory and a child name into a normalized absolute path.
	 *
	 * @param directory the parent directory
	 * @param name      the child entry name
	 * @return the normalized combined path
	 */
	public static String join(String directory, String name) {
		String base = normalize(directory);
		if (name == null || name.isBlank()) {
			return base;
		}
		return normalize(base + "/" + name);
	}

	/**
	 * Return the parent of a remote path, or {@code null} when the path is the
	 * root (which has no parent).
	 *
	 * @param path the path whose parent to compute
	 * @return the parent path, or {@code null} at the root
	 */
	public static String parent(String path) {
		String normalized = normalize(path);
		if (normalized.equals("/")) {
			return null;
		}
		int slash = normalized.lastIndexOf('/');
		return slash == 0 ? "/" : normalized.substring(0, slash);
	}

	/**
	 * Return the last segment of a remote path ({@code /} for the root).
	 *
	 * @param path the path whose file name to extract
	 * @return the entry name
	 */
	public static String name(String path) {
		String normalized = normalize(path);
		if (normalized.equals("/")) {
			return "/";
		}
		return normalized.substring(normalized.lastIndexOf('/') + 1);
	}

	/**
	 * Return {@code true} when {@code path} is the remote root.
	 *
	 * @param path the path to test
	 * @return {@code true} for {@code /}
	 */
	public static boolean isRoot(String path) {
		return normalize(path).equals("/");
	}

}
