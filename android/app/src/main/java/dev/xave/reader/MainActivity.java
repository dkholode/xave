package dev.xave.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.print.PrintManager;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;
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
    // Orphaned images come only from a process that died mid-capture, so one check per process is enough.
    private static boolean orphansChecked;
    private volatile int generation;
    private boolean busy;
    private int zoom;
    private String pendingExportFile;
    private boolean dark;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::pollStatus;
    private final Set<File> selected = new HashSet<>();

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
        if (!orphansChecked) {
            orphansChecked = true;
            // Queued ahead of this screen's captures, and later screens never sweep, so no capture in flight is touched.
            captures.execute(() -> { try { DocumentFiles.removeOrphanImages(getFilesDir()); } catch (Exception ignored) { } });
        }
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
    private int muted() { return dark ? 0xFFAAAAAA : 0xFF555555; }

    private void layout(String nextMode) {
        rememberPosition();
        generation++;
        busy = false;
        ui.removeCallbacks(poll);
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

    private GradientDrawable outline(boolean filled) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(24)); shape.setStroke(dp(2), fg());
        if (filled) shape.setColor(fg());
        return shape;
    }

    // Outlined and filled pills instead of grey Material buttons, which dither on e-ink screens.
    private TextView pill(String text, boolean filled, Runnable action) {
        TextView b = new TextView(this);
        b.setText(text); b.setTextSize(16); b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(filled ? bg() : fg()); b.setBackground(outline(filled));
        b.setGravity(Gravity.CENTER); b.setMinHeight(dp(44)); b.setPadding(dp(20), 0, dp(20), 0);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private TextView icon(String glyph, View.OnClickListener action) {
        TextView b = label(glyph, 30);
        b.setGravity(Gravity.CENTER); b.setMinWidth(dp(48)); b.setMinHeight(dp(48));
        b.setOnClickListener(action);
        return b;
    }

    private LinearLayout bar() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        screen.addView(row);
        return row;
    }

    private void divider(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(fg() & 0x40FFFFFF);
        parent.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private void section(LinearLayout entries, String text) {
        TextView view = label(text.toUpperCase(java.util.Locale.ROOT), 13);
        view.setTypeface(Typeface.DEFAULT_BOLD); view.setTextColor(muted()); view.setLetterSpacing(0.06f);
        view.setPadding(0, dp(18), 0, dp(4));
        entries.addView(view);
    }

    private void popup(View anchor, String[] items, IntConsumer choose) {
        PopupMenu menu = new PopupMenu(this, anchor);
        for (int i = 0; i < items.length; i++) menu.getMenu().add(0, i, i, items[i]);
        menu.setOnMenuItemClickListener(item -> { choose.accept(item.getItemId()); return true; });
        menu.show();
    }

    private static File[] newestFirst(File dir) {
        File[] files = dir.listFiles((d,name) -> name.endsWith(".json"));
        if (files == null) return new File[0];
        Arrays.sort(files, (a,b) -> Long.compare(b.lastModified(), a.lastModified()));
        return files;
    }

    private void showLibrary() {
        destroySource();
        layout("library"); document = null; currentFile = null; currentDraft = null;
        LinearLayout top = bar();
        TextView name = label("Xave", 28); name.setTypeface(Typeface.DEFAULT_BOLD); name.setPadding(0, 0, 0, 0);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(icon("⋮", v -> popup(v, new String[]{"Manage multiple", "Theme"}, which -> {
            if (which == 0) manageMultiple(); else chooseTheme();
        })));
        LinearLayout field = bar();
        field.setBackground(outline(false)); field.setPadding(dp(16), dp(3), dp(3), dp(3));
        ((LinearLayout.LayoutParams)field.getLayoutParams()).setMargins(0, dp(10), 0, dp(4));
        EditText url = new EditText(this);
        url.setTextSize(17); url.setSingleLine(true); url.setHint("Paste an X post link");
        url.setTextColor(fg()); url.setHintTextColor(muted()); url.setBackground(null);
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        url.setImeOptions(EditorInfo.IME_ACTION_GO);
        url.setOnEditorActionListener((v, action, event) -> { openOnline(url.getText().toString()); return true; });
        field.addView(url, new LinearLayout.LayoutParams(0, -2, 1));
        field.addView(pill("Open", true, () -> openOnline(url.getText().toString())));
        File[] files = newestFirst(library());
        ScrollView scroll = new ScrollView(this);
        LinearLayout entries = new LinearLayout(this); entries.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(entries); screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        File[] unfinished = newestFirst(drafts());
        if (unfinished.length > 0) {
            section(entries, "Not saved yet · " + unfinished.length);
            for (File draft : unfinished) addLibraryEntry(entries, draft, true);
        }
        section(entries, "Saved · " + files.length);
        if (files.length == 0) {
            TextView empty = label("Your saved reading will appear here.", 17);
            empty.setTextColor(muted()); empty.setPadding(0, dp(8), 0, dp(8));
            entries.addView(empty);
        }
        for (File file : files) addLibraryEntry(entries, file, false);
    }

    private void addLibraryEntry(LinearLayout entries, File file, boolean draft) {
        entry(entries, file, draft).setOnClickListener(v -> { if (draft) openDraft(file); else openSaved(file); });
    }

    /** A library row: the first post or article title, with the author and post count under it. */
    private LinearLayout entry(LinearLayout entries, File file, boolean draft) {
        JSONObject summary;
        boolean repair = false;
        try {
            summary = new JSONObject(new String(Files.readAllBytes(new File(file + ".meta").toPath()), StandardCharsets.UTF_8));
            repair = !summary.has("detail") || summary.optString("title").trim().isEmpty();
        } catch (Exception e) { summary = new JSONObject(); repair = true; }
        String title = summary.optString("title", "Untitled");
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(12), 0, dp(12));
        LinearLayout text = new LinearLayout(this); text.setOrientation(LinearLayout.VERTICAL);
        TextView item = label(title, 18);
        item.setPadding(0, 0, 0, 0); item.setMaxLines(2); item.setEllipsize(TextUtils.TruncateAt.END);
        TextView detail = label(summary.optString("detail"), 14);
        detail.setPadding(0, dp(3), 0, 0); detail.setTextColor(muted());
        text.addView(item); text.addView(detail);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        entries.addView(row);
        divider(entries);
        if (repair || ReaderDocument.genericTitle(title)) {
            int token = generation;
            io.execute(() -> {
                try {
                    JSONObject doc = draft ? readDocument(file) : readSavedDocument(file);
                    JSONObject fresh = ReaderDocument.summary(doc);
                    try { writeSummary(file, doc); } catch (Exception ignored) { }
                    runOnUiThread(() -> {
                        if (token == generation && !isFinishing()) {
                            item.setText(fresh.optString("title"));
                            detail.setText(fresh.optString("detail"));
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> { if (token == generation) detail.setText("Could not read this item · tap to retry"); });
                }
            });
        }
        return row;
    }

    private void manageMultiple() {
        new AlertDialog.Builder(this).setTitle("Manage multiple")
            .setItems(new String[]{"Select captures to save", "Select items to delete"}, (dialog,which) -> showSelection(which == 1))
            .show();
    }

    /** Save mode lists only unsaved captures; delete mode lists everything. */
    private void showSelection(boolean delete) {
        layout(delete ? "delete" : "save"); document = null; currentFile = null; currentDraft = null;
        selected.clear();
        LinearLayout top = bar();
        top.addView(icon("‹", v -> showLibrary()));
        TextView heading = label(delete ? "Select to delete" : "Select to save", 19);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        TextView action = pill(delete ? "Delete" : "Save", true, () -> applySelection(delete));
        top.addView(action);
        divider(screen);
        ScrollView scroll = new ScrollView(this);
        LinearLayout entries = new LinearLayout(this); entries.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(entries); screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        File[] unfinished = newestFirst(drafts());
        File[] files = delete ? newestFirst(library()) : new File[0];
        if (unfinished.length > 0) {
            section(entries, "Not saved yet · " + unfinished.length);
            for (File draft : unfinished) selectable(entries, draft, true, action, delete);
        }
        if (files.length > 0) {
            section(entries, "Saved · " + files.length);
            for (File file : files) selectable(entries, file, false, action, delete);
        }
        if (unfinished.length + files.length == 0) {
            TextView empty = label(delete ? "Nothing to delete." : "Every capture is already saved.", 17);
            empty.setTextColor(muted()); empty.setPadding(0, dp(16), 0, dp(8));
            entries.addView(empty);
        }
        updateSelection(action, delete);
    }

    private void selectable(LinearLayout entries, File file, boolean draft, TextView action, boolean delete) {
        LinearLayout row = entry(entries, file, draft);
        CheckBox box = new CheckBox(this);
        box.setClickable(false); box.setButtonTintList(ColorStateList.valueOf(fg()));
        row.addView(box, 0);
        row.setOnClickListener(v -> {
            box.setChecked(!box.isChecked());
            if (box.isChecked()) selected.add(file); else selected.remove(file);
            updateSelection(action, delete);
        });
    }

    private void updateSelection(TextView action, boolean delete) {
        action.setText((delete ? "Delete" : "Save") + (selected.isEmpty() ? "" : " " + selected.size()));
        action.setEnabled(!selected.isEmpty()); action.setAlpha(selected.isEmpty() ? 0.35f : 1);
    }

    private void applySelection(boolean delete) {
        if (busy || selected.isEmpty()) return;
        List<File> targets = new ArrayList<>(selected);
        if (!delete) { runSelection(targets, false); return; }
        String items = targets.size() + (targets.size() == 1 ? " item" : " items");
        new AlertDialog.Builder(this).setMessage("Delete " + items + "? This cannot be undone.")
            .setNegativeButton("Keep", null)
            .setPositiveButton("Delete", (dialog,which) -> runSelection(targets, true)).show();
    }

    private void runSelection(List<File> targets, boolean delete) {
        if (busy) return;
        busy = true;
        int token = generation;
        io.execute(() -> {
            int failed = 0;
            for (File target : targets) {
                try {
                    if (delete) DocumentFiles.delete(target);
                    else DocumentFiles.save(target, library(), readDocument(target));
                } catch (Exception e) { failed++; }
            }
            int missed = failed, done = targets.size() - failed;
            runOnUiThread(() -> {
                if (token != generation) return;
                if (delete) {
                    SharedPreferences.Editor prefs = getPreferences(0).edit();
                    for (File target : targets) if (!target.exists()) prefs.remove("position:" + target.getName());
                    prefs.apply();
                }
                showLibrary();
                if (missed > 0) error(delete ? "Could not delete " + missed + " of " + targets.size() + " items."
                    : "Could not save " + missed + " of " + targets.size() + " captures. They are kept under Not saved yet.");
                else Toast.makeText(this, (delete ? "Deleted " : "Saved ") + done + (done == 1 ? " item" : " items"), Toast.LENGTH_SHORT).show();
            });
        });
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
        LinearLayout top = bar();
        top.addView(icon("‹", v -> showLibrary()));
        status = label("Loading…", 16); status.setTypeface(Typeface.DEFAULT_BOLD); status.setMaxLines(2);
        top.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(pill("Capture", true, this::capture));
        TextView tip = label("Scroll the thread and expand long posts first.", 14);
        tip.setTextColor(muted()); screen.addView(tip);
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
                if (view != browser) return;
                pageFailed = false;
                if ("online".equals(mode)) status.setText("Loading…");
            }
            @Override public void onPageFinished(WebView view, String loaded) {
                // A failed load still finishes, so keep its error message instead of polling for posts.
                if (!"online".equals(mode) || view != browser || pageFailed) return;
                view.evaluateJavascript(collector, null);
                CookieManager.getInstance().flush();
                pollStatus();
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
            browser.evaluateJavascript(collector, null);
            pollStatus();
        }
    }

    /** Shows how much of the page the collector has found, so you know when to stop scrolling. */
    private void pollStatus() {
        ui.removeCallbacks(poll);
        if (!"online".equals(mode) || browser == null) return;
        WebView page = browser;
        if (!busy && !pageFailed) page.evaluateJavascript("window.__xaveCollector ? window.__xaveCollector.summary() : null", result -> {
            if (page != browser || busy || pageFailed) return;
            try {
                JSONObject found = new JSONObject(result);
                int posts = found.getInt("posts");
                status.setText(found.getBoolean("article") ? "X Article loaded"
                    : posts > 0 ? posts + (posts == 1 ? " post loaded" : " posts loaded")
                    : found.getBoolean("status") ? "Loading posts…" : "Ready");
            } catch (Exception ignored) { }
        });
        ui.postDelayed(poll, 1000);
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
                if (doc.has("error")) { busy = false; error(doc.getString("error")); return; }
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
                            busy = false; error("Could not keep this capture. Check available storage and retry.");
                        } });
                    }
                });
            } catch (Exception e) { busy = false; error("Could not capture this page. Wait for it to load, then retry."); }
        });
    }

    /** Saves each image in the article's images folder; image blocks then refer to it by file name. */
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
        LinearLayout top = bar();
        top.addView(icon("‹", v -> showLibrary()));
        if (file == null) {
            TextView state = label("Preview · not saved", 14); state.setTextColor(muted());
            top.addView(state, new LinearLayout.LayoutParams(0, -2, 1));
            top.addView(pill("Save", true, this::saveDocument));
        } else top.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1));
        top.addView(icon("⋮", this::readerOptions));
        divider(screen);
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
        divider(screen);
        LinearLayout footer = bar(); footer.setPadding(0, dp(8), 0, dp(8));
        footer.addView(pill("Prev", false, () -> browser.pageUp(false)));
        readingProgress = label("0%", 16); readingProgress.setTypeface(Typeface.DEFAULT_BOLD);
        readingProgress.setGravity(Gravity.CENTER);
        footer.addView(readingProgress, new LinearLayout.LayoutParams(0, -2, 1));
        footer.addView(pill("Next", false, () -> browser.pageDown(false)));
        browser.loadDataWithBaseURL("https://offline.xave.invalid/", ReaderDocument.html(doc, dark, file == null, name -> "images/" + name), "text/html", "UTF-8", null);
    }

    private void updateReadingProgress() {
        if (!"reader".equals(mode) || browser == null || readingProgress == null) return;
        float range = browser.getContentHeight() * browser.getScale() - browser.getHeight();
        int percent = browser.getContentHeight() == 0 ? 0 : range <= dp(2) ? 100 : Math.max(0, Math.min(100, Math.round(browser.getScrollY() * 100 / range)));
        readingProgress.setText(percent + "%");
    }

    private void readerOptions(View anchor) {
        boolean draft = currentDraft != null;
        String[] items = draft ? new String[]{"Text size", "Theme", "Back to source", "Discard draft"}
            : new String[]{"Text size", "Theme", "Export", "Open on X", "Delete"};
        popup(anchor, items, which -> {
            if (which == 0) {
                LinearLayout sizes = new LinearLayout(this);
                sizes.setPadding(dp(20), dp(12), dp(20), 0);
                LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -2, 1);
                half.setMargins(dp(6), 0, dp(6), 0);
                sizes.addView(pill("A−", false, () -> changeZoom(-10)), half);
                sizes.addView(pill("A+", false, () -> changeZoom(10)), half);
                new AlertDialog.Builder(this).setTitle("Text size").setView(sizes).setPositiveButton("Done", null).show();
            } else if (which == 1) chooseTheme();
            else if (draft) { if (which == 2) openOnline(document.optString("source")); else deleteCurrent(); }
            else if (which == 2) exportDocument();
            else if (which == 3) openOnline(document.optString("source"));
            else deleteCurrent();
        });
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

    private void deleteCurrent() {
        boolean draft = currentDraft != null;
        File target = draft ? currentDraft : currentFile;
        if (target == null || busy) return;
        new AlertDialog.Builder(this).setMessage(draft ? "Discard this unfinished capture?" : "Delete this from your library? This cannot be undone.")
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
                    String html = ReaderDocument.html(doc, name -> {
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
        ui.removeCallbacks(poll);
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
