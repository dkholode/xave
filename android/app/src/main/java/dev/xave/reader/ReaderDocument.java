package dev.xave.reader;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class ReaderDocument {
    // capture.js titles every thread capture this way, so it identifies threads in old and new saves alike.
    private static final Pattern THREAD_TITLE = Pattern.compile("@([A-Za-z0-9_]{1,15}) · (\\d+) posts?");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\[(.+)\\]", Pattern.DOTALL);
    private static final Pattern STATUS_PATH = Pattern.compile("/(?:[^/]+|i/web)/status(?:es)?/(\\d+)");
    private static final Pattern ARTICLE_PATH = Pattern.compile("/(?:i|[^/]+)/article/(\\d+)");
    private static final Pattern SHARED_LINK = Pattern.compile("https?://[^\\s<>]+", Pattern.CASE_INSENSITIVE);

    /** Reading styles shared by the screen, print, and exported files. */
    private static final String STYLE = ""
        + "body{color:#000;background:#fff;font:21px/1.55 Georgia,serif;margin:14px 16px;overflow-wrap:anywhere}"
        + "h1{font-size:1.25em;line-height:1.25;margin:0 0 6px}"
        + "h2{font-size:1.15em}"
        + "h3,h4,h5,h6{font-size:1.05em}"
        + "p{white-space:pre-wrap;margin:.8em 0}"
        + "h2,h3,h4,h5,h6{break-after:avoid}"
        + "img{max-width:100%;height:auto}"
        + "pre{white-space:pre-wrap;font-size:.8em;border:1px solid;padding:10px}"
        + "figcaption{font:15px/1.5 sans-serif}"
        + ".byline{font:15px/1.4 sans-serif;opacity:.65;margin:0 0 22px}"
        + ".byline a{color:inherit}"
        + ".post-meta{font:14px/1.4 sans-serif;margin:0 0 6px;break-after:avoid}"
        + ".post-meta a{display:flex;justify-content:space-between;color:inherit;text-decoration:none;opacity:.6}"
        + ".note{font:15px/1.4 sans-serif;white-space:normal;border:1px solid;border-radius:8px;padding:8px 12px;opacity:.7}"
        + "ul,ol{padding-left:1.4em;margin:.8em 0}li{margin:.4em 0;white-space:pre-wrap}"
        + "blockquote{border-left:3px solid;margin:1em 0;padding-left:14px;white-space:pre-wrap}"
        + "a{color:#000;text-decoration:underline}"
        + "hr{border:0;border-top:1px solid #000;opacity:.3;margin:24px 0}"
        + "figure{margin:16px 0}"
        + ".print-info{font:11px/1.4 sans-serif;margin-top:32px}";

    private static boolean genericTitle(String title) {
        return title == null || title.trim().isEmpty() || title.trim().matches("(?i)(conversation|post|article|home|x|twitter)(\\s*[/|]\\s*(x|twitter))?");
    }

    static boolean xHost(String host) {
        return host != null && host.matches("(?i)(.*\\.)?(x|twitter)\\.com");
    }

    /** X sometimes names an Article's page "Conversation"; the first line after the author's handle reads better. */
    static String savedTitle(JSONObject document) {
        String title = document.optString("title").trim();
        if (!genericTitle(title)) {
            return title;
        } else {
            JSONArray blocks = document.optJSONArray("blocks");
            boolean sawHandle = false;
            if (blocks != null) {
                for(int i = 0; i < Math.min(12, blocks.length()); ++i) {
                    JSONObject block = blocks.optJSONObject(i);
                    if (block != null && "text".equals(block.optString("kind"))) {
                        String text = block.optString("text").replaceAll("\\s+", " ").trim();
                        if (text.matches("@[A-Za-z0-9_]{1,15}")) {
                            sawHandle = true;
                        } else if (sawHandle && text.length() >= 12 && !genericTitle(text)) {
                            return text.length() > 160 ? text.substring(0, 157) + "…" : text;
                        }
                    }
                }
            }

            return title.isEmpty() ? "Saved article" : title;
        }
    }

    /** Library row text: the first post of a thread, or the title of an X Article, plus the source link so a new link can find its saved copy. */
    static JSONObject summary(JSONObject document) throws JSONException {
        Matcher thread = THREAD_TITLE.matcher(document.optString("title"));
        if (thread.matches()) {
            return new JSONObject().put("title", firstPost(document)).put("detail", thread.group()).put("source", document.optString("source"));
        }
        String handle = handle(document.optString("source"));
        return new JSONObject().put("title", document.optString("title")).put("detail", handle.isEmpty() ? "Article" : "@" + handle + " · Article").put("source", document.optString("source"));
    }

    /** The confirmation shown once a capture is saved, such as "Saved 6 posts by @demo_dev", plus a count of images whose download failed. */
    static String savedMessage(JSONObject document) {
        Matcher thread = THREAD_TITLE.matcher(document.optString("title"));
        String message;
        if (thread.matches()) {
            message = "Saved " + thread.group(2) + (thread.group(2).equals("1") ? " post" : " posts") + " by @" + thread.group(1);
        } else {
            String handle = handle(document.optString("source"));
            message = handle.isEmpty() ? "Saved X Article" : "Saved X Article by @" + handle;
        }
        int missing = imagesNotSaved(document);
        return missing == 0 ? message : message + " · " + missing + (missing == 1 ? " image" : " images") + " not saved";
    }

    /** Posts in a thread capture, read from its title; 0 for an Article, even one titled like a thread. */
    static int postCount(JSONObject document) {
        Matcher thread = THREAD_TITLE.matcher(document.optString("title"));
        try { return thread.matches() ? Integer.parseInt(thread.group(2)) : 0; }
        catch (NumberFormatException tooLarge) { return 0; }
    }

    /** Image blocks in a capture, whether or not their files downloaded. */
    static int imageCount(JSONObject document) {
        JSONArray blocks = document.optJSONArray("blocks");
        int count = 0;
        for (int i = 0; blocks != null && i < blocks.length(); ++i) {
            JSONObject block = blocks.optJSONObject(i);
            if (block != null && "image".equals(block.optString("kind"))) ++count;
        }
        return count;
    }

    /** Image blocks without a stored file, which means their download failed. */
    private static int imagesNotSaved(JSONObject document) {
        JSONArray blocks = document.optJSONArray("blocks");
        int count = 0;
        for (int i = 0; blocks != null && i < blocks.length(); ++i) {
            JSONObject block = blocks.optJSONObject(i);
            if (block != null && "image".equals(block.optString("kind")) && !imageFile(block.optString("file"))) ++count;
        }
        return count;
    }

    private static String firstPost(JSONObject document) {
        JSONArray blocks = document.optJSONArray("blocks");
        for (int i = 0; blocks != null && i < blocks.length(); ++i) {
            JSONObject block = blocks.optJSONObject(i);
            if (block == null || !"text".equals(block.optString("kind"))) continue;
            String text = block.optString("text").replaceAll("\\s+", " ").trim();
            if (!text.isEmpty() && !PLACEHOLDER.matcher(text).matches()) {
                return text.length() > 160 ? text.substring(0, 157) + "…" : text;
            }
        }
        return document.optString("title");
    }

    /** The account in an x.com/handle/status/... link, or empty for share links. */
    private static String handle(String source) {
        try {
            URI uri = new URI(source);
            String[] path = uri.getPath().split("/");
            if (xHost(uri.getHost()) && path.length > 1 && path[1].matches("[A-Za-z0-9_]{1,15}") && !path[1].equals("i")) {
                return path[1];
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** X gives ISO times on signed-in pages and display text on anonymous ones; only the former needs formatting. */
    private static String postDate(String text) {
        try {
            return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(Instant.parse(text).atZone(ZoneId.systemDefault()));
        } catch (DateTimeParseException ignored) {
            return text;
        }
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    static boolean webUrl(String url) {
        try {
            URI uri = new URI(url);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** The post or Article a link points to, so different links to the same one match; null for anything but a web link. */
    static String postKey(String url) {
        if (!webUrl(url)) return null;
        try {
            URI uri = new URI(url);
            String path = uri.getPath();
            if (xHost(uri.getHost())) {
                Matcher status = STATUS_PATH.matcher(path);
                if (status.lookingAt()) return "status:" + status.group(1);
                Matcher article = ARTICLE_PATH.matcher(path);
                if (article.lookingAt()) return "article:" + article.group(1);
                return "x.com" + path.replaceAll("/+$", "");
            }
            return uri.getHost().toLowerCase(Locale.ROOT) + path.replaceAll("/+$", "");
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Whether the capture screen may open a link: https to X, or an X short link that redirects there. */
    static boolean capturable(String url) {
        if (!webUrl(url)) return false;
        try {
            URI uri = new URI(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && (xHost(uri.getHost()) || "t.co".equalsIgnoreCase(uri.getHost()));
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * The link to open from shared text: its first X link, else its first t.co link, else its first web link, else null.
     * A post shared with its text can carry a t.co link of its own before the post's link.
     */
    static String sharedLink(String text) {
        if (text == null) return null;
        List<String> links = new ArrayList<>();
        Matcher match = SHARED_LINK.matcher(text);
        while (match.find()) links.add(match.group().replaceAll("[)\\],.]+$", ""));
        for (String link : links) {
            if (capturable(link) && xHost(URI.create(link).getHost())) return link;
        }
        for (String link : links) {
            if (capturable(link)) return link;
        }
        return links.isEmpty() ? null : links.get(0);
    }

    private static String inline(JSONObject block) {
        JSONArray runs = block.optJSONArray("runs");
        if (runs != null && runs.length() != 0) {
            StringBuilder html = new StringBuilder();

            for(int i = 0; i < runs.length(); ++i) {
                JSONObject run = runs.optJSONObject(i);
                if (run != null) {
                    String text = escape(run.optString("text"));
                    if (run.optBoolean("code")) {
                        text = "<code>" + text + "</code>";
                    }

                    if (run.optBoolean("italic")) {
                        text = "<em>" + text + "</em>";
                    }

                    if (run.optBoolean("bold")) {
                        text = "<strong>" + text + "</strong>";
                    }

                    if (webUrl(run.optString("url"))) {
                        text = "<a href=\"" + escape(run.optString("url")) + "\">" + text + "</a>";
                    }

                    html.append(text);
                }
            }

            return html.toString();
        } else {
            return escape(block.optString("text"));
        }
    }

    /** Site cookies go only to images on the captured page's own host, never to hosts a page names. */
    static boolean sameHost(String page, String image) {
        try {
            String host = new URI(page).getHost();
            return host != null && host.equalsIgnoreCase(new URI(image).getHost());
        } catch (Exception ignored) {
            return false;
        }
    }

    static String imageExtension(String contentType) {
        String type = contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        switch (type) {
            case "image/png": return "png";
            case "image/jpeg": case "image/jpg": return "jpg";
            case "image/gif": return "gif";
            case "image/webp": return "webp";
            default: return null;
        }
    }

    static String imageType(String file) {
        String extension = file.substring(file.lastIndexOf('.') + 1);
        return "image/" + ("jpg".equals(extension) ? "jpeg" : extension);
    }

    /** Stored image names are generated by the app, so anything else in a document is ignored. */
    static boolean imageFile(String name) {
        return name != null && name.matches("\\d{1,3}\\.(png|jpg|gif|webp)");
    }

    /** Exported HTML files show the save date and source on screen. */
    static String html(JSONObject document, Function<String, String> image) {
        return html(document, false, true, image);
    }

    /** Dark colors apply on screen only, so printing and PDF export stay black on white. */
    static String html(JSONObject document, boolean dark, Function<String, String> image) {
        return html(document, dark, false, image);
    }

    private static String html(JSONObject document, boolean dark, boolean export, Function<String, String> image) {
        String title = document.optString("title");
        Matcher thread = THREAD_TITLE.matcher(title);
        boolean isThread = thread.matches();
        StringBuilder html = new StringBuilder("<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><meta http-equiv='Content-Security-Policy' content=\"default-src 'none'; img-src data: 'self'; style-src 'unsafe-inline'\"><title>" + escape(title) + "</title><style>" + STYLE + (export ? "" : ".print-info{display:none}@media print{.print-info{display:block}}") + (dark ? "@media screen{body{color:#eee;background:#000}a{color:#eee}hr{border-top-color:#eee}}" : "") + "</style></head><body>");
        String source = document.optString("source");
        String open = webUrl(source) ? "<a href=\"" + escape(source) + "\">Open on X</a>" : "";
        String byline;
        if (isThread) {
            html.append("<h1>@").append(thread.group(1)).append("</h1>");
            byline = thread.group(2).equals("1") ? "Post" : thread.group(2) + " posts";
            if (!open.isEmpty()) byline += " · " + open;
        } else {
            html.append("<h1>").append(escape(title)).append("</h1>");
            String handle = handle(source);
            byline = handle.isEmpty() ? open : "@" + handle + (open.isEmpty() ? "" : " · " + open);
        }
        if (!byline.isEmpty()) html.append("<p class='byline'>").append(byline).append("</p>");

        JSONArray blocks = document.optJSONArray("blocks");
        if (blocks != null && isThread) {
            List<List<JSONObject>> posts = new ArrayList<>();
            posts.add(new ArrayList<>());
            for (int i = 0; i < blocks.length(); ++i) {
                JSONObject block = blocks.optJSONObject(i);
                if (block == null) continue;
                if ("divider".equals(block.optString("kind"))) posts.add(new ArrayList<>());
                else posts.get(posts.size() - 1).add(block);
            }
            for (int i = 0; i < posts.size(); ++i) {
                if (i > 0) html.append("<hr>");
                JSONObject link = null;
                for (JSONObject block : posts.get(i)) {
                    if ("link".equals(block.optString("kind")) && webUrl(block.optString("url"))) link = block;
                }
                if (link != null) {
                    html.append("<p class='post-meta'><a href=\"").append(escape(link.optString("url"))).append("\"><span>").append(i + 1).append(" / ").append(posts.size())
                        .append("</span><span>").append(escape(postDate(link.optString("text")))).append("</span></a></p>");
                }
                List<JSONObject> body = new ArrayList<>(posts.get(i));
                body.remove(link);
                renderBlocks(html, body, image);
            }
        } else if (blocks != null) {
            List<JSONObject> all = new ArrayList<>();
            for (int i = 0; i < blocks.length(); ++i) {
                JSONObject block = blocks.optJSONObject(i);
                if (block != null) all.add(block);
            }
            renderBlocks(html, all, image);
        }

        String savedAt = document.optString("savedAt");
        if (!savedAt.isEmpty()) {
            html.append("<div class='print-info'>Saved ").append(escape(savedAt)).append("<br>").append(escape(source)).append("</div>");
        }
        return html.append("</body></html>").toString();
    }

    /** Consecutive list items share open lists, so screen readers announce them as lists; other blocks close every open list. */
    private static void renderBlocks(StringBuilder html, List<JSONObject> blocks, Function<String, String> image) {
        List<String> open = new ArrayList<>();
        for (JSONObject block : blocks) {
            if (!"listItem".equals(block.optString("kind"))) {
                closeLists(html, open, 0);
                block(html, block, image);
                continue;
            }
            int depth = Math.max(1, Math.min(8, block.optInt("depth", 1)));
            String marker = block.optString("marker", "•");
            boolean ordered = marker.matches("\\d+\\.");
            String tag = ordered ? "ol" : "ul";
            closeLists(html, open, depth);
            if (open.size() == depth && open.get(depth - 1).equals(tag)) {
                html.append("</li>");
            } else {
                closeLists(html, open, depth - 1);
            }
            while (open.size() < depth) {
                html.append('<').append(tag).append('>');
                open.add(tag);
            }
            html.append(ordered ? "<li value=\"" + marker.substring(0, marker.length() - 1) + "\">" : "<li>").append(inline(block));
        }
        closeLists(html, open, 0);
    }

    private static void closeLists(StringBuilder html, List<String> open, int level) {
        while (open.size() > level) html.append("</li></").append(open.remove(open.size() - 1)).append('>');
    }

    private static void block(StringBuilder html, JSONObject block, Function<String, String> image) {
        String text = inline(block);
        switch (block.optString("kind")) {
            case "divider":
                html.append("<hr>");
                break;
            case "heading":
                int level = Math.max(2, Math.min(6, block.optInt("level", 2)));
                html.append("<h").append(level).append(">").append(text).append("</h").append(level).append(">");
                break;
            case "code":
                html.append("<pre>").append(escape(block.optString("text"))).append("</pre>");
                break;
            case "quote":
                html.append("<blockquote>").append(text).append("</blockquote>");
                break;
            case "link":
                if (webUrl(block.optString("url"))) {
                    html.append("<p><a href=\"").append(escape(block.optString("url"))).append("\">").append(text).append("</a></p>");
                }
                break;
            case "image":
                text = escape(block.optString("text"));
                String src = imageFile(block.optString("file")) ? image.apply(block.optString("file")) : null;
                if (src != null) {
                    html.append("<figure><img src=\"").append(src).append("\" alt=\"").append(text).append("\">");
                    if (!text.isEmpty() && !"Image".equals(text)) {
                        html.append("<figcaption>").append(text).append("</figcaption>");
                    }

                    html.append("</figure>");
                } else {
                    html.append("<p class='note'>Image unavailable offline: ").append(text).append("</p>");
                }
                break;
            default:
                Matcher placeholder = PLACEHOLDER.matcher(block.optString("text").trim());
                if (block.optJSONArray("runs") == null && placeholder.matches()) {
                    String note = escape(placeholder.group(1));
                    String url = block.optString("url");
                    html.append("<p class='note'>").append(webUrl(url) ? "<a href=\"" + escape(url) + "\">" + note + "</a>" : note).append("</p>");
                } else {
                    html.append("<p>").append(text).append("</p>");
                }
        }
    }
}
