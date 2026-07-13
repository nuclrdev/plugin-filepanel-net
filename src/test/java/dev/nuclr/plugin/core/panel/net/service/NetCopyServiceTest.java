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
package dev.nuclr.plugin.core.panel.net.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.platform.plugin.NuclrResource;

/**
 * Exercises the plugin-agnostic selection/header helpers shared by copy and
 * move, using plain local paths — the logic under test never inspects the
 * filesystem provider, only the {@link NuclrResource} wrapper.
 */
class NetCopyServiceTest {

	@TempDir
	Path tempDir;

	private static final class TestResource extends NuclrResource {
		private static final long serialVersionUID = 1L;

		TestResource(Path path, String name) {
			super(path);
			this.name = name;
			setUuid(path == null ? name : path.toString());
		}
	}

	@Test
	void collectSourcesPrefersMarkedSelectionOverFocused() throws IOException {
		Path a = Files.createFile(tempDir.resolve("a.txt"));
		Path b = Files.createFile(tempDir.resolve("b.txt"));
		Path focused = Files.createFile(tempDir.resolve("focused.txt"));

		var selected = List.<NuclrResource>of(new TestResource(a, "a.txt"), new TestResource(b, "b.txt"));
		var focusedResource = new TestResource(focused, "focused.txt");

		List<Path> sources = NetCopyService.collectSources(selected, focusedResource);

		assertEquals(2, sources.size());
		assertTrue(sources.contains(a));
		assertTrue(sources.contains(b));
	}

	@Test
	void collectSourcesFallsBackToFocusedWhenNothingMarked() throws IOException {
		Path focused = Files.createFile(tempDir.resolve("focused.txt"));
		var focusedResource = new TestResource(focused, "focused.txt");

		List<Path> sources = NetCopyService.collectSources(List.of(), focusedResource);

		assertEquals(1, sources.size());
		assertEquals(focused, sources.get(0));
	}

	@Test
	void collectSourcesExcludesParentNavigationEntry() throws IOException {
		Path parent = tempDir.getParent() != null ? tempDir.getParent() : tempDir;
		var dotDot = new TestResource(parent, "..");

		List<Path> sources = NetCopyService.collectSources(List.of(dotDot), null);

		assertTrue(sources.isEmpty());
	}

	@Test
	void headerNamesSingleSourceBySimpleName() throws IOException {
		Path file = Files.createFile(tempDir.resolve("report.pdf"));
		assertEquals("report.pdf", NetCopyService.header(List.of(file)));
	}

	@Test
	void headerCountsMultipleSources() throws IOException {
		Path a = Files.createFile(tempDir.resolve("a.txt"));
		Path b = Files.createFile(tempDir.resolve("b.txt"));
		Path c = Files.createFile(tempDir.resolve("c.txt"));
		assertEquals("3 items", NetCopyService.header(List.of(a, b, c)));
	}

}
