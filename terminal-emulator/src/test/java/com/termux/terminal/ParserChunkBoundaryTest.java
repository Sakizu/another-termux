package com.termux.terminal;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * Regression tests for feeding input to the parser in small chunks across multiple
 * {@link TerminalEmulator#append(byte[], int)} calls.
 *
 * <p>Bytes arriving over a pty can be split at arbitrary positions, so multi-byte
 * UTF-8 sequences and escape sequences must survive append boundaries without
 * corrupting parser state or losing data.</p>
 */
public class ParserChunkBoundaryTest extends TerminalTestCase {

	/** Feed the given bytes one byte per append() call, checking screen invariants after each chunk. */
	private ParserChunkBoundaryTest feedByteByByte(byte[] data) {
		for (byte b : data) {
			mTerminal.append(new byte[]{b}, 1);
			assertInvariants();
		}
		return this;
	}

	private ParserChunkBoundaryTest feedByteByByte(String s) {
		return feedByteByByte(s.getBytes(StandardCharsets.UTF_8));
	}

	/** Feed the given bytes in fixed-size chunks (the last chunk may be shorter). */
	private ParserChunkBoundaryTest feedInChunks(byte[] data, int chunkSize) {
		for (int offset = 0; offset < data.length; offset += chunkSize) {
			int len = Math.min(chunkSize, data.length - offset);
			byte[] chunk = new byte[len];
			System.arraycopy(data, offset, chunk, 0, len);
			mTerminal.append(chunk, len);
			assertInvariants();
		}
		return this;
	}

	private ParserChunkBoundaryTest feedInChunks(String s, int chunkSize) {
		return feedInChunks(s.getBytes(StandardCharsets.UTF_8), chunkSize);
	}

	public void testMultibyteUtf8SplitAcrossAppendBoundaries() {
		// 2-byte (U+00E9), 3-byte (U+20AC) and 4-byte (U+1F600) sequences, each split at every
		// possible byte position by feeding one byte per append() call.
		withTerminalSized(20, 2);
		feedByteByByte("A\u00E9\u20AC\uD83D\uDE00B");
		assertLineStartsWith(0, 'A', 0x00E9, 0x20AC, 0x1F600, 'B', ' ');
		assertCursorAt(0, 6);

		// Same input in two uneven chunks must produce the same result.
		withTerminalSized(20, 2);
		feedInChunks("A\u00E9\u20AC\uD83D\uDE00B", 3);
		assertLineStartsWith(0, 'A', 0x00E9, 0x20AC, 0x1F600, 'B', ' ');
		assertCursorAt(0, 6);

		// Byte-at-a-time feeding must match a single whole-buffer feed.
		withTerminalSized(20, 2);
		enterString("A\u00E9\u20AC\uD83D\uDE00B");
		assertLineStartsWith(0, 'A', 0x00E9, 0x20AC, 0x1F600, 'B', ' ');
		assertCursorAt(0, 6);
	}

	public void testIncompleteUtf8SplitAcrossAppendBecomesReplacementChar() {
		// A lead byte with no continuation yet must not emit anything until more input arrives.
		withTerminalSized(5, 2);
		mTerminal.append(new byte[]{(byte) 0xC3}, 1);
		assertInvariants();
		assertLineIs(0, "     ");
		assertCursorAt(0, 0);

		// A non-continuation byte ends the sequence: one U+FFFD, and the ASCII byte is kept.
		mTerminal.append(new byte[]{(byte) 'a'}, 1);
		assertInvariants();
		assertLineIs(0, "\uFFFD" + "a   ");

		// A partial 4-byte sequence abandoned mid-way also becomes a single U+FFFD.
		withTerminalSized(5, 2);
		mTerminal.append(new byte[]{(byte) 0xF0, (byte) 0x9F}, 2);
		assertInvariants();
		assertLineIs(0, "     ");
		mTerminal.append(new byte[]{(byte) 'a'}, 1);
		assertInvariants();
		assertLineIs(0, "\uFFFD" + "a   ");

		// Overlong encoding split across the boundary is still rejected with U+FFFD.
		withTerminalSized(5, 2);
		mTerminal.append(new byte[]{(byte) 0xC0}, 1);
		assertInvariants();
		mTerminal.append(new byte[]{(byte) 0xA0, (byte) 'Y'}, 2);
		assertInvariants();
		assertLineIs(0, "\uFFFDY   ");

		// A lone continuation byte (invalid start) is replaced immediately.
		withTerminalSized(5, 2);
		mTerminal.append(new byte[]{(byte) 0x80}, 1);
		assertInvariants();
		assertLineIs(0, "\uFFFD    ");
	}

	public void testCsiSplitAcrossAppendBoundaries() {
		// ESC [ split from the rest of the CSI: SGR 31 must still apply once completed.
		withTerminalSized(5, 2);
		mTerminal.append(new byte[]{0x1B}, 1);
		assertInvariants();
		assertEquals("Parser must not have applied SGR yet",
				TextStyle.COLOR_INDEX_FOREGROUND, mTerminal.mForeColor);
		feedByteByByte("[31m");
		assertEquals("SGR 31 should set foreground color index 1", 1, mTerminal.mForeColor);

		// Text after the split CSI picks up the attribute.
		feedByteByByte("X");
		assertLineIs(0, "X    ");
		assertForegroundColorAt(0, 0, 1);

		// Cursor positioning CSI split at every byte position.
		withTerminalSized(10, 5);
		feedByteByByte("\033[3;4H");
		assertCursorAt(2, 3);

		// A CSI parameter split across chunks must be parsed as one number.
		withTerminalSized(10, 5);
		feedByteByByte("\033");
		feedByteByByte("[12");
		feedByteByByte("C");
		assertCursorAt(0, 9); // Started at column 0, moved forward 12 -> clamped to last column 9.
	}

	public void testOscSplitAcrossAppendBoundaries() {
		// OSC set-title split at every byte position, BEL-terminated.
		withTerminalSized(10, 2);
		feedByteByByte("\033]0;Chunked Title\007");
		assertEquals("Chunked Title", mTerminal.getTitle());
		assertEquals(Collections.singletonList(new ChangedTitle(null, "Chunked Title")), mOutput.titleChanges);

		// OSC split across chunks, ST-terminated with the ESC split from the backslash.
		withTerminalSized(10, 2);
		byte[] oscStart = "\033]0;Split ST".getBytes(StandardCharsets.UTF_8);
		mTerminal.append(oscStart, oscStart.length);
		assertInvariants();
		mTerminal.append(new byte[]{0x1B}, 1);
		assertInvariants();
		mTerminal.append(new byte[]{'\\'}, 1);
		assertInvariants();
		assertEquals("Split ST", mTerminal.getTitle());

		// Plain text after the split OSC must not be corrupted.
		withTerminalSized(10, 2);
		feedByteByByte("\033]0;T\007Hi");
		assertLineIs(0, "Hi        ");
	}

	public void testBracketedPasteModeOnAndOff() {
		withTerminalSized(10, 2);

		// Bracketed paste off: plain text.
		mTerminal.paste("hello");
		assertEquals("hello", mOutput.getOutputAndClear());

		// Bracketed paste on: wrapped in \033[200~ ... \033[201~, newlines become CR.
		enterString("\033[?2004h");
		mTerminal.paste("a\nb");
		assertEquals("\033[200~a\rb\033[201~", mOutput.getOutputAndClear());

		// Bracketed paste off again: plain text again.
		enterString("\033[?2004l");
		mTerminal.paste("world");
		assertEquals("world", mOutput.getOutputAndClear());
	}
}
