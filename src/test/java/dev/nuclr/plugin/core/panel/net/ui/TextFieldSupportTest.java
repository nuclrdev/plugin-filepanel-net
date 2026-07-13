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
package dev.nuclr.plugin.core.panel.net.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.JTextField;
import javax.swing.text.BadLocationException;

import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link TextFieldSupport#limitLength}: the document
 * filter runs regardless of whether the field is ever shown, so these run
 * without a display. {@code install}'s undo/redo and popup-menu wiring is UI
 * plumbing exercised manually via the connection dialog rather than unit
 * tested here.
 */
class TextFieldSupportTest {

	@Test
	void typingWithinLimitIsUnaffected() throws BadLocationException {
		var field = new JTextField();
		TextFieldSupport.limitLength(field, 10);

		field.getDocument().insertString(0, "short", null);

		assertEquals("short", field.getText());
	}

	@Test
	void typingBeyondLimitIsRejected() throws BadLocationException {
		var field = new JTextField();
		TextFieldSupport.limitLength(field, 5);

		field.getDocument().insertString(0, "12345", null);
		// A 6th character would exceed the limit and must be dropped entirely.
		field.getDocument().insertString(5, "6", null);

		assertEquals("12345", field.getText());
	}

	@Test
	void pastingLongTextIsTruncatedToFit() throws BadLocationException {
		var field = new JTextField();
		TextFieldSupport.limitLength(field, 5);

		field.getDocument().insertString(0, "1234567890", null);

		assertEquals("12345", field.getText());
		assertTrue(field.getText().length() <= 5);
	}

	@Test
	void replacingSelectionRespectsLimit() throws BadLocationException {
		var field = new JTextField();
		TextFieldSupport.limitLength(field, 5);
		field.getDocument().insertString(0, "abcde", null);

		// Replace the first 2 chars ("ab") with a longer string; the 5-char cap
		// leaves room for only 2 of the incoming replacement characters.
		field.getDocument().remove(0, 2);
		field.getDocument().insertString(0, "XYZW", null);

		assertEquals(5, field.getText().length());
	}

	@Test
	void fiftyCharLimitMatchesNameFieldRequirement() throws BadLocationException {
		var field = new JTextField();
		TextFieldSupport.limitLength(field, 50);

		String tooLong = "x".repeat(80);
		field.getDocument().insertString(0, tooLong, null);

		assertEquals(50, field.getText().length());
	}

}
