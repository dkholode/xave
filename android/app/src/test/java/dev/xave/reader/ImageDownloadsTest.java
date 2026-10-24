package dev.xave.reader;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ImageDownloadsTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    private static JSONObject capture(String imageUrl) throws Exception {
        return new JSONObject().put("blocks", new JSONArray()
            .put(new JSONObject().put("kind", "text").put("text", "A post"))
            .put(new JSONObject().put("kind", "image").put("url", imageUrl).put("text", "A diagram")));
    }

    @Test public void inlineImageIsSavedAsAFileAndItsPayloadLeavesTheArticle() throws Exception {
        JSONObject doc = capture("data:image/png;base64,iVBORw==");
        File images = new File(folder.getRoot(), "images");
        ImageDownloads.save(doc, Arrays.asList(null, null), "test", images, (done, count) -> { });
        JSONObject image = doc.getJSONArray("blocks").getJSONObject(1);
        assertEquals("0.png", image.getString("file"));
        assertFalse(image.has("url"));
        assertArrayEquals(new byte[]{(byte)0x89, 'P', 'N', 'G'}, Files.readAllBytes(new File(images, "0.png").toPath()));
    }

    @Test public void insecureImageUrlIsNotFetchedAndStaysAPlaceholder() throws Exception {
        JSONObject doc = capture("http://example.org/a.png");
        File images = new File(folder.getRoot(), "images");
        ImageDownloads.save(doc, Arrays.asList(null, null), "test", images, (done, count) -> { });
        assertFalse(doc.getJSONArray("blocks").getJSONObject(1).has("file"));
        assertEquals(0, images.listFiles().length);
    }

    @Test public void readLimitedStopsAtTheSizeLimit() throws Exception {
        byte[] ten = new byte[10];
        assertEquals(10, ImageDownloads.readLimited(new ByteArrayInputStream(ten), 10, Long.MAX_VALUE).length);
        try {
            ImageDownloads.readLimited(new ByteArrayInputStream(ten), 9, Long.MAX_VALUE);
            fail("Read past the size limit");
        } catch (Exception expected) { }
    }
}
