package dev.xave.reader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Locale;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

public final class ReaderDocumentTest {
    @BeforeClass public static void fixClockAndLocale() {
        Locale.setDefault(Locale.US);
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    private static JSONObject text(String text) throws Exception { return new JSONObject().put("kind", "text").put("text", text); }
    private static JSONObject link(String text, String url) throws Exception { return new JSONObject().put("kind", "link").put("text", text).put("url", url); }

    /** An X Article opened from a share link, so its source names no author. */
    private static JSONObject article() throws Exception {
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
        for (int i=0; i<12; i++) blocks.put(text("Paragraph " + i + ". The reader keeps the article in focus."));
        return new JSONObject().put("title", "A calmer offline reading experience")
            .put("source", "https://x.com/i/article/1234").put("savedAt", "Oct 21, 2026")
            .put("blocks", blocks);
    }

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
            .put("savedAt", "Oct 21, 2026 8:40 PM").put("blocks", blocks);
    }

    @Test public void paragraphsLinksEmphasisHeadingsListsQuotesAndCodeRenderTogether() throws Exception {
        String html = ReaderDocument.html(article(), false, name -> null);
        assertTrue("Fragmented paragraph or missing inline formatting",
            html.contains("Read <strong>this important</strong> sentence with <a href=\"https://example.org/reference?x=1&amp;y=2\">a reference</a> here.</p>"));
        assertTrue("Lost block formatting", html.contains("<h3>") && html.contains("<blockquote>") && html.contains("3. A numbered step"));
        assertTrue("Lost code indentation", html.contains("<pre>  if (offline)"));
        assertTrue("Lost the final paragraph", html.contains("Paragraph 11"));
    }

    @Test public void hostileRunsAndRemoteImageSourcesStayInert() throws Exception {
        JSONObject hostile = new JSONObject().put("title", "<script>alert(1)</script>").put("blocks", new JSONArray()
            .put(new JSONObject().put("kind", "text").put("runs", new JSONArray()
                .put(new JSONObject().put("text", "<img src=x onerror=alert(1)>").put("bold", true).put("url", "javascript:alert(1)"))
                .put(new JSONObject().put("text", "private").put("url", "https://name:secret@example.org/"))))
            .put(new JSONObject().put("kind", "image").put("url", "https://example.org/tracking.png").put("text", "\" onerror=alert(1)")));
        String safe = ReaderDocument.html(hostile, false, name -> null);
        assertTrue("Active content survived", !safe.contains("<script>") && !safe.contains("javascript:") && !safe.contains("src=\"https://"));
        assertFalse("Credential link survived", safe.contains("href=\"https://name:"));
        assertTrue("Missing escaping or CSP", safe.contains("&lt;img") && safe.contains("Content-Security-Policy"));
    }

    @Test public void plainTextBlocksRenderAsParagraphs() throws Exception {
        String html = ReaderDocument.html(new JSONObject().put("blocks", new JSONArray().put(text("A post without formatting"))), false, name -> null);
        assertTrue(html.contains("<p>A post without formatting</p>"));
    }

    @Test public void threadsReadAsNumberedPostsWithReadableDatesThatLinkToEachPost() throws Exception {
        String reading = ReaderDocument.html(thread(), false, name -> null);
        assertTrue("Thread header lost its author or post count", reading.contains("<h1>@alice</h1>") && reading.contains("Thread · 2 posts"));
        assertTrue("Posts lost their number, date, or link",
            reading.contains("<a href=\"https://x.com/alice/status/1\"><span>1 / 2</span><span>Oct 19, 2026</span></a>")
            && reading.contains("<a href=\"https://x.com/alice/status/2\"><span>2 / 2</span><span>6:21 PM · Oct 7, 2026</span></a>"));
        assertFalse("Raw timestamp is still shown", reading.contains("2026-10-19T12:40"));
    }

    @Test public void placeholdersRenderAsNotesNotBracketedText() throws Exception {
        String reading = ReaderDocument.html(thread(), false, name -> null);
        assertTrue(reading.contains("<p class='note'>Quoted post — see original</p>"));
        assertFalse(reading.contains("[Quoted post"));
    }

    @Test public void saveConfirmationNamesThePostCountAndAuthorOrTheArticle() throws Exception {
        assertEquals("Saved 2 posts by @alice", ReaderDocument.savedMessage(thread()));
        assertEquals("Saved 1 post by @bob", ReaderDocument.savedMessage(new JSONObject().put("title", "@bob · 1 post")));
        assertEquals("Saved X Article by @bob", ReaderDocument.savedMessage(
            new JSONObject().put("title", "Why e-ink finally works").put("source", "https://x.com/bob/status/9")));
        assertEquals("Saved X Article", ReaderDocument.savedMessage(article()));
    }

    @Test public void exportsCarryTheSaveDateAndSource() throws Exception {
        String exported = ReaderDocument.html(thread(), name -> null);
        assertTrue(exported.contains("Saved Oct 21, 2026 8:40 PM") && exported.contains("https://x.com/alice/status/1"));
    }

    @Test public void libraryRowsShowTheFirstPostOrArticleTitleWithAuthorAndPostCount() throws Exception {
        JSONObject threadRow = ReaderDocument.summary(thread());
        assertEquals("I rebuilt our build pipeline three times this year.", threadRow.getString("title"));
        assertEquals("@alice · 2 posts", threadRow.getString("detail"));
        JSONObject articleRow = ReaderDocument.summary(new JSONObject().put("title", "Why e-ink finally works").put("source", "https://x.com/bob/status/9"));
        assertEquals("Why e-ink finally works", articleRow.getString("title"));
        assertEquals("@bob · Article", articleRow.getString("detail"));
    }

    @Test public void libraryRowsKeepTheSourceLink() throws Exception {
        assertEquals("https://x.com/alice/status/1", ReaderDocument.summary(thread()).getString("source"));
        assertEquals("https://x.com/i/article/1234", ReaderDocument.summary(article()).getString("source"));
    }

    @Test public void captureSizeCountsThreadPostsAndImages() throws Exception {
        assertEquals(2, ReaderDocument.postCount(thread()));
        assertEquals(0, ReaderDocument.postCount(article()));
        assertEquals(0, ReaderDocument.imageCount(thread()));
        JSONObject pictured = new JSONObject().put("blocks", new JSONArray()
            .put(new JSONObject().put("kind", "image"))
            .put(text("Between the pictures."))
            .put(new JSONObject().put("kind", "image")));
        assertEquals(2, ReaderDocument.imageCount(pictured));
    }

    @Test public void genericArticleTitleIsReplacedByTheLineAfterTheAuthorsHandle() throws Exception {
        JSONObject conversation = new JSONObject().put("title", "Conversation").put("source", "https://x.com/bob/status/9")
            .put("blocks", new JSONArray().put(text("@bob")).put(text("Why e-ink finally works for reading")));
        assertEquals("Why e-ink finally works for reading", ReaderDocument.savedTitle(conversation));
        assertEquals("A calmer offline reading experience", ReaderDocument.savedTitle(article()));
    }

    @Test public void darkReadingColorsApplyOnScreenWhilePrintStaysLight() throws Exception {
        String light = ReaderDocument.html(article(), false, name -> null);
        String night = ReaderDocument.html(article(), true, name -> null);
        assertFalse(light.contains("@media screen{body{color:#eee"));
        assertTrue(night.contains("@media screen{body{color:#eee;background:#000}"));
        assertTrue("Dark mode removed print styles", night.contains("@media print{"));
    }

    @Test public void onlyXHostsAreTreatedAsX() {
        assertTrue(ReaderDocument.xHost("x.com"));
        assertTrue(ReaderDocument.xHost("mobile.twitter.com"));
        assertFalse(ReaderDocument.xHost("example.com"));
        assertFalse(ReaderDocument.xHost("notx.com"));
        assertFalse(ReaderDocument.xHost(null));
    }

    @Test public void differentLinksToTheSamePostOrArticleShareAKey() {
        assertEquals("status:123", ReaderDocument.postKey("https://x.com/alice/status/123?s=20"));
        assertEquals("status:123", ReaderDocument.postKey("https://twitter.com/alice/status/123/photo/1"));
        assertEquals("status:123", ReaderDocument.postKey("https://x.com/i/web/status/123"));
        assertEquals("article:55", ReaderDocument.postKey("https://x.com/i/article/55"));
        assertEquals("status:124", ReaderDocument.postKey("https://x.com/alice/status/124"));
        assertEquals("example.org/a", ReaderDocument.postKey("https://Example.org/a/?q=1"));
        assertNull(ReaderDocument.postKey("javascript:alert(1)"));
    }

    @Test public void sharedTextOpensItsXLinkEvenAfterAnotherLink() {
        assertEquals("https://x.com/alice/status/1", ReaderDocument.sharedLink("Read https://example.org/a, then https://x.com/alice/status/1."));
        assertEquals("https://t.co/AbC123", ReaderDocument.sharedLink("via https://t.co/AbC123)"));
        assertEquals("https://example.org/a", ReaderDocument.sharedLink("only https://example.org/a"));
        assertNull(ReaderDocument.sharedLink("no link here"));
    }

    @Test public void onlySecureXAndShortLinksOpenForCapture() {
        assertTrue(ReaderDocument.capturable("https://x.com/alice/status/1"));
        assertTrue(ReaderDocument.capturable("https://mobile.twitter.com/a/status/1"));
        assertTrue(ReaderDocument.capturable("https://t.co/AbC123"));
        assertFalse(ReaderDocument.capturable("http://x.com/alice/status/1"));
        assertFalse(ReaderDocument.capturable("https://example.org/"));
        assertFalse(ReaderDocument.capturable("https://t.co.evil.example/x"));
    }

    @Test public void storedImagesRenderByNameAndUnsafeNamesFallBackToPlaceholders() throws Exception {
        JSONObject pictured = new JSONObject().put("blocks", new JSONArray()
            .put(new JSONObject().put("kind", "image").put("file", "0.jpg").put("text", "A chart"))
            .put(new JSONObject().put("kind", "image").put("file", "../library/other.json").put("text", "Escape"))
            .put(new JSONObject().put("kind", "image").put("file", "https://example.org/t.png").put("text", "Remote")));
        String shown = ReaderDocument.html(pictured, false, name -> "images/" + name);
        assertTrue("Stored image not shown", shown.contains("<figure><img src=\"images/0.jpg\" alt=\"A chart\"><figcaption>A chart</figcaption></figure>"));
        assertTrue("Unsafe image name was used", !shown.contains("../library") && !shown.contains("src=\"images/https")
            && shown.contains("<p class='note'>Image unavailable offline: Escape</p>") && shown.contains("<p class='note'>Image unavailable offline: Remote</p>"));
    }

    @Test public void imageCookiesStayOnThePagesHost() {
        assertTrue(ReaderDocument.sameHost("https://example.org/post", "https://EXAMPLE.org/i.png"));
        assertFalse(ReaderDocument.sameHost("https://attacker.example/post", "https://example.org/i.png"));
        assertFalse(ReaderDocument.sameHost("https://example.org/post", "not a url"));
    }

    @Test public void onlyRasterImageTypesAreKept() {
        assertEquals("jpg", ReaderDocument.imageExtension("image/jpg"));
        assertEquals("jpg", ReaderDocument.imageExtension("image/jpeg; charset=binary"));
        assertEquals("webp", ReaderDocument.imageExtension("IMAGE/WEBP"));
        assertNull(ReaderDocument.imageExtension("image/svg+xml"));
        assertNull(ReaderDocument.imageExtension(null));
    }
}
