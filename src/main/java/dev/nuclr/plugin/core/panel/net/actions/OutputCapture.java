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
package dev.nuclr.plugin.core.panel.net.actions;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import dev.nuclr.platform.plugin.NuclrPluginCallback;

/**
 * One stream of a remote command's output: everything is passed on as text through
 * {@link NuclrPluginCallback#onOutput} as it arrives, and the last {@code limit}
 * bytes are kept for the action's result. The tail is what matters - errors and
 * summaries come last.
 *
 * <p>Chunks from the server can split a UTF-8 character; the incomplete bytes are
 * held back until the rest arrives, so streamed text is never garbled.
 */
final class OutputCapture extends OutputStream {

	private final NuclrPluginCallback callback;
	private final int limit;
	private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
			.onMalformedInput(CodingErrorAction.REPLACE)
			.onUnmappableCharacter(CodingErrorAction.REPLACE);

	private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
	private byte[] carry = new byte[0];
	private long dropped;

	OutputCapture(NuclrPluginCallback callback, int limit) {
		this.callback = callback;
		this.limit = limit;
	}

	@Override
	public synchronized void write(int b) {
		write(new byte[] { (byte) b }, 0, 1);
	}

	@Override
	public synchronized void write(byte[] bytes, int offset, int length) {
		if (length <= 0) {
			return;
		}
		keep(bytes, offset, length);
		stream(bytes, offset, length, false);
	}

	/** Pass on whatever incomplete character is still held back. Call once, at the end. */
	synchronized void finish() {
		stream(new byte[0], 0, 0, true);
	}

	/** The kept output as text, marked when earlier output was dropped. */
	synchronized String text() {
		byte[] bytes = kept.toByteArray();
		long omitted = dropped;
		int start = 0;
		if (bytes.length > limit) {
			start = bytes.length - limit;
			omitted += start;
		}
		// Do not start in the middle of a character.
		while (start < bytes.length && start > 0 && (bytes[start] & 0xC0) == 0x80) {
			start++;
			omitted++;
		}
		String text = new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
		return omitted > 0 ? "[... " + omitted + " earlier bytes not shown ...]\n" + text : text;
	}

	/** Whether the result text leaves out earlier output. */
	synchronized boolean truncated() {
		return dropped > 0 || kept.size() > limit;
	}

	private void keep(byte[] bytes, int offset, int length) {
		kept.write(bytes, offset, length);
		// Trim only once twice the limit is held, so trimming stays rare.
		if (kept.size() > 2L * limit) {
			byte[] all = kept.toByteArray();
			kept.reset();
			kept.write(all, all.length - limit, limit);
			dropped += all.length - limit;
		}
	}

	private void stream(byte[] bytes, int offset, int length, boolean end) {
		ByteBuffer in = ByteBuffer.allocate(carry.length + length);
		in.put(carry).put(bytes, offset, length).flip();
		CharBuffer out = CharBuffer.allocate(in.remaining() + 1);
		decoder.decode(in, out, end);
		if (end) {
			decoder.flush(out);
			decoder.reset();
		}
		carry = new byte[in.remaining()];
		in.get(carry);
		out.flip();
		if (out.hasRemaining()) {
			callback.onOutput(out.toString());
		}
	}

}
