package com.termux.app.api.file;

import com.termux.app.api.file.FileReceiverActivity;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class FileReceiverActivityTest {

    @Test
    public void testIsSafeAttachmentFileName() {
        // Plain names are accepted.
        String[] safeNames = {"photo.jpg", "document.txt", "a b (1).pdf", "archive.tar.gz", ".hidden"};
        for (String name : safeNames) {
            Assert.assertTrue("expected safe: " + name, FileReceiverActivity.isSafeAttachmentFileName(name));
        }

        // Traversal / separator / NUL payloads must be rejected so saveStreamWithName
        // never writes outside the receive dir.
        String[] unsafeNames = {
            "../evil.txt", "..\\evil.txt", "../../etc/cron.d/x", "..", "...", "sub/dir/file.txt",
            "sub\\dir\\file.txt", "/absolute.txt", "evil.txt\0.jpg", "file..txt", null, ""
        };
        for (String name : unsafeNames) {
            Assert.assertFalse("expected rejected: " + (name == null ? "null" : name),
                FileReceiverActivity.isSafeAttachmentFileName(name));
        }
    }

    @Test
    public void testIsSharedTextAnUrl() {
        List<String> validUrls = new ArrayList<>();
        validUrls.add("http://example.com");
        validUrls.add("https://example.com");
        validUrls.add("https://example.com/path/parameter=foo");
        validUrls.add("magnet:?xt=urn:btih:d540fc48eb12f2833163eed6421d449dd8f1ce1f&dn=Ubuntu+desktop+19.04+%2864bit%29&tr=udp%3A%2F%2Ftracker.openbittorrent.com%3A80&tr=udp%3A%2F%2Ftracker.publicbt.com%3A80&tr=udp%3A%2F%2Ftracker.ccc.de%3A80");
        for (String url : validUrls) {
            Assert.assertTrue(FileReceiverActivity.isSharedTextAnUrl(url));
        }

        List<String> invalidUrls = new ArrayList<>();
        invalidUrls.add("a test with example.com");
        invalidUrls.add("");
        invalidUrls.add(null);
        for (String url : invalidUrls) {
            Assert.assertFalse(FileReceiverActivity.isSharedTextAnUrl(url));
        }
    }

}
