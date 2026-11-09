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

import java.nio.file.attribute.FileTime;

/**
 * Protocol-neutral metadata of one remote entry, as a {@link NetConnection}
 * reports it from a listing or a stat.
 *
 * @param directory    whether the entry is a directory
 * @param regularFile  whether the entry is a regular file
 * @param symbolicLink whether the entry itself is a symbolic link (only
 *                     meaningful for listings and link-preserving stats)
 * @param size         size in bytes ({@code 0} when unknown)
 * @param modifyTime   last modification time, or {@code null} when unknown
 * @param accessTime   last access time, or {@code null} when unknown
 * @param createTime   creation time, or {@code null} when unknown
 * @param permissions  POSIX mode bits ({@code 0} when unknown)
 */
public record RemoteAttributes(
		boolean directory,
		boolean regularFile,
		boolean symbolicLink,
		long size,
		FileTime modifyTime,
		FileTime accessTime,
		FileTime createTime,
		int permissions) {
}
