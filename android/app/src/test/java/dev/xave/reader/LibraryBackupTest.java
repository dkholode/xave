package dev.xave.reader;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class LibraryBackupTest {
    private static final String A = "11111111-1111-1111-1111-111111111111";
    private static final String B = "22222222-2222-2222-2222-222222222222";
    private static final String C = "33333333-3333-3333-3333-333333333333";

    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    private static JSONObject doc(String title) throws Exception {
        return new JSONObject().put("title", title).put("source", "https://x.com/bob/status/9")
            .put("blocks", new JSONArray().put(new JSONObject().put("kind", "text").put("text", "Body")));
    }

    private static String text(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void entry(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void entry(ZipOutputStream zip, String name, String text) throws Exception {
        entry(zip, name, text.getBytes(StandardCharsets.UTF_8));
    }

    private static ByteArrayInputStream asInput(ByteArrayOutputStream backup) {
        return new ByteArrayInputStream(backup.toByteArray());
    }

    @Test public void aBackupRestoresArticlesAndTheirImagesIntoAnEmptyLibrary() throws Exception {
        File library = folder.newFolder("source", "library");
        File first = new File(library, A + ".json");
        DocumentFiles.writeAtomic(first, doc("First article").toString());
        DocumentFiles.writeSummary(first, doc("First article"));
        DocumentFiles.writeAtomic(new File(library, B + ".json"), doc("Second article").toString());
        File images = DocumentFiles.images(first);
        images.mkdirs();
        Files.write(new File(images, "0.jpg").toPath(), new byte[]{1, 2, 3});
        Files.write(new File(images, "1.png").toPath(), new byte[]{4, 5});

        ByteArrayOutputStream backup = new ByteArrayOutputStream();
        assertEquals(2, LibraryBackup.write(library.getParentFile(), backup));
        File restored = folder.newFolder("restored");
        assertEquals(2, LibraryBackup.read(asInput(backup), restored));

        File restoredLibrary = new File(restored, "library");
        File restoredFirst = new File(restoredLibrary, A + ".json");
        assertEquals(text(first), text(restoredFirst));
        assertEquals(text(new File(library, B + ".json")), text(new File(restoredLibrary, B + ".json")));
        assertEquals("First article", new JSONObject(text(new File(restoredFirst + ".meta"))).getString("title"));
        assertEquals("Second article", new JSONObject(text(new File(restoredLibrary, B + ".json.meta"))).getString("title"));
        File restoredImages = DocumentFiles.images(restoredFirst);
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(new File(restoredImages, "0.jpg").toPath()));
        assertArrayEquals(new byte[]{4, 5}, Files.readAllBytes(new File(restoredImages, "1.png").toPath()));
    }

    @Test public void restoringKeepsArticlesYouAlreadyHave() throws Exception {
        File restoredLibrary = folder.newFolder("restored", "library");
        File mine = new File(restoredLibrary, A + ".json");
        String keep = doc("Mine").toString();
        DocumentFiles.writeAtomic(mine, keep);

        File library = folder.newFolder("source", "library");
        File theirs = new File(library, A + ".json");
        DocumentFiles.writeAtomic(theirs, doc("Theirs").toString());
        File theirImages = DocumentFiles.images(theirs);
        theirImages.mkdirs();
        Files.write(new File(theirImages, "0.jpg").toPath(), new byte[]{9});
        DocumentFiles.writeAtomic(new File(library, B + ".json"), doc("Second article").toString());
        ByteArrayOutputStream backup = new ByteArrayOutputStream();
        LibraryBackup.write(library.getParentFile(), backup);

        assertEquals(1, LibraryBackup.read(asInput(backup), restoredLibrary.getParentFile()));
        assertEquals(keep, text(mine));
        assertFalse("Mixed the backup's images into the save already here", DocumentFiles.images(mine).exists());
        assertTrue(new File(restoredLibrary, B + ".json").isFile());
    }

    @Test public void entriesOutsideTheLibraryLayoutAreIgnored() throws Exception {
        File root = folder.newFolder("restored");
        ByteArrayOutputStream backup = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(backup);
        entry(zip, "../evil.json", doc("Evil").toString());
        entry(zip, "library/../../evil.json", doc("Evil").toString());
        entry(zip, "library/not-a-uuid.json", doc("Evil").toString());
        entry(zip, "images/" + A + "/../../x.png", new byte[]{1});
        entry(zip, "library/" + B + ".json", "not json");
        entry(zip, "library/" + C + ".json", doc("Valid article").toString());
        entry(zip, LibraryBackup.COMPLETE, new byte[0]);
        zip.close();

        assertEquals(1, LibraryBackup.read(asInput(backup), root));
        assertFalse(new File(folder.getRoot(), "evil.json").exists());
        assertFalse(new File(root, "evil.json").exists());
        assertFalse(new File(root, "x.png").exists());
        assertFalse(new File(root, "library/not-a-uuid.json").exists());
        assertFalse(new File(root, "library/" + B + ".json").exists());
        assertTrue(new File(root, "library/" + C + ".json").isFile());
    }

    @Test public void imagesWithoutAnArticleAreNotKept() throws Exception {
        ByteArrayOutputStream backup = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(backup);
        entry(zip, "images/" + C + "/0.jpg", new byte[]{1, 2, 3});
        entry(zip, LibraryBackup.COMPLETE, new byte[0]);
        zip.close();

        File root = folder.newFolder("restored");
        assertEquals(0, LibraryBackup.read(asInput(backup), root));
        assertFalse(new File(root, "images/" + C).exists());
    }

    @Test public void onlyArticlesARestoreCanReadArePartOfTheBackup() throws Exception {
        File library = folder.newFolder("source", "library");
        File article = new File(library, A + ".json");
        DocumentFiles.writeAtomic(article, doc("First article").toString());
        DocumentFiles.writeSummary(article, doc("First article"));
        DocumentFiles.writeAtomic(new File(library, "notes.json"), doc("Not named by the app").toString());

        ByteArrayOutputStream backup = new ByteArrayOutputStream();
        assertEquals(1, LibraryBackup.write(library.getParentFile(), backup));

        List<String> names = new ArrayList<>();
        ZipInputStream zip = new ZipInputStream(asInput(backup));
        ZipEntry entry = zip.getNextEntry();
        while (entry != null) {
            names.add(entry.getName());
            entry = zip.getNextEntry();
        }
        assertEquals(java.util.Arrays.asList("library/" + A + ".json", LibraryBackup.COMPLETE), names);
    }

    @Test public void aRestoreThatFailsPartWayLeavesTheLibraryAsItWas() throws Exception {
        File root = folder.newFolder("restored");
        ByteArrayOutputStream backup = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(backup)) {
            entry(zip, "library/" + A + ".json", doc("First article").toString());
            entry(zip, "images/" + A + "/0.jpg", new byte[ImageDownloads.MAX_IMAGE_BYTES + 1]);
            entry(zip, LibraryBackup.COMPLETE, new byte[0]);
        }
        try {
            LibraryBackup.read(asInput(backup), root);
            org.junit.Assert.fail("Restored a backup with an oversized image");
        } catch (Exception expected) { }
        assertFalse(new File(root, "library/" + A + ".json").exists());
        assertEquals(java.util.Arrays.asList("library"), java.util.Arrays.asList(root.list()));
    }

    @Test public void entriesARestoreSkipsStillCountTowardItsSizeLimit() throws Exception {
        File root = folder.newFolder("restored");
        ByteArrayOutputStream backup = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(backup)) {
            entry(zip, "unrelated.bin", new byte[2000]);
            entry(zip, "library/" + A + ".json", doc("First article").toString());
            entry(zip, LibraryBackup.COMPLETE, new byte[0]);
        }
        try {
            LibraryBackup.read(asInput(backup), root, 1000);
            org.junit.Assert.fail("Expanded a skipped entry past the size limit");
        } catch (Exception expected) { }
        assertFalse(new File(root, "library/" + A + ".json").exists());
    }

    @Test public void aBackupARestoreCouldNotReadFailsInsteadOfReportingSuccess() throws Exception {
        File library = folder.newFolder("source", "library");
        char[] body = new char[8 * 1024 * 1024];
        java.util.Arrays.fill(body, 'x');
        DocumentFiles.writeAtomic(new File(library, A + ".json"), doc(new String(body)).toString());
        try {
            LibraryBackup.write(library.getParentFile(), new ByteArrayOutputStream());
            org.junit.Assert.fail("Wrote an article too large to restore");
        } catch (Exception expected) { }
    }

    @Test public void aRestoreThatFailsWhilePublishingTakesBackWhatItAdded() throws Exception {
        File root = folder.newFolder("restored");
        File blocked = new File(root, "library/" + B + ".json.meta");
        assertTrue(new File(blocked, "in-the-way").mkdirs());
        ByteArrayOutputStream backup = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(backup)) {
            entry(zip, "library/" + A + ".json", doc("First").toString());
            entry(zip, "images/" + A + "/0.jpg", new byte[]{1});
            entry(zip, "library/" + B + ".json", doc("Second").toString());
            entry(zip, LibraryBackup.COMPLETE, new byte[0]);
        }
        try {
            LibraryBackup.read(asInput(backup), root);
            org.junit.Assert.fail("Reported a restore that could not write a summary");
        } catch (Exception expected) { }
        assertFalse(new File(root, "library/" + A + ".json").exists());
        assertFalse(new File(root, "library/" + B + ".json").exists());
        assertFalse(new File(root, "images/" + A).exists());
    }

    @Test public void anArticleNestedDeeperThanAnyCaptureIsNotRestored() throws Exception {
        Object title = "Deep";
        for (int i = 0; i < 40; i++) title = new JSONArray().put(title);
        ByteArrayOutputStream backup = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(backup)) {
            entry(zip, "library/" + A + ".json", doc("Fine").put("title", title).toString());
            entry(zip, "library/" + B + ".json", doc("Fine").toString());
            entry(zip, LibraryBackup.COMPLETE, new byte[0]);
        }
        File root = folder.newFolder("restored");
        assertEquals(1, LibraryBackup.read(asInput(backup), root));
        assertFalse(new File(root, "library/" + A + ".json").exists());
        assertTrue(new File(root, "library/" + B + ".json").isFile());
    }

    @Test public void anArticleHoldingMoreValuesThanAnyCaptureIsNotRestored() throws Exception {
        StringBuilder blocks = new StringBuilder();
        for (int i = 0; i < 600_000; i++) blocks.append("{},");
        String article = "{\"title\":\"t\",\"source\":\"https://x.com/a/status/1\",\"blocks\":[" + blocks + "{}]}";
        ByteArrayOutputStream backup = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(backup)) {
            entry(zip, "library/" + A + ".json", article);
            entry(zip, LibraryBackup.COMPLETE, new byte[0]);
        }
        File root = folder.newFolder("restored");
        assertEquals(0, LibraryBackup.read(asInput(backup), root));
        assertEquals(0, new File(root, "library").list().length);
    }

    @Test public void anArticleHidingValuesBehindASingleQuotedKeyIsNotRestored() throws Exception {
        StringBuilder blocks = new StringBuilder();
        for (int i = 0; i < 600_000; i++) blocks.append("{},");
        String article = "{'\"':1,\"title\":\"t\",\"source\":\"https://x.com/a/status/1\",\"blocks\":[" + blocks + "{}]}";
        ByteArrayOutputStream backup = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(backup)) {
            entry(zip, "library/" + A + ".json", article);
            entry(zip, LibraryBackup.COMPLETE, new byte[0]);
        }
        File root = folder.newFolder("restored");
        assertEquals(0, LibraryBackup.read(asInput(backup), root));
    }

    @Test public void aBackupCutOffBetweenEntriesRestoresNothing() throws Exception {
        File library = folder.newFolder("source", "library");
        DocumentFiles.writeAtomic(new File(library, A + ".json"), doc("First article").toString());
        DocumentFiles.writeAtomic(new File(library, B + ".json"), doc("Second article").toString());
        ByteArrayOutputStream backup = new ByteArrayOutputStream();
        LibraryBackup.write(library.getParentFile(), backup);
        byte[] whole = backup.toByteArray();
        int second = -1;
        for (int i = 1; i + 3 < whole.length && second < 0; i++) {
            if (whole[i] == 'P' && whole[i + 1] == 'K' && whole[i + 2] == 3 && whole[i + 3] == 4) second = i;
        }
        assertTrue(second > 0);

        File root = folder.newFolder("restored");
        try {
            LibraryBackup.read(new ByteArrayInputStream(java.util.Arrays.copyOf(whole, second + 10)), root);
            org.junit.Assert.fail("Restored part of a backup that was cut off");
        } catch (Exception expected) { }
        assertEquals(0, new File(root, "library").list().length);
    }

    @Test public void aBackupWithTooManyFilesIsRefused() throws Exception {
        ByteArrayOutputStream backup = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(backup)) {
            for (int i = 0; i < LibraryBackup.MAX_ENTRIES; i++) entry(zip, "unrelated/" + i, new byte[0]);
            entry(zip, LibraryBackup.COMPLETE, new byte[0]);
        }
        try {
            LibraryBackup.read(asInput(backup), folder.newFolder("restored"));
            org.junit.Assert.fail("Read a backup with more files than any library holds");
        } catch (Exception expected) { }
    }

    @Test public void aRestoredArticleKeepsTheDateItWasSaved() throws Exception {
        File library = folder.newFolder("source", "library");
        File article = new File(library, A + ".json");
        DocumentFiles.writeAtomic(article, doc("Saved last year").toString());
        long savedAt = 1736942400000L;
        assertTrue(article.setLastModified(savedAt));
        ByteArrayOutputStream backup = new ByteArrayOutputStream();
        LibraryBackup.write(library.getParentFile(), backup);

        File root = folder.newFolder("restored");
        LibraryBackup.read(asInput(backup), root);
        long restoredAt = new File(root, "library/" + A + ".json").lastModified();
        assertTrue("Restored with time " + restoredAt, Math.abs(restoredAt - savedAt) <= 2000);
    }
}
