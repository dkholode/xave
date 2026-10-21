package dev.xave.reader;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
    public static void main(String[] args) throws Exception {
        JSONObject doc = sample();
        String html = ReaderDocument.html(doc);
        check(html.contains("Read <strong>this important</strong> sentence with <a href=\"https://example.org/reference?x=1&amp;y=2\">a reference</a> here.</p>"), "Fragmented paragraph or missing inline formatting");
        check(html.contains("<h3>") && html.contains("<blockquote>") && html.contains("3. A numbered step"), "Lost block formatting");
        check(html.contains("<pre>  if (offline)"), "Lost code indentation");
        pass("Paragraphs, inline links, emphasis, headings, lists, quotes, and code render together");

        JSONObject hostile = new JSONObject().put("title", "<script>alert(1)</script>").put("blocks", new JSONArray()
            .put(new JSONObject().put("kind", "text").put("runs", new JSONArray()
                .put(new JSONObject().put("text", "<img src=x onerror=alert(1)>").put("bold", true).put("url", "javascript:alert(1)"))
                .put(new JSONObject().put("text", "private").put("url", "https://name:secret@example.org/"))))
            .put(new JSONObject().put("kind", "image").put("data", "https://example.org/tracking.png").put("text", "\" onerror=alert(1)")));
        String safe = ReaderDocument.html(hostile);
        check(!safe.contains("<script>") && !safe.contains("javascript:") && !safe.contains("src=\"https://"), "Active content survived");
        check(!safe.contains("href=\"https://name:"), "Credential link survived");
        check(safe.contains("&lt;img") && safe.contains("Content-Security-Policy"), "Missing escaping or CSP");
        pass("Hostile rich-text runs and remote image sources stay inert");

        String legacy = ReaderDocument.html(new JSONObject().put("blocks", new JSONArray()
            .put(new JSONObject().put("kind", "text").put("text", "Legacy paragraph"))));
        check(legacy.contains("<p>Legacy paragraph</p>"), "Broke an older saved document");
        pass("Older plain-text saves remain readable without rewriting their content");
        check(html.contains("<details><summary>example.org") && html.contains("class='print-info'") && html.contains("Paragraph 11"), "Lost metadata or final paragraph");
        String partial = ReaderDocument.html(new JSONObject().put("warning", "Partial capture: incomplete. 1 image(s) could not be saved"));
        check(partial.contains("Partial capture · check captured posts · missing images"), "Important warning was hidden");
        pass("Compact metadata preserves export details and prominent partial/missing-image summaries");

        String night = ReaderDocument.html(doc, true);
        check(!html.contains("@media screen{body{color:#eee") && night.contains("@media screen{body{color:#eee;background:#000}"), "Dark reading colors not applied on screen only");
        check(night.contains("@media print{"), "Dark mode removed print styles");
        pass("Dark reading colors apply on screen while print and export stay light");

        check(ReaderDocument.xHost("x.com") && ReaderDocument.xHost("mobile.twitter.com") && !ReaderDocument.xHost("example.com")
            && !ReaderDocument.xHost("notx.com") && !ReaderDocument.xHost(null), "Misclassified an X host");
        pass("Only X hosts receive X-specific capture tips");

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
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>)paths.sorted(java.util.Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
        Files.write(new File(args[0]).toPath(), html.getBytes(StandardCharsets.UTF_8));
        System.out.println(passed + "/10 host checks passed");
    }
}
