package com.termux.app;

import com.termux.app.terminal.io.TerminalToolbarViewPager;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TerminalSession;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Upstream issue #5309 (security audit F3): the toolbar text-input page must route
 * typed text through bracketed-paste-aware sending ({@link TerminalEmulator#paste(String)})
 * instead of writing it raw to the session.
 *
 * Drives the toolbar's editor-action/send path
 * ({@link TerminalToolbarViewPager.PageAdapter#sendTextInput}) with "a\nb" against a
 * session whose emulator writes into a capturing output.
 */
public class TerminalToolbarViewPagerTest {

    /** Captures bytes the emulator writes back to the session. */
    static class CapturingOutput extends TerminalOutput {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        @Override
        public void write(byte[] data, int offset, int count) {
            bytes.write(data, offset, count);
        }

        @Override
        public void titleChanged(String oldTitle, String newTitle) {
        }

        @Override
        public void onCopyTextToClipboard(String text) {
        }

        @Override
        public void onPasteTextFromClipboard() {
        }

        @Override
        public void onBell() {
        }

        @Override
        public void onColorsChanged() {
        }

        String getOutputAndClear() {
            String result = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            bytes.reset();
            return result;
        }
    }

    /** A session wired to an emulator that writes into {@link #output}. */
    static class SessionHarness {
        final CapturingOutput output = new CapturingOutput();
        final TerminalSession session;
        final TerminalEmulator emulator;

        SessionHarness() throws Exception {
            // Allocate without running the constructor: it creates an android.os.Handler,
            // which cannot exist on a JVM. The emulator is normally created in
            // initializeEmulator(), which needs a real PTY, so inject ours instead.
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            session = (TerminalSession) unsafe.allocateInstance(TerminalSession.class);
            emulator = new TerminalEmulator(output, 80, 24, 13, 15, 48, null);
            Field field = TerminalSession.class.getDeclaredField("mEmulator");
            field.setAccessible(true);
            field.set(session, emulator);
        }

        String sendTextInput(String text) {
            TerminalToolbarViewPager.PageAdapter.sendTextInput(session, text);
            return output.getOutputAndClear();
        }
    }

    @Test
    public void testSendTextInputUsesBracketedPasteWhenEnabled() throws Exception {
        SessionHarness harness = new SessionHarness();
        harness.emulator.doDecSetOrReset(true, 2004); // Enable bracketed paste (DECSET 2004).

        String written = harness.sendTextInput("a\nb");

        assertTrue("Bytes written to the session should start with ESC[200~ when bracketed paste is enabled, was: "
                + escape(written), written.startsWith("\033[200~"));
        assertEquals("Bracketed paste should wrap the text and turn the newline into a carriage return",
            "\033[200~a\rb\033[201~", written);
    }

    @Test
    public void testSendTextInputUnchangedWhenBracketedPasteDisabled() throws Exception {
        SessionHarness harness = new SessionHarness();
        // DECSET 2004 left off: behavior must be unchanged (no bracket markers).

        String written = harness.sendTextInput("a\nb");

        assertEquals("Without bracketed paste the text goes through paste() unwrapped "
                + "(newlines are normalized to carriage returns, as paste() always does)",
            "a\rb", written);
    }

    private static String escape(String s) {
        return s.replace("\033", "ESC");
    }

}
