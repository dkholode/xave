package dev.xave.reader;

import java.net.URI;
import org.json.JSONArray;
import org.json.JSONObject;

final class ReaderDocument {
    static boolean genericTitle(String title) {
        return title == null || title.trim().isEmpty() || title.trim().matches("(?i)(conversation|post|article|home|x|twitter)(\\s*[/|]\\s*(x|twitter))?");
    }

    static boolean xHost(String host) {
        return host != null && host.matches("(?i)(.*\\.)?(x|twitter)\\.com");
    }

    static String savedTitle(JSONObject document) {
        String title = document.optString("title").trim();
        if (!genericTitle(title)) {
            return title;
        } else {
            try {
                String host = (new URI(document.optString("source"))).getHost();
                if (!xHost(host)) {
                    return title.isEmpty() ? "Saved article" : title;
                }
            } catch (Exception ignored) {
                return title.isEmpty() ? "Saved article" : title;
            }

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

    static String html(JSONObject document) {
        return html(document, false);
    }

    /** Dark colors apply on screen only, so printing and PDF export stay black on white. */
    static String html(JSONObject document, boolean dark) {
        StringBuilder html = new StringBuilder("<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><meta http-equiv='Content-Security-Policy' content=\"default-src 'none'; img-src data:; style-src 'unsafe-inline'\"><title>" + escape(document.optString("title")) + "</title><style>body{color:#000;background:#fff;font:21px/1.55 Georgia,serif;margin:14px 16px;overflow-wrap:anywhere}h1{font-size:1.25em;line-height:1.25;margin:0 0 12px}h2{font-size:1.15em}h3,h4,h5,h6{font-size:1.05em}p{white-space:pre-wrap;margin:.8em 0}h2,h3,h4,h5,h6{break-after:avoid}img{max-width:100%;height:auto}pre{white-space:pre-wrap;font-size:.8em;border:1px solid;padding:10px}header,details,figcaption{font:15px/1.5 sans-serif}details{margin:8px 0}summary{padding:6px 0;cursor:pointer}.warning{border-left:3px solid;padding-left:10px}.list-item{padding-left:1.3em;text-indent:-1.3em;margin:.4em 0}blockquote{border-left:3px solid;margin:1em 0;padding-left:14px;white-space:pre-wrap}a{color:#000;text-decoration:underline}hr{border:0;border-top:1px solid #000;margin:24px 0}figure{margin:16px 0}.print-info{display:none}@media print{details{display:none}.print-info{display:block;font:11px/1.4 sans-serif}}" + (dark ? "@media screen{body{color:#eee;background:#000}a{color:#eee}hr{border-top-color:#eee}}" : "") + "</style></head><body><h1>" + escape(document.optString("title")) + "</h1>");
        String source = document.optString("source");
        String host = "Article";

        try {
            String sourceHost = (new URI(source)).getHost();
            if (sourceHost != null) {
                host = sourceHost;
            }
        } catch (Exception ignored) {
        }

        String details = "Saved " + escape(document.optString("savedAt")) + "<br>" + escape(source);
        html.append("<details><summary>").append(escape(host)).append(" · Article details</summary><p>").append(details).append("</p></details><div class='print-info'>").append(details).append("</div>");
        String warning = document.optString("warning");
        if (!warning.isEmpty()) {
            String summary = warning.startsWith("Partial capture:") ? "Partial capture · check captured posts" : "Capture notes";
            if (warning.contains("image(s) could not be saved")) {
                summary = summary + " · missing images";
            }

            html.append("<details class='warning'><summary>").append(summary).append("</summary><p>").append(escape(warning)).append("</p></details><div class='print-info'>").append(escape(warning)).append("</div>");
        }

        JSONArray blocks = document.optJSONArray("blocks");
        if (blocks != null) {
            for(int i = 0; i < blocks.length(); ++i) {
                JSONObject block = blocks.optJSONObject(i);
                if (block != null) {
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
                        case "listItem":
                            html.append("<p class='list-item' style='margin-left:").append(Math.max(0, Math.min(8, block.optInt("depth", 1)) - 1)).append("em'>").append(escape(block.optString("marker", "•"))).append(" ").append(text).append("</p>");
                            break;
                        case "link":
                            if (webUrl(block.optString("url"))) {
                                html.append("<p><a href=\"").append(escape(block.optString("url"))).append("\">").append(text).append("</a></p>");
                            }
                            break;
                        case "image":
                            text = escape(block.optString("text"));
                            String data = block.optString("data");
                            if (data.matches("data:image/(png|jpeg|gif|webp);base64,[A-Za-z0-9+/=\\r\\n]+")) {
                                html.append("<figure><img src=\"").append(data).append("\" alt=\"").append(text).append("\">");
                                if (!text.isEmpty() && !"Image".equals(text)) {
                                    html.append("<figcaption>").append(text).append("</figcaption>");
                                }

                                html.append("</figure>");
                            } else {
                                html.append("<p>[Image unavailable offline: ").append(text).append("]</p>");
                            }
                            break;
                        default:
                            html.append("<p>").append(text).append("</p>");
                    }
                }
            }
        }

        return html.append("</body></html>").toString();
    }
}
