package dev.xave.reader;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.TimeZone;

/** Runs the actual renderer and disk code with Android Studio's pure-Java org.json. */
public final class ReaderHostTest {
    private static int passed;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void pass(String message) { passed++; System.out.println("PASS · " + message); }
    private static JSONObject sample() throws Exception {
        JSONArray runs = new JSONArray()
            .put(new JSONObject().put("text", "Read "))
            .put(new JSONObject().put("text", "this important").put("bold", true))
            .put(new JSONObject().put("text", " sentence with "))
            .put(new JSONObject().put("text", "a reference").put("url", "https://example.org/reference?x=1&y=2"))
            .put(new JSONObject().put("text", " here."));
        JSONArray blocks = new JSONArray().put(new JSONObject().put("kind", "text").put("runs", runs))
            .put(new JSONObject().put("kind", "heading").put("level", 3).put("text", "Keep the useful structure"))
            .put(new JSONObject().put("kind", "listItem").put("depth", 1).put("marker", "3.").put("text", "A numbered step"))
            .put(new JSONObject().put("kind", "listItem").put("depth", 2).put("marker", "•").put("text", "A nested detail"))
            .put(new JSONObject().put("kind", "quote").put("text", "A clearly separated quotation."))
            .put(new JSONObject().put("kind", "code").put("text", "  if (offline) {\n    read();\n  }"));
        for (int i=0; i<12; i++) blocks.put(new JSONObject().put("kind", "text")
            .put("text", "Paragraph " + i + ". The reader keeps the article in focus. Metadata and capture notes can be expanded when needed."));
        return new JSONObject().put("title", "A calmer offline reading experience")
            .put("source", "https://example.org/a-readable-article").put("savedAt", "Oct 21, 2026")
            .put("warning", "Page capture: check the preview against the original. Only loaded text and images are saved.")
            .put("blocks", blocks);
    }
    private static JSONObject text(String text) throws Exception { return new JSONObject().put("kind", "text").put("text", text); }
    private static JSONObject link(String text, String url) throws Exception { return new JSONObject().put("kind", "link").put("text", text).put("url", url); }
    /** Two posts in the shape capture.js emits: an ISO time from a signed-in page, visible text from an anonymous one. */
    private static JSONObject thread() throws Exception {
        JSONArray blocks = new JSONArray()
            .put(text("I rebuilt our build pipeline\nthree times this year."))
            .put(link("2026-10-19T12:40:11.000Z", "https://x.com/alice/status/1"))
            .put(new JSONObject().put("kind", "divider"))
            .put(text("1. Caching the dependency step."))
            .put(text("[Quoted post — see original]"))
            .put(link("6:21 PM · Oct 7, 2026", "https://x.com/alice/status/2"));
        return new JSONObject().put("title", "@alice · 2 posts").put("source", "https://x.com/alice/status/1")
            .put("savedAt", "Oct 21, 2026 8:40 PM").put("blocks", blocks)
            .put("warning", "Partial capture: loaded posts by @alice only. 1 image(s) could not be saved; placeholders appear below.");
    }
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.US);
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        JSONObject doc = sample();
        String html = ReaderDocument.html(doc, false, false, name -> null);
        check(html.contains("Read <strong>this important</strong> sentence with <a href=\"https://example.org/reference?x=1&amp;y=2\">a reference</a> here.</p>"), "Fragmented paragraph or missing inline formatting");
        check(html.contains("<h3>") && html.contains("<blockquote>") && html.contains("3. A numbered step"), "Lost block formatting");
        check(html.contains("<pre>  if (offline)"), "Lost code indentation");
        pass("Paragraphs, inline links, emphasis, headings, lists, quotes, and code render together");

        JSONObject hostile = new JSONObject().put("title", "<script>alert(1)</script>").put("blocks", new JSONArray()
            .put(new JSONObject().put("kind", "text").put("runs", new JSONArray()
                .put(new JSONObject().put("text", "<img src=x onerror=alert(1)>").put("bold", true).put("url", "javascript:alert(1)"))
                .put(new JSONObject().put("text", "private").put("url", "https://name:secret@example.org/"))))
            .put(new JSONObject().put("kind", "image").put("data", "https://example.org/tracking.png").put("text", "\" onerror=alert(1)")));
        String safe = ReaderDocument.html(hostile, false, false, name -> null);
        check(!safe.contains("<script>") && !safe.contains("javascript:") && !safe.contains("src=\"https://"), "Active content survived");
        check(!safe.contains("href=\"https://name:"), "Credential link survived");
        check(safe.contains("&lt;img") && safe.contains("Content-Security-Policy"), "Missing escaping or CSP");
        pass("Hostile rich-text runs and remote image sources stay inert");

        String legacy = ReaderDocument.html(new JSONObject().put("blocks", new JSONArray()
            .put(new JSONObject().put("kind", "text").put("text", "Legacy paragraph"))), false, false, name -> null);
        check(legacy.contains("<p>Legacy paragraph</p>"), "Broke an older saved document");
        pass("Older plain-text saves remain readable without rewriting their content");
        check(html.contains("Paragraph 11"), "Lost the final paragraph");

        String reading = ReaderDocument.html(thread(), false, false, name -> null);
        check(reading.contains("<h1>@alice</h1>") && reading.contains("Thread · 2 posts"), "Thread header lost its author or post count");
        check(reading.contains("<a href=\"https://x.com/alice/status/1\"><span>1 / 2</span><span>Oct 19, 2026</span></a>")
            && reading.contains("<a href=\"https://x.com/alice/status/2\"><span>2 / 2</span><span>6:21 PM · Oct 7, 2026</span></a>"), "Posts lost their number, date, or link");
        check(!reading.contains("2026-10-19T12:40") && !reading.contains("<details"), "Raw timestamp or details row is still shown");
        pass("Threads read as numbered posts with readable dates that link to each post");

        check(reading.contains("<p class='note'>Quoted post — see original</p>") && !reading.contains("[Quoted post"), "Placeholder still reads as body text");
        pass("Quoted-post and media placeholders render as notes, not bracketed text");

        String preview = ReaderDocument.html(thread(), false, true, name -> null);
        check(preview.contains("Check this capture.") && preview.contains("2 loaded posts by @alice") && preview.contains("1 image could not be saved"), "Preview hides what the capture contains");
        check(!reading.contains("Check this capture.") && !reading.contains("Partial capture"), "Saved copy still carries the capture warning");
        pass("The capture notice appears while previewing and not on saved copies");

        String exported = ReaderDocument.html(thread(), name -> null);
        check(exported.contains("Saved Oct 21, 2026 8:40 PM") && exported.contains("https://x.com/alice/status/1"), "Export lost the save date or source");
        pass("Exports carry the save date and source");

        JSONObject threadRow = ReaderDocument.summary(thread());
        check(threadRow.getString("title").equals("I rebuilt our build pipeline three times this year.") && threadRow.getString("detail").equals("@alice · 2 posts"), "Thread row is not its first post: " + threadRow);
        JSONObject articleRow = ReaderDocument.summary(new JSONObject().put("title", "Why e-ink finally works").put("source", "https://x.com/bob/status/9"));
        check(articleRow.getString("title").equals("Why e-ink finally works") && articleRow.getString("detail").equals("@bob · Article"), "Article row lost its title or author: " + articleRow);
        pass("Library rows show the first post or article title, with author and post count");

        String night = ReaderDocument.html(doc, true, false, name -> null);
        check(!html.contains("@media screen{body{color:#eee") && night.contains("@media screen{body{color:#eee;background:#000}"), "Dark reading colors not applied on screen only");
        check(night.contains("@media print{"), "Dark mode removed print styles");
        pass("Dark reading colors apply on screen while print and export stay light");

        check(ReaderDocument.xHost("x.com") && ReaderDocument.xHost("mobile.twitter.com") && !ReaderDocument.xHost("example.com")
            && !ReaderDocument.xHost("notx.com") && !ReaderDocument.xHost(null), "Misclassified an X host");
        pass("Only X hosts are treated as X");

        JSONObject pictured = new JSONObject().put("blocks", new JSONArray()
            .put(new JSONObject().put("kind", "image").put("file", "0.jpg").put("text", "A chart"))
            .put(new JSONObject().put("kind", "image").put("file", "../library/other.json").put("text", "Escape"))
            .put(new JSONObject().put("kind", "image").put("file", "https://example.org/t.png").put("text", "Remote")));
        String shown = ReaderDocument.html(pictured, false, false, name -> "images/" + name);
        check(shown.contains("<figure><img src=\"images/0.jpg\" alt=\"A chart\"><figcaption>A chart</figcaption></figure>"), "Stored image not shown");
        check(!shown.contains("../library") && !shown.contains("src=\"images/https")
            && shown.contains("<p class='note'>Image unavailable offline: Escape</p>") && shown.contains("<p class='note'>Image unavailable offline: Remote</p>"), "Unsafe image name was used");
        pass("Stored images render by name and unsafe names fall back to placeholders");

        check(ReaderDocument.sameHost("https://example.org/post", "https://EXAMPLE.org/i.png")
            && !ReaderDocument.sameHost("https://attacker.example/post", "https://example.org/i.png")
            && !ReaderDocument.sameHost("https://example.org/post", "not a url"), "Wrong cookie host decision");
        check("jpg".equals(ReaderDocument.imageExtension("image/jpg")) && "jpg".equals(ReaderDocument.imageExtension("image/jpeg; charset=binary"))
            && "webp".equals(ReaderDocument.imageExtension("IMAGE/WEBP")) && ReaderDocument.imageExtension("image/svg+xml") == null
            && ReaderDocument.imageExtension(null) == null, "Wrong image type decision");
        pass("Image cookies stay on the page's host and only raster image types are kept");

        Path root = Files.createTempDirectory("xave-host-test-");
        try {
            File drafts = Files.createDirectory(root.resolve("drafts")).toFile();
            File library = Files.createDirectory(root.resolve("library")).toFile();
            File draft = new File(drafts, "sample.json");
            DocumentFiles.writeAtomic(draft, doc.toString()); DocumentFiles.writeSummary(draft, doc);
            check(DocumentFiles.read(draft).toString().contains("Paragraph 11"), "Draft did not survive a fresh read");
            File saved = DocumentFiles.save(draft, library, DocumentFiles.read(draft));
            check(saved.isFile() && !draft.exists() && !new File(draft + ".meta").exists(), "Promotion lost data or left active draft");
            DocumentFiles.save(draft, library, doc); // Retry after promotion, with stale in-memory state.
            check(library.listFiles((d,n) -> n.endsWith(".json")).length == 1, "Retry duplicated the article");
            pass("Drafts persist on disk and save retries retain one complete article");

            File retry = new File(drafts, "retry.json"); DocumentFiles.writeAtomic(retry, doc.toString());
            byte[] original = Files.readAllBytes(retry.toPath());
            File unavailable = root.resolve("not-a-directory").toFile(); Files.write(unavailable.toPath(), new byte[]{1});
            boolean failed = false;
            try { DocumentFiles.save(retry, unavailable, doc); } catch (Exception expected) { failed = true; }
            check(failed && java.util.Arrays.equals(original, Files.readAllBytes(retry.toPath())), "Failed save destroyed its draft");
            pass("A failed save keeps the draft bytes available for retry");

            File meta = new File(saved + ".meta"); Files.write(meta.toPath(), "{broken".getBytes(StandardCharsets.UTF_8));
            DocumentFiles.writeSummary(saved, DocumentFiles.read(saved));
            check(DocumentFiles.read(meta).getString("title").equals(doc.getString("title")), "Metadata could not be rebuilt");
            check(DocumentFiles.read(saved).getJSONArray("blocks").length() == doc.getJSONArray("blocks").length(), "Metadata repair changed article content");
            pass("Metadata can be rebuilt from an intact article without altering content");

            byte[] before = Files.readAllBytes(saved.toPath()); failed = false;
            try { DocumentFiles.writeAtomic(saved, null); } catch (Exception expected) { failed = true; }
            check(failed && java.util.Arrays.equals(before, Files.readAllBytes(saved.toPath())), "Failed write damaged the previous copy");
            check(library.listFiles((d,n) -> n.endsWith(".tmp")).length == 0, "Failed write left a temporary file");
            pass("Failed atomic replacement preserves the previous copy and removes temporary output");

            File pictureDraft = new File(drafts, "pictured.json"); DocumentFiles.writeAtomic(pictureDraft, doc.toString());
            File stored = new File(DocumentFiles.images(pictureDraft), "0.png");
            stored.getParentFile().mkdirs(); Files.write(stored.toPath(), new byte[]{(byte)0x89, 'P', 'N', 'G'});
            File pictureSaved = DocumentFiles.save(pictureDraft, library, doc);
            File kept = new File(DocumentFiles.images(pictureSaved), "0.png");
            check(Files.readAllBytes(kept.toPath()).length == 4, "Saving lost the draft's images");
            check("data:image/png;base64,iVBORw==".equals(DocumentFiles.dataUrl(kept)), "Export could not embed a stored image");
            DocumentFiles.writeAtomic(pictureDraft, doc.toString()); DocumentFiles.delete(pictureDraft);
            check(kept.isFile(), "Discarding a stale draft removed images its saved article uses");
            DocumentFiles.delete(pictureSaved);
            check(!pictureSaved.exists() && !new File(pictureSaved + ".meta").exists() && !kept.exists()
                && !DocumentFiles.images(pictureSaved).exists(), "Deleting an article left its files behind");
            pass("Images follow a draft into the library, embed in exports, and leave with the last copy");

            File live = new File(drafts, "live.json"); DocumentFiles.writeAtomic(live, doc.toString());
            File liveImage = new File(DocumentFiles.images(live), "0.png");
            File lostImage = new File(DocumentFiles.images(new File(drafts, "interrupted.json")), "0.png");
            for (File image : new File[]{liveImage, lostImage}) { image.getParentFile().mkdirs(); Files.write(image.toPath(), new byte[]{1}); }
            DocumentFiles.removeOrphanImages(root.toFile());
            check(liveImage.isFile() && !lostImage.getParentFile().exists(), "Wrong images removed after an interrupted capture");
            pass("Images left by an interrupted capture are removed while a draft's images stay");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>)paths.sorted(java.util.Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
        Files.write(new File(args[0]).toPath(), html.getBytes(StandardCharsets.UTF_8));
        System.out.println(passed + "/18 host checks passed");
    }
}
