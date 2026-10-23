package dev.xave.reader;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class DocumentFilesTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();
    private File root;
    private File library;
    private final JSONObject doc = new JSONObject();

    @Before public void setUp() throws Exception {
        root = folder.getRoot();
        library = folder.newFolder("library");
        doc.put("title", "A saved article").put("source", "https://x.com/bob/status/9")
            .put("blocks", new JSONArray().put(new JSONObject().put("kind", "text").put("text", "Body")));
    }

    private static JSONObject read(File file) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void metadataCanBeRebuiltFromAnIntactArticleWithoutAlteringIt() throws Exception {
        File saved = new File(library, "sample.json");
        DocumentFiles.writeAtomic(saved, doc.toString());
        File meta = new File(saved + ".meta");
        Files.write(meta.toPath(), "{broken".getBytes(StandardCharsets.UTF_8));
        DocumentFiles.writeSummary(saved, read(saved));
        assertEquals("A saved article", read(meta).getString("title"));
        assertEquals(1, read(saved).getJSONArray("blocks").length());
    }

    @Test public void failedAtomicReplacementKeepsThePreviousCopyAndNoTemporaryFile() throws Exception {
        File saved = new File(library, "sample.json");
        DocumentFiles.writeAtomic(saved, doc.toString());
        byte[] before = Files.readAllBytes(saved.toPath());
        try {
            DocumentFiles.writeAtomic(saved, null);
            fail("Writing null content should fail");
        } catch (Exception expected) { }
        assertArrayEquals(before, Files.readAllBytes(saved.toPath()));
        assertEquals(0, library.listFiles((d,n) -> n.endsWith(".tmp")).length);
    }

    @Test public void storedImagesEmbedInExportsAndLeaveWithTheirArticle() throws Exception {
        File article = new File(library, "pictured.json");
        File stored = new File(DocumentFiles.images(article), "0.png");
        stored.getParentFile().mkdirs();
        Files.write(stored.toPath(), new byte[]{(byte)0x89, 'P', 'N', 'G'});
        DocumentFiles.writeAtomic(article, doc.toString());
        DocumentFiles.writeSummary(article, doc);
        assertEquals("data:image/png;base64,iVBORw==", DocumentFiles.dataUrl(stored));
        DocumentFiles.delete(article);
        assertFalse(article.exists());
        assertFalse(new File(article + ".meta").exists());
        assertFalse(DocumentFiles.images(article).exists());
    }

    @Test public void imagesLeftByAnInterruptedCaptureAreRemovedWhileSavedImagesStay() throws Exception {
        File live = new File(library, "live.json");
        DocumentFiles.writeAtomic(live, doc.toString());
        File liveImage = new File(DocumentFiles.images(live), "0.png");
        File lostImage = new File(DocumentFiles.images(new File(library, "interrupted.json")), "0.png");
        for (File image : new File[]{liveImage, lostImage}) {
            image.getParentFile().mkdirs();
            Files.write(image.toPath(), new byte[]{1});
        }
        DocumentFiles.removeOrphanImages(root);
        assertTrue(liveImage.isFile());
        assertFalse(lostImage.getParentFile().exists());
    }
}
