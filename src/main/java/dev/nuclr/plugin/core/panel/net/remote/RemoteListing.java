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

/**
 * A lazily read remote directory listing. Entries may arrive while iterating,
 * so a caller can stop early; transport failures during iteration surface as
 * {@link java.io.UncheckedIOException}. Close it to release the underlying
 * channel.
 */
public interface RemoteListing extends Iterable<RemoteEntry>, Closeable {
}
