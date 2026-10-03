package com.termux.app;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Tests for the zip-slip containment in {@link TermuxInstaller}'s bootstrap extraction.
 * Feeds {@link ZipInputStream} entries named {@code ../evil} and {@code /abs} through the
 * same containment decision the production extraction loop applies, and asserts that
 * nothing is created outside the staging directory.
 */
public class TermuxInstallerZipSlipTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private static byte[] buildZip(String... entryNames) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String name : entryNames) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write("payload".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /**
     * Mirrors the production extraction loop in {@link TermuxInstaller}: for each zip entry
     * the target is contained within the staging dir, and blocked entries are never written.
     */
    private static void extractWithContainment(File stagingDir, byte[] zipBytes) throws IOException {
        byte[] buffer = new byte[8096];
        try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry zipEntry;
            while ((zipEntry = zipInput.getNextEntry()) != null) {
                File targetFile = new File(stagingDir, zipEntry.getName());
                if (!TermuxInstaller.isWithinDirectory(stagingDir, targetFile)) {
                    // Blocked by the containment check: do NOT write the file, like production.
                    continue;
                }
                boolean isDirectory = zipEntry.isDirectory();
                File dir = isDirectory ? targetFile : targetFile.getParentFile();
                if (dir != null) dir.mkdirs();
                if (!isDirectory) {
                    try (OutputStream out = new FileOutputStream(targetFile)) {
                        int readBytes;
                        while ((readBytes = zipInput.read(buffer)) != -1)
                            out.write(buffer, 0, readBytes);
                    }
                }
            }
        }
    }

    @Test
    public void dotDotEntry_isBlockedByContainmentCheck() {
        File staging = tempFolder.getRoot();
        Assert.assertFalse(TermuxInstaller.isWithinDirectory(staging, new File(staging, "../evil")));
        Assert.assertFalse(TermuxInstaller.isWithinDirectory(staging, new File(staging, "sub/../../evil")));
    }

    @Test
    public void benignEntry_passesContainmentCheck() {
        File staging = tempFolder.getRoot();
        Assert.assertTrue(TermuxInstaller.isWithinDirectory(staging, new File(staging, "bin/tool")));
        Assert.assertTrue(TermuxInstaller.isWithinDirectory(staging, new File(staging, "lib/apt/methods/http")));
    }

    @Test
    public void zipSlipEntries_createNothingOutsideStagingDir() throws IOException {
        File stagingDir = tempFolder.newFolder("usr-staging");
        File stagingParent = stagingDir.getParentFile();

        byte[] zipBytes = buildZip("../evil", "/abs", "bin/ok");
        extractWithContainment(stagingDir, zipBytes);

        // The ../evil entry must not have escaped the staging directory.
        Assert.assertFalse(new File(stagingParent, "evil").exists());
        Assert.assertFalse(new File(tempFolder.getRoot(), "evil").exists());

        // The /abs entry must not have landed anywhere outside the staging directory either.
        Assert.assertFalse(new File(stagingParent, "abs").exists());
        Assert.assertFalse(new File(tempFolder.getRoot(), "abs").exists());

        // The benign entry is extracted normally inside the staging directory.
        Assert.assertTrue(new File(stagingDir, "bin/ok").isFile());
    }
}
