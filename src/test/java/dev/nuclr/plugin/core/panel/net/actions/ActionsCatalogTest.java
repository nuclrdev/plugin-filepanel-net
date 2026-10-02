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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.panel.net.NetFilePanelPlugin;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Keeps actions.json and the code in step, and the file inside the rules of the
 * SDK's actions schema (platform-sdk docs/actions-schema.md).
 */
class ActionsCatalogTest {

	private static final Set<String> CLASSES = Set.of("read", "write", "unrestricted");
	private static final Set<String> EXPOSURES = Set.of("agent", "palette");
	private static final Set<String> TYPES = Set.of("object", "string", "integer", "number", "boolean", "array");

	private final JsonNode catalog = new ObjectMapper().readTree(new NetFilePanelPlugin().getActionsJson());

	@Test
	void theSdkDefaultFindsTheFileNextToThePluginClass() {
		assertNotNull(new NetFilePanelPlugin().getActionsJson());
	}

	@Test
	void declaresExactlyTheActionsTheCodeHandles() {
		assertEquals(1, catalog.path("schemaVersion").asInt());
		var ids = new HashSet<String>();
		for (JsonNode action : catalog.path("actions")) {
			assertTrue(ids.add(action.path("id").asString()), "duplicate id " + action.path("id"));
		}
		assertEquals(NetActions.IDS, ids);
	}

	@Test
	void everyActionFollowsTheSchemaRules() {
		for (JsonNode action : catalog.path("actions")) {
			String id = action.path("id").asString();
			assertTrue(id.matches("[a-z0-9._-]+") && id.length() <= 48 && id.contains("."), "id " + id);
			assertTrue(!action.path("title").asString("").isBlank(), id + ": title");
			assertTrue(!action.path("summary").asString("").isBlank(), id + ": summary");
			assertTrue(CLASSES.contains(action.path("class").asString()), id + ": class");
			for (JsonNode exposure : action.path("exposure")) {
				assertTrue(EXPOSURES.contains(exposure.asString()), id + ": exposure " + exposure);
			}

			JsonNode input = action.path("input");
			assertEquals("object", input.path("type").asString(), id + ": input must be an object");
			for (JsonNode required : input.path("required")) {
				assertTrue(input.path("properties").has(required.asString()),
						id + ": required '" + required.asString() + "' is not a property");
			}
			checkSchema(id + ".input", input);
			if (action.has("output")) {
				checkSchema(id + ".output", action.path("output"));
			}
		}
	}

	@Test
	void everyDocFileExists() {
		for (JsonNode action : catalog.path("actions")) {
			String doc = action.path("doc").asString(null);
			if (doc != null) {
				assertNotNull(NetFilePanelPlugin.class.getResource(doc),
						action.path("id").asString() + ": missing " + doc + " next to actions.json");
			}
		}
	}

	@Test
	void reservedArgumentNamesAreNotDeclared() {
		for (JsonNode action : catalog.path("actions")) {
			for (String name : action.path("input").path("properties").propertyNames()) {
				assertTrue(!name.startsWith("_"), action.path("id").asString() + ": '" + name + "' is reserved");
			}
		}
	}

	private static void checkSchema(String where, JsonNode schema) {
		if (schema.has("type")) {
			assertTrue(TYPES.contains(schema.path("type").asString()), where + ": type " + schema.path("type"));
		}
		for (String name : schema.path("properties").propertyNames()) {
			checkSchema(where + "." + name, schema.path("properties").path(name));
		}
		if (schema.has("items")) {
			checkSchema(where + "[]", schema.path("items"));
		}
	}

}
