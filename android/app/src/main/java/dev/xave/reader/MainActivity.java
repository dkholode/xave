package dev.xave.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.print.PrintManager;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private LinearLayout screen;
    private WebView browser;
    private TextView status;
    private String collector;
    private String mode = "library";
    private JSONObject document;
    private File currentFile;
    private File currentDraft;
    private WebView sourceBrowser;
    private String sourceUrl;
    private TextView readingProgress;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    // Image downloads run apart from io so they never delay opening, saving, or exporting articles.
    private final ExecutorService captures = Executors.newSingleThreadExecutor();
    private boolean pageFailed;
    private boolean foreground;
    private volatile int generation;
    private boolean busy;
    private int zoom;
    private String pendingExportFile;
    private boolean dark;
    private TextView tip;

    @Override public void onCreate(Bundle state) {
        String theme = getPreferences(0).getString("theme", "system");
        dark = "dark".equals(theme) || "system".equals(theme)
            && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (dark) setTheme(R.style.AppTheme_Dark);
        super.onCreate(state);
        zoom = getPreferences(0).getInt("zoom", 100);
        try (InputStream in = getAssets().open("capture.js")) {
            collector = new String(readLimited(in, 200000, Long.MAX_VALUE), StandardCharsets.UTF_8);
        } catch (Exception e) { error("Cannot load capture tools."); return; }
        io.execute(() -> { try { DocumentFiles.removeOrphanImages(getFilesDir()); } catch (Exception ignored) { } });
        showLibrary();
        if (state == null) handleIntent(getIntent());
        else {
            pendingExportFile = state.getString("pendingExportFile");
            String savedItem = state.getString("savedItem");
            if (savedItem != null && savedItem.equals(new File(savedItem).getName())
                    && new File(library(), savedItem).isFile()) openSaved(new File(library(), savedItem));
            else if (state.getString("draftItem") != null
                    && state.getString("draftItem").equals(new File(state.getString("draftItem")).getName())) {
                File draft = new File(drafts(), state.getString("draftItem"));
                // Saving may have completed while Android was recreating this screen.
                File saved = new File(library(), draft.getName());
                if (saved.isFile()) openSaved(saved);
                else if (draft.isFile()) openDraft(draft);
            }
            else if (state.getString("onlineUrl") != null) openOnline(state.getString("onlineUrl"));
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        rememberPosition();
        if ("reader".equals(mode) && currentFile != null) state.putString("savedItem", currentFile.getName());
        if ("online".equals(mode) && browser != null) state.putString("onlineUrl", browser.getUrl());
        if ("reader".equals(mode) && currentDraft != null) state.putString("draftItem", currentDraft.getName());
        state.putString("pendingExportFile", pendingExportFile);
        super.onSaveInstanceState(state);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (!Intent.ACTION_SEND.equals(intent.getAction())) return;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null) return;
        Matcher match = Pattern.compile("https?://[^\\s<>]+", Pattern.CASE_INSENSITIVE).matcher(text);
        if (match.find()) openOnline(match.group().replaceAll("[)\\],.]+$", ""));
        else error("The shared text has no web link.");
    }

    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density); }
    private int fg() { return dark ? 0xFFEEEEEE : Color.BLACK; }
    private int bg() { return dark ? Color.BLACK : Color.WHITE; }

    private void layout(String nextMode) {
        rememberPosition();
        generation++;
        busy = false;
        if (browser != null) {
            browser.stopLoading();
            if (browser.getParent() != null) ((android.view.ViewGroup)browser.getParent()).removeView(browser);
            browser.destroy(); browser = null;
        }
        mode = nextMode;
        screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(dp(10), dp(8), dp(10), 0);
        // Android 15 draws apps edge to edge, so keep content clear of system bars and the keyboard.
        screen.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(10) + insets.getSystemWindowInsetLeft(), dp(8) + insets.getSystemWindowInsetTop(),
                dp(10) + insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        screen.setBackgroundColor(bg());
        setContentView(screen);
    }

    private TextView label(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setTextColor(fg());
        view.setPadding(dp(4), dp(7), dp(4), dp(7));
        return view;
    }

    private Button button(String text, Runnable action) {
        Button b = new Button(this);
        b.setText(text); b.setTextColor(fg()); b.setAllCaps(false);
        b.setMinHeight(dp(48)); b.setOnClickListener(v -> action.run());
        return b;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        screen.addView(row);
        return row;
    }

    private void add(LinearLayout row, Button b) {
        row.addView(b, new LinearLayout.LayoutParams(0, dp(52), 1));
    }

    private void showLibrary() {
        destroySource();
        layout("library"); document = null; currentFile = null; currentDraft = null;
        screen.addView(label("Xave · Offline library", 25));
        EditText url = new EditText(this);
        url.setTextSize(18); url.setSingleLine(true); url.setHint("Paste a web link");
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        screen.addView(url);
        LinearLayout actions = row();
        add(actions, button("Open link", () -> openOnline(url.getText().toString())));
        add(actions, button("Theme", this::chooseTheme));
        screen.addView(label("Or share a link from another app to Xave. Internet is needed only to capture new items.", 16));
        File[] files = library().listFiles((dir,name) -> name.endsWith(".json"));
        int count = files == null ? 0 : files.length;
        ScrollView scroll = new ScrollView(this);
        LinearLayout entries = new LinearLayout(this); entries.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(entries); screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        File[] unfinished = drafts().listFiles((dir,name) -> name.endsWith(".json"));
        if (unfinished != null && unfinished.length > 0) {
            entries.addView(label("Unfinished captures · " + unfinished.length, 20));
            Arrays.sort(unfinished, (a,b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File draft : unfinished) addLibraryEntry(entries, draft, true);
        }
        entries.addView(label("Saved articles · " + count, 21));
        if (files == null || files.length == 0) entries.addView(label("Your saved reading will appear here.", 19));
        else {
            Arrays.sort(files, (a,b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File file : files) addLibraryEntry(entries, file, false);
        }
    }

    private void addLibraryEntry(LinearLayout entries, File file, boolean draft) {
        JSONObject summary;
        boolean repair = false;
        try {
            summary = new JSONObject(new String(Files.readAllBytes(new File(file + ".meta").toPath()), StandardCharsets.UTF_8));
            repair = !summary.has("title") || summary.optString("title").trim().isEmpty();
        } catch (Exception e) { summary = new JSONObject(); repair = true; }
        String title = summary.optString("title", draft ? "Unfinished capture" : "Saved article");
        Button item = button(title, () -> { if (draft) openDraft(file); else openSaved(file); });
        item.setTextSize(20);
        item.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        item.setPadding(dp(12), dp(12), dp(12), dp(12)); item.setMinHeight(dp(64));
        entries.addView(item);
        TextView date = label((draft ? "Draft · " : "") + summary.optString("savedAt"), 14);
        entries.addView(date);
        if (repair || ReaderDocument.genericTitle(title)) {
            int token = generation;
            io.execute(() -> {
                try {
                    JSONObject doc = draft ? readDocument(file) : readSavedDocument(file);
                    try { writeSummary(file, doc); } catch (Exception ignored) { }
                    runOnUiThread(() -> {
                        if (token == generation && !isFinishing()) {
                            item.setText(doc.optString("title", "Saved article"));
                            date.setText((draft ? "Draft · " : "") + doc.optString("savedAt"));
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> { if (token == generation) date.setText("Could not read this item · tap to retry"); });
                }
            });
        }
    }

    private JSONObject readDocument(File file) throws Exception {
        return DocumentFiles.read(file);
    }

    private void openSaved(File file) { openDocument(file, false); }
    private void openDraft(File file) { openDocument(file, true); }

    private void openDocument(File file, boolean draft) {
        if (busy) return;
        busy = true;
        int token = generation;
        io.execute(() -> {
            try {
                JSONObject doc = draft ? readDocument(file) : readSavedDocument(file);
                runOnUiThread(() -> { if (token == generation && !isFinishing()) showReader(doc, draft ? null : file, draft ? file : null); });
            } catch (Exception e) {
                runOnUiThread(() -> { if (token == generation) { busy = false; error("Could not open this " + (draft ? "draft." : "saved item.")); } });
            }
        });
    }

    private JSONObject readSavedDocument(File file) throws Exception {
        byte[] original = Files.readAllBytes(file.toPath());
        JSONObject doc = new JSONObject(new String(original, StandardCharsets.UTF_8));
        String title = ReaderDocument.savedTitle(doc);
        if (!title.equals(doc.optString("title"))) {
            doc.put("title", title);
            try {
                File backup = new File(file + ".title-backup");
                if (!backup.exists()) Files.write(backup.toPath(), original, java.nio.file.StandardOpenOption.CREATE_NEW);
                long modified = file.lastModified();
                writeAtomic(file, doc.toString());
                file.setLastModified(modified);
                writeSummary(file, doc);
            } catch (Exception e) {
                // A metadata repair must not prevent reading an otherwise valid saved article.
                android.util.Log.w("Xave", "Could not persist the improved article title", e);
            }
        }
        return doc;
    }

    private void writeSummary(File file, JSONObject doc) throws Exception {
        DocumentFiles.writeSummary(file, doc);
    }

    private void writeAtomic(File file, String text) throws Exception {
        DocumentFiles.writeAtomic(file, text);
    }

    private void openOnline(String input) {
        String url = input.trim();
        if (!url.contains("://")) url = "https://" + url;
        if (!ReaderDocument.webUrl(url) || !"https".equalsIgnoreCase(Uri.parse(url).getScheme())) {
            error("Enter a valid https:// web link."); return;
        }
        WebView resumed = sourceBrowser != null && url.equals(sourceUrl) ? sourceBrowser : null;
        if (resumed != null) sourceBrowser = null;
        else destroySource();
        layout("online"); document = null; currentFile = null; currentDraft = null;
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(screen.getWindowToken(), 0);
        LinearLayout top = row();
        add(top, button("Library", this::showLibrary));
        Button capture = button("Preview capture", this::capture);
        add(top, capture);
        status = label("Loading…", 15); screen.addView(status);
        tip = label(tipFor(Uri.parse(url).getHost()), 15); screen.addView(tip);
        browser = resumed == null ? new WebView(this) : resumed;
        if (foreground) browser.resumeTimers();
        pageFailed = false;
        browser.setBackgroundColor(bg());
        WebSettings s = browser.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false); s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false);
        browser.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String link = request.getUrl().toString();
                if (!ReaderDocument.webUrl(link) || !link.startsWith("https://")) {
                    error("This app opens secure web pages only."); return true;
                }
                return false;
            }
            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (view == browser) pageFailed = false;
            }
            @Override public void onPageFinished(WebView view, String loaded) {
                // A failed load still finishes, so keep its error message instead of reporting Ready.
                if (!"online".equals(mode) || view != browser || pageFailed) return;
                status.setText("Ready · " + Uri.parse(loaded).getHost());
                tip.setText(tipFor(Uri.parse(loaded).getHost()));
                view.evaluateJavascript(collector, null);
                CookieManager.getInstance().flush();
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError e) {
                if (request.isForMainFrame()) { pageFailed = true; status.setText("Page could not load. Check Wi-Fi and the link."); }
            }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                return renderGone(view);
            }
        });
        screen.addView(browser, new LinearLayout.LayoutParams(-1, 0, 1));
        if (resumed == null) browser.loadUrl(url);
        else {
            browser.onResume();
            status.setText("Ready · " + Uri.parse(url).getHost());
            browser.evaluateJavascript(collector, null);
        }
    }

    private void capture() {
        if (busy || browser == null) return;
        busy = true;
        int token = generation;
        status.setText("Capturing loaded content…");
        // evaluateJavascript returns data only; websites cannot call native app methods.
        browser.evaluateJavascript(collector + "\nwindow.__xaveCollector.capture();", encoded -> {
            if (token != generation) return;
            try {
                Object decoded = new JSONTokener(encoded).nextValue();
                JSONObject doc = new JSONObject((String)decoded);
                if (doc.has("error")) { busy = false; status.setText(doc.getString("error")); return; }
                doc.put("schemaVersion", 2);
                doc.put("savedAt", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date()));
                JSONArray blocks = doc.getJSONArray("blocks");
                // Snapshot cookies on the UI thread, only for images on the page's own host. Never export them.
                String source = doc.optString("source");
                ArrayList<String> cookies = new ArrayList<>();
                for (int i=0; i<blocks.length(); i++) {
                    JSONObject b = blocks.getJSONObject(i);
                    cookies.add("image".equals(b.optString("kind")) && ReaderDocument.sameHost(source, b.optString("url"))
                        ? CookieManager.getInstance().getCookie(b.optString("url")) : null);
                }
                String userAgent = browser.getSettings().getUserAgentString();
                status.setText("Downloading images for offline reading…");
                captures.execute(() -> {
                    File draft = new File(drafts(), UUID.randomUUID() + ".json");
                    embedImages(doc, cookies, userAgent, token, DocumentFiles.images(draft));
                    if (token != generation) {
                        try { DocumentFiles.delete(draft); } catch (Exception ignored) { }
                        return;
                    }
                    try {
                        writeAtomic(draft, doc.toString());
                        try { writeSummary(draft, doc); } catch (Exception ignored) { }
                        runOnUiThread(() -> { if (token == generation && !isFinishing()) showReader(doc, null, draft); });
                    } catch (Exception e) {
                        try { DocumentFiles.delete(draft); } catch (Exception ignored) { }
                        runOnUiThread(() -> { if (token == generation) {
                            busy = false; status.setText("Could not keep this capture. Check available storage and retry.");
                        } });
                    }
                });
            } catch (Exception e) { busy = false; status.setText("Could not capture this page. Wait for it to load, then retry."); }
        });
    }

    /** Saves image files next to the draft; image blocks then refer to them by file name. */
    private void embedImages(JSONObject doc, ArrayList<String> cookies, String userAgent, int token, File images) {
        JSONArray blocks = doc.optJSONArray("blocks");
        ArrayList<Integer> pictures = new ArrayList<>();
        for (int i=0; i<blocks.length(); i++) if ("image".equals(blocks.optJSONObject(i).optString("kind"))) pictures.add(i);
        int count = Math.min(40, pictures.size());
        int missing = pictures.size() - count;
        long deadline = android.os.SystemClock.elapsedRealtime() + 45000;
        AtomicLong total = new AtomicLong();
        AtomicInteger done = new AtomicInteger();
        ArrayList<Callable<String>> downloads = new ArrayList<>();
        if (count > 0) images.mkdirs();
        for (int n=0; n<count; n++) {
            int index = n;
            JSONObject block = blocks.optJSONObject(pictures.get(n));
            String cookie = cookies.get(pictures.get(n));
            downloads.add(() -> {
                try { return token == generation ? download(block.optString("url"), cookie, userAgent, new File(images, String.valueOf(index)), total, deadline) : null; }
                catch (Exception e) { return null; }
                finally {
                    int finished = done.incrementAndGet();
                    runOnUiThread(() -> { if (token == generation) status.setText("Downloading images for offline reading · " + finished + "/" + count); });
                }
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<String>> results = pool.invokeAll(downloads, 45, TimeUnit.SECONDS);
            for (int n=0; n<count; n++) {
                try {
                    String name = results.get(n).get();
                    if (name == null) missing++;
                    else blocks.getJSONObject(pictures.get(n)).put("file", name);
                } catch (Exception e) { missing++; }
            }
        } catch (InterruptedException e) { missing += count; }
        finally { pool.shutdownNow(); }
        // An inline image is now a file or a placeholder, so its payload need not stay in the article.
        for (int i : pictures) if (blocks.optJSONObject(i).optString("url").startsWith("data:")) blocks.optJSONObject(i).remove("url");
        if (missing > 0) try { doc.put("warning", doc.optString("warning") + " " + missing + " image(s) could not be saved; placeholders appear below."); } catch (Exception ignored) { }
    }

    /** Returns the saved file name, which is the given base name plus the image type's extension. */
    private static String download(String link, String cookie, String userAgent, File base, AtomicLong total, long deadline) throws Exception {
        String type;
        byte[] bytes;
        Matcher inline = Pattern.compile("data:(image/[a-z]+);base64,([A-Za-z0-9+/=]+)").matcher(link);
        if (inline.matches()) {
            type = inline.group(1);
            bytes = java.util.Base64.getDecoder().decode(inline.group(2));
            if (bytes.length > 4 * 1024 * 1024) throw new Exception("Resource exceeds size limit");
        } else {
            if (!ReaderDocument.webUrl(link) || !link.startsWith("https://")) throw new Exception("Unsupported image URL");
            HttpURLConnection connection = (HttpURLConnection)new URL(link).openConnection();
            try {
                connection.setConnectTimeout(7000); connection.setReadTimeout(7000);
                // Redirects fail visibly rather than forwarding site cookies to another host.
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", userAgent);
                if (cookie != null) connection.setRequestProperty("Cookie", cookie);
                if (connection.getResponseCode() != 200) throw new Exception("Image HTTP error");
                type = connection.getContentType();
                if (ReaderDocument.imageExtension(type) == null) throw new Exception("Unsupported image format");
                try (InputStream in = connection.getInputStream()) { bytes = readLimited(in, 4 * 1024 * 1024, deadline); }
            } finally { connection.disconnect(); }
        }
        String extension = ReaderDocument.imageExtension(type);
        if (extension == null) throw new Exception("Unsupported image format");
        if (total.addAndGet(bytes.length) > 16 * 1024 * 1024) throw new Exception("Image limit");
        File file = new File(base + "." + extension);
        Files.write(file.toPath(), bytes);
        return file.getName();
    }

    /** Without this, a crashed or memory-killed page renderer would take the whole app down with it. */
    private boolean renderGone(WebView view) {
        if (view == sourceBrowser) { destroySource(); return true; }
        if (view != browser) return true;
        ((android.view.ViewGroup)browser.getParent()).removeView(browser);
        browser.destroy(); browser = null;
        showLibrary();
        error("The page stopped unexpectedly, often because the device ran low on memory. Open it again from the library or the link.");
        return true;
    }

    private void destroySource() {
        if (sourceBrowser != null) { sourceBrowser.destroy(); sourceBrowser = null; }
        sourceUrl = null;
    }

    private void showReader(JSONObject doc, File file, File draft) {
        if (draft != null && "online".equals(mode) && browser != null) {
            destroySource();
            // A page that navigated while images downloaded no longer shows this capture's source.
            if (doc.optString("source").equals(browser.getUrl())) {
                sourceBrowser = browser; sourceUrl = doc.optString("source");
                ((android.view.ViewGroup)browser.getParent()).removeView(browser);
                sourceBrowser.onPause(); browser = null;
            }
        }
        layout("reader"); document = doc; currentFile = file; currentDraft = draft;
        LinearLayout top = row();
        add(top, button("Library", this::showLibrary));
        add(top, button(file == null ? "Save offline" : "Export", file == null ? this::saveDocument : this::exportDocument));
        add(top, button("More", this::readerOptions));
        browser = new WebView(this);
        // Timers are global; a capture finishing in the background must not restart page scripts.
        if (foreground) browser.resumeTimers();
        browser.setBackgroundColor(bg());
        WebSettings s = browser.getSettings();
        s.setJavaScriptEnabled(false); s.setAllowFileAccess(false); s.setAllowContentAccess(false);
        s.setBlockNetworkLoads(true); s.setTextZoom(zoom);
        File positionFile = file == null ? draft : file;
        File images = DocumentFiles.images(positionFile);
        browser.setWebViewClient(new WebViewClient() {
            // Network loads stay blocked; the article's own images are served from app storage.
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri link = request.getUrl();
                String name = link.getLastPathSegment();
                if (!"offline.xave.invalid".equals(link.getHost()) || !("/images/" + name).equals(link.getPath())
                        || !ReaderDocument.imageFile(name)) return null;
                try { return new WebResourceResponse(ReaderDocument.imageType(name), null, new FileInputStream(new File(images, name))); }
                catch (Exception e) { return null; }
            }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                return renderGone(view);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String link = request.getUrl().toString();
                if (ReaderDocument.webUrl(link)) new AlertDialog.Builder(MainActivity.this)
                    .setMessage("Open this link online?\n" + link).setNegativeButton("Cancel", null)
                    .setPositiveButton("Open", (d,w) -> openOnline(link)).show();
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (view != browser) return;
                float ratio = positionFile == null ? 0 : getPreferences(0).getFloat("position:" + positionFile.getName(), 0);
                view.postVisualStateCallback(0, new WebView.VisualStateCallback() {
                    @Override public void onComplete(long requestId) {
                        if (view != browser) return;
                        if (ratio > 0 && view.getScrollY() == 0)
                            view.scrollTo(0, (int)(ratio * Math.max(0, view.getContentHeight() * view.getScale() - view.getHeight())));
                        updateReadingProgress();
                    }
                });
            }
        });
        browser.setOnScrollChangeListener((view,x,y,oldX,oldY) -> updateReadingProgress());
        screen.addView(browser, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout footer = row();
        add(footer, button("Prev", () -> browser.pageUp(false)));
        LinearLayout info = new LinearLayout(this); info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(android.view.Gravity.CENTER);
        TextView state = label(file == null ? "Preview · draft kept" : "Available offline", 12);
        state.setPadding(0,0,0,0); info.addView(state);
        readingProgress = label("0%", 14); readingProgress.setPadding(0,0,0,0); info.addView(readingProgress);
        footer.addView(info, new LinearLayout.LayoutParams(0, dp(52), 1));
        add(footer, button("Next", () -> browser.pageDown(false)));
        browser.loadDataWithBaseURL("https://offline.xave.invalid/", ReaderDocument.html(doc, dark, name -> "images/" + name), "text/html", "UTF-8", null);
    }

    private void updateReadingProgress() {
        if (!"reader".equals(mode) || browser == null || readingProgress == null) return;
        float range = browser.getContentHeight() * browser.getScale() - browser.getHeight();
        int percent = browser.getContentHeight() == 0 ? 0 : range <= dp(2) ? 100 : Math.max(0, Math.min(100, Math.round(browser.getScrollY() * 100 / range)));
        readingProgress.setText(percent + "%");
    }

    private void readerOptions() {
        boolean draft = currentDraft != null;
        new AlertDialog.Builder(this).setTitle("Reading options")
            .setItems(new String[]{"Text size", "Theme", draft ? "Back to source" : "Open source online",
                draft ? "Discard draft" : "Delete from library"}, (dialog,which) -> {
                if (which == 0) {
                    LinearLayout sizes = new LinearLayout(this);
                    sizes.addView(button("A−", () -> changeZoom(-10)), new LinearLayout.LayoutParams(0,dp(52),1));
                    sizes.addView(button("A+", () -> changeZoom(10)), new LinearLayout.LayoutParams(0,dp(52),1));
                    new AlertDialog.Builder(this).setTitle("Text size").setView(sizes).setPositiveButton("Done", null).show();
                } else if (which == 1) chooseTheme();
                else if (which == 2) openOnline(document.optString("source"));
                else deleteCurrent();
            }).show();
    }

    private void chooseTheme() {
        String[] values = {"system", "light", "dark"};
        int checked = Arrays.asList(values).indexOf(getPreferences(0).getString("theme", "system"));
        new AlertDialog.Builder(this).setTitle("Theme")
            .setSingleChoiceItems(new String[]{"Follow system", "Light (best for e-ink)", "Dark"}, checked, (dialog,which) -> {
                dialog.dismiss();
                if (which == checked) return;
                getPreferences(0).edit().putString("theme", values[which]).apply();
                recreate();
            }).show();
    }

    private String tipFor(String host) {
        return ReaderDocument.xHost(host)
            ? "X: sign in here if needed, expand long posts, and scroll to load the thread. Then preview and check the captured posts."
            : "Sign in if the site requires it and scroll to load everything you want to keep. Then preview the capture.";
    }

    private void deleteCurrent() {
        boolean draft = currentDraft != null;
        File target = draft ? currentDraft : currentFile;
        if (target == null || busy) return;
        new AlertDialog.Builder(this).setMessage(draft ? "Discard this unfinished capture?" : "Delete this article from your library? This cannot be undone.")
            .setNegativeButton("Keep", null)
            .setPositiveButton(draft ? "Discard" : "Delete", (dialog,which) -> {
                busy = true; int token = generation;
                io.execute(() -> {
                    try {
                        DocumentFiles.delete(target);
                        runOnUiThread(() -> { if (token == generation) {
                            showLibrary();
                            // Cleared after leaving the reader, which records the position on exit.
                            getPreferences(0).edit().remove("position:" + target.getName()).apply();
                        } });
                    } catch (Exception e) { runOnUiThread(() -> { if (token == generation) { busy = false; error(draft ? "Could not discard this draft." : "Could not delete this article."); } }); }
                });
            }).show();
    }

    private void changeZoom(int delta) {
        zoom = Math.max(70, Math.min(190, zoom + delta));
        getPreferences(0).edit().putInt("zoom", zoom).apply();
        browser.getSettings().setTextZoom(zoom);
    }

    private void saveDocument() {
        if (busy || document == null || currentDraft == null) return;
        rememberPosition();
        busy = true;
        JSONObject doc = document;
        File draft = currentDraft;
        int token = generation;
        io.execute(() -> {
            try {
                File file = DocumentFiles.save(draft, library(), doc);
                runOnUiThread(() -> { if (token == generation && !isFinishing()) {
                    destroySource();
                    showReader(doc, file, null);
                    Toast.makeText(this,"Saved for offline reading",Toast.LENGTH_LONG).show();
                } });
            } catch (Exception e) {
                runOnUiThread(() -> { if (token == generation) { busy = false; error("Could not finish saving. Your draft is kept; retry when storage is available."); } });
            }
        });
    }

    private void exportDocument() {
        new AlertDialog.Builder(this).setTitle("Export saved reading")
            .setItems(new String[]{"Self-contained HTML file", "Print / Save as PDF"}, (dialog,which) -> {
                if (which == 1) {
                    if (browser.getProgress() < 100) { error("The article is still loading. Try again in a moment."); return; }
                    try {
                        ((PrintManager)getSystemService(PRINT_SERVICE)).print("Xave", browser.createPrintDocumentAdapter("Xave"), null);
                    } catch (Exception e) { error("Printing is unavailable on this device. Export an HTML file instead."); }
                } else {
                    pendingExportFile = currentFile.getName();
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("text/html");
                    String title = document.optString("title", "xave").replaceAll("[^\\p{L}\\p{N} ._-]", "").trim();
                    if (title.isEmpty()) title = "xave";
                    intent.putExtra(Intent.EXTRA_TITLE, title.substring(0, Math.min(80, title.length())) + ".html");
                    try { startActivityForResult(intent, 1); }
                    catch (android.content.ActivityNotFoundException e) {
                        pendingExportFile = null;
                        error("This device has no file picker for HTML export.");
                    }
                }
            }).show();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 1) {
            String filename = pendingExportFile; pendingExportFile = null;
            if (result != RESULT_OK || data == null || data.getData() == null || filename == null) return;
            Uri target = data.getData();
            io.execute(() -> {
                try {
                    File saved = new File(library(), filename);
                    JSONObject doc = new JSONObject(new String(Files.readAllBytes(saved.toPath()), StandardCharsets.UTF_8));
                    File images = DocumentFiles.images(saved);
                    String html = ReaderDocument.html(doc, false, name -> {
                        try { return DocumentFiles.dataUrl(new File(images, name)); } catch (Exception e) { return null; }
                    });
                    try (OutputStream out = getContentResolver().openOutputStream(target)) {
                        if (out == null) throw new Exception("File picker returned no output stream");
                        out.write(html.getBytes(StandardCharsets.UTF_8));
                    }
                    runOnUiThread(() -> Toast.makeText(this, "Exported", Toast.LENGTH_SHORT).show());
                } catch (Exception e) { runOnUiThread(() -> error("Could not export the file.")); }
            });
        }
    }

    private File drafts() { File dir = new File(getFilesDir(), "drafts"); dir.mkdirs(); return dir; }

    private File library() { File dir = new File(getFilesDir(), "library"); dir.mkdirs(); return dir; }

    private void rememberPosition() {
        File positionFile = currentFile == null ? currentDraft : currentFile;
        if (!"reader".equals(mode) || browser == null || positionFile == null) return;
        float range = browser.getContentHeight() * browser.getScale() - browser.getHeight();
        float ratio = range <= dp(2) ? 0 : Math.max(0, Math.min(1, browser.getScrollY() / range));
        getPreferences(0).edit().putFloat("position:" + positionFile.getName(), ratio).apply();
    }

    @Override protected void onPause() {
        foreground = false;
        rememberPosition();
        // WebView.onPause stops media but not page scripts; pauseTimers stops JavaScript in every WebView.
        if (browser != null) { browser.onPause(); browser.pauseTimers(); }
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        if (browser != null) { browser.resumeTimers(); browser.onResume(); }
    }
    @Override public void onBackPressed() {
        if ("reader".equals(mode) && currentDraft != null && sourceBrowser != null) openOnline(document.optString("source"));
        else if ("online".equals(mode) && browser != null && browser.canGoBack()) browser.goBack();
        else if (!"library".equals(mode)) showLibrary();
        else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        generation++;
        if (browser != null) browser.destroy();
        destroySource();
        io.shutdown();
        captures.shutdown();
        super.onDestroy();
    }
    private void error(String message) {
        // Background work can finish after this screen is gone, and a dialog then would crash.
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this).setMessage(message).setPositiveButton("OK", null).show();
    }

    /** The deadline bounds a server that trickles bytes, which the per-read timeout alone does not. */
    static byte[] readLimited(InputStream in, int limit, long deadline) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (out.size() + n > limit) throw new Exception("Resource exceeds size limit");
            if (Thread.interrupted() || android.os.SystemClock.elapsedRealtime() > deadline) throw new Exception("Download stopped");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
