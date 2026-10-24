package dev.xave.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
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
import android.os.SystemClock;
import android.print.PrintManager;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
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
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

public class MainActivity extends Activity {
    private LinearLayout screen;
    private WebView browser;
    private TextView status;
    private TextView tip;
    private static final String REVIEW_TIP = "Scroll the thread and expand long posts first. Replies aren't saved.";
    // Set while a link may still save without a tap; null once it saves, stops, or you take over.
    private AutoSave autoSave;
    // The saved item this capture replaces, when you went back to its own post to capture it again.
    private File replacing;
    private String collector;
    private String mode = "library";
    private JSONObject document;
    private File currentFile;
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
    // Set when dark mode switched during a capture; the new theme waits until you leave the loaded page.
    private boolean themeStale;
    private LinearLayout libraryEntries;
    // Saves still downloading, by post key. Static so a link opened again from a new screen or activity waits for its save.
    private static final Set<String> saving = Collections.newSetFromMap(new ConcurrentHashMap<>());
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
            collector = new String(ImageDownloads.readLimited(in, 200000, Long.MAX_VALUE), StandardCharsets.UTF_8);
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
            else if (state.getString("onlineUrl") != null) {
                // Only the link survives Android restarting Xave, so posts loaded by scrolling must load again.
                openOnline(state.getString("onlineUrl"), true);
                if ("online".equals(mode)) tip.setText("Xave restarted, so the page reloaded. Scroll to load the posts you want again, then tap Capture.");
            }
        }
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        if (!"system".equals(getPreferences(0).getString("theme", "system"))) return;
        boolean night = (config.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (night == dark) return;
        // Recreating would reload the loaded page and drop its scrolled-in posts, so a capture keeps its theme.
        if ("online".equals(mode)) themeStale = true; else recreate();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        rememberPosition();
        if ("reader".equals(mode) && currentFile != null) state.putString("savedItem", currentFile.getName());
        if ("online".equals(mode) && browser != null) state.putString("onlineUrl", browser.getUrl());
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
        String link = ReaderDocument.sharedLink(text);
        if (link != null) open(link);
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
        if (themeStale && !"online".equals(nextMode)) { themeStale = false; recreate(); }
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
        announceAsButton(b);
        return b;
    }

    private TextView icon(String glyph, String description, View.OnClickListener action) {
        TextView b = label(glyph, 30);
        b.setGravity(Gravity.CENTER); b.setMinWidth(dp(48)); b.setMinHeight(dp(48));
        b.setContentDescription(description);
        b.setOnClickListener(action);
        announceAsButton(b);
        return b;
    }

    /** Pills and icons are styled TextViews, so TalkBack would not otherwise say they are buttons. */
    private static void announceAsButton(View view) {
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
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
        layout("library"); document = null; currentFile = null;
        LinearLayout top = bar();
        TextView name = label("Xave", 28); name.setTypeface(Typeface.DEFAULT_BOLD); name.setPadding(0, 0, 0, 0);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(icon("⋮", "More options", v -> popup(v, new String[]{"Delete", "Saving", "Theme"}, which -> {
            if (which == 0) showSelection(); else if (which == 1) chooseSaving(); else chooseTheme();
        })));
        LinearLayout field = bar();
        field.setBackground(outline(false)); field.setPadding(dp(16), dp(3), dp(3), dp(3));
        ((LinearLayout.LayoutParams)field.getLayoutParams()).setMargins(0, dp(10), 0, dp(4));
        EditText url = new EditText(this);
        url.setTextSize(17); url.setSingleLine(true); url.setHint("Paste an X post link");
        url.setTextColor(fg()); url.setHintTextColor(muted()); url.setBackground(null);
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        url.setImeOptions(EditorInfo.IME_ACTION_GO);
        url.setOnEditorActionListener((v, action, event) -> { open(url.getText().toString()); return true; });
        field.addView(url, new LinearLayout.LayoutParams(0, -2, 1));
        field.addView(pill("Open", true, () -> open(url.getText().toString())));
        ScrollView scroll = new ScrollView(this);
        libraryEntries = new LinearLayout(this); libraryEntries.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(libraryEntries); screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        fillLibrary();
    }

    /** Rebuilds only the list, so a save finishing in the background shows up without clearing a link you are typing. */
    private void fillLibrary() {
        libraryEntries.removeAllViews();
        File[] files = newestFirst(library());
        section(libraryEntries, "Saved · " + files.length);
        if (files.length == 0) {
            TextView empty = label("Your saved reading will appear here.", 17);
            empty.setTextColor(muted()); empty.setPadding(0, dp(8), 0, dp(8));
            libraryEntries.addView(empty);
        }
        for (File file : files) entry(libraryEntries, file).setOnClickListener(v -> openSaved(file));
    }

    /** A library row: the first post or article title, with the author and post count under it. */
    private LinearLayout entry(LinearLayout entries, File file) {
        JSONObject summary;
        boolean repair = false;
        try {
            summary = new JSONObject(new String(Files.readAllBytes(new File(file + ".meta").toPath()), StandardCharsets.UTF_8));
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
        if (repair) {
            int token = generation;
            io.execute(() -> {
                try {
                    JSONObject doc = readSavedDocument(file);
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

    private void showSelection() {
        layout("delete"); document = null; currentFile = null;
        selected.clear();
        LinearLayout top = bar();
        top.addView(icon("‹", "Back to library", v -> showLibrary()));
        TextView heading = label("Select to delete", 19);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        TextView action = pill("Delete", true, this::applySelection);
        top.addView(action);
        divider(screen);
        ScrollView scroll = new ScrollView(this);
        LinearLayout entries = new LinearLayout(this); entries.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(entries); screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        File[] files = newestFirst(library());
        if (files.length > 0) section(entries, "Saved · " + files.length);
        for (File file : files) selectable(entries, file, action);
        if (files.length == 0) {
            TextView empty = label("Nothing to delete.", 17);
            empty.setTextColor(muted()); empty.setPadding(0, dp(16), 0, dp(8));
            entries.addView(empty);
        }
        updateSelection(action);
    }

    private void selectable(LinearLayout entries, File file, TextView action) {
        LinearLayout row = entry(entries, file);
        CheckBox box = new CheckBox(this);
        box.setClickable(false); box.setButtonTintList(ColorStateList.valueOf(fg()));
        row.addView(box, 0);
        row.setOnClickListener(v -> {
            box.setChecked(!box.isChecked());
            if (box.isChecked()) selected.add(file); else selected.remove(file);
            updateSelection(action);
        });
    }

    private void updateSelection(TextView action) {
        action.setText("Delete" + (selected.isEmpty() ? "" : " " + selected.size()));
        action.setEnabled(!selected.isEmpty()); action.setAlpha(selected.isEmpty() ? 0.35f : 1);
    }

    private void applySelection() {
        if (busy || selected.isEmpty()) return;
        List<File> targets = new ArrayList<>(selected);
        String items = targets.size() + (targets.size() == 1 ? " item" : " items");
        new AlertDialog.Builder(this).setMessage("Delete " + items + "? This cannot be undone.")
            .setNegativeButton("Keep", null)
            .setPositiveButton("Delete", (dialog,which) -> deleteSelection(targets)).show();
    }

    private void deleteSelection(List<File> targets) {
        if (busy) return;
        busy = true;
        int token = generation;
        io.execute(() -> {
            int failed = 0;
            for (File target : targets) {
                try { DocumentFiles.delete(target); } catch (Exception e) { failed++; }
            }
            int missed = failed, done = targets.size() - failed;
            runOnUiThread(() -> {
                if (token != generation) return;
                SharedPreferences.Editor prefs = getPreferences(0).edit();
                for (File target : targets) if (!target.exists()) prefs.remove("position:" + target.getName());
                prefs.apply();
                showLibrary();
                if (missed > 0) error("Could not delete " + missed + " of " + targets.size() + " items.");
                else Toast.makeText(this, "Deleted " + done + (done == 1 ? " item" : " items"), Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void openSaved(File file) {
        if (busy) return;
        busy = true;
        load(file);
    }

    private void load(File file) {
        int token = generation;
        io.execute(() -> {
            try {
                JSONObject doc = readSavedDocument(file);
                runOnUiThread(() -> { if (token == generation && !isFinishing()) showReader(doc, file, false); });
            } catch (Exception e) {
                runOnUiThread(() -> { if (token == generation) { busy = false; error("Could not open this saved item."); } });
            }
        });
    }

    private JSONObject readSavedDocument(File file) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    private void writeSummary(File file, JSONObject doc) throws Exception {
        DocumentFiles.writeSummary(file, doc);
    }

    private void writeAtomic(File file, String text) throws Exception {
        DocumentFiles.writeAtomic(file, text);
    }

    /** A link you paste or share opens its saved copy when there is one, so the library keeps each post once. */
    private void open(String input) {
        String url = input.trim();
        if (!url.contains("://")) url = "https://" + url;
        String key = ReaderDocument.capturable(url) ? ReaderDocument.postKey(url) : null;
        if (key == null) { openOnline(url); return; }
        if (saving.contains(key)) {
            Toast.makeText(this, "Still saving this post. It appears in your library when it's done.", Toast.LENGTH_LONG).show();
            return;
        }
        // Taking the link ends this screen: its automatic save stops, and a capture finishing during the lookup saves in the background.
        autoSave = null;
        int token = ++generation;
        String link = url;
        io.execute(() -> {
            File saved = findSaved(key);
            runOnUiThread(() -> {
                if (token != generation || isFinishing()) return;
                if (saved == null) { openOnline(link); return; }
                load(saved);
                Toast.makeText(this, "Already saved. To capture it again, use ⋮ > Open on X.", Toast.LENGTH_LONG).show();
            });
        });
    }

    /** The newest saved copy of a post or Article, matched by the source link in each library row's summary. */
    private File findSaved(String key) {
        for (File file : newestFirst(library())) {
            try {
                File meta = new File(file + ".meta");
                JSONObject summary = meta.isFile() ? new JSONObject(new String(Files.readAllBytes(meta.toPath()), StandardCharsets.UTF_8)) : null;
                String source;
                if (summary != null && summary.has("source")) source = summary.getString("source");
                else {
                    // Summaries written before they kept the source are rebuilt once from the saved article.
                    JSONObject doc = readSavedDocument(file);
                    source = doc.optString("source");
                    try { writeSummary(file, doc); } catch (Exception ignored) { }
                }
                if (key.equals(ReaderDocument.postKey(source))) return file;
            } catch (Exception ignored) { }
        }
        return null;
    }

    private void openOnline(String input) { openOnline(input, false); }

    /** review opens the page without saving it on load, for links you only meant to look at. */
    private void openOnline(String input, boolean review) {
        String url = input.trim();
        if (!url.contains("://")) url = "https://" + url;
        if (!ReaderDocument.capturable(url)) {
            error("Paste a link to an X post or Article, such as https://x.com/…/status/…"); return;
        }
        File replaces = "reader".equals(mode) && document != null && url.equals(document.optString("source")) ? currentFile : null;
        WebView resumed = sourceBrowser != null && url.equals(sourceUrl) ? sourceBrowser : null;
        if (resumed != null) sourceBrowser = null;
        else destroySource();
        layout("online"); document = null; currentFile = null; replacing = replaces;
        // A recapture is for loading more of the page first, so only a new link saves without a tap.
        autoSave = replaces == null && !review && !getPreferences(0).getBoolean("review", false) ? new AutoSave(SystemClock.uptimeMillis()) : null;
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(screen.getWindowToken(), 0);
        LinearLayout top = bar();
        top.addView(icon("‹", "Back to library", v -> showLibrary()));
        status = label("Loading…", 16); status.setTypeface(Typeface.DEFAULT_BOLD); status.setMaxLines(2);
        top.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(icon("↻", "Reload page", v -> { if (browser != null) { pageFailed = false; browser.reload(); } }));
        top.addView(pill("Capture", true, this::capture));
        tip = label(autoSave != null ? "Saves once the page loads. Touch the page to review it first." : REVIEW_TIP, 14);
        tip.setTextColor(muted()); screen.addView(tip);
        browser = resumed == null ? new WebView(this) : resumed;
        syncTimers();
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
                if (!request.isForMainFrame()) return;
                pageFailed = true; status.setText("Page could not load. Tap ↻ to retry.");
                // You may be waiting on the save elsewhere, so a failed load gets a dialog, not just the status line.
                if (view == browser && autoSave != null) { stopAutoSave(REVIEW_TIP); error("Page could not load. Check Wi-Fi and the link, then tap ↻ to reload."); }
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
        // Injected on every poll so the count follows you to another post; on the same post the collector keeps its posts.
        if (!busy && !pageFailed) page.evaluateJavascript(collector + "\nwindow.__xaveCollector.summary()", result -> {
            if (page != browser || busy || pageFailed) return;
            try {
                JSONObject found = new JSONObject(result);
                int posts = found.getInt("posts");
                status.setText(found.getBoolean("article") ? "X Article loaded"
                    : posts > 0 ? posts + (posts == 1 ? " post loaded" : " posts loaded")
                    : found.getBoolean("status") ? "Loading posts…" : "Ready");
                if (autoSave != null) {
                    AutoSave.Step step = autoSave.next(found, SystemClock.uptimeMillis());
                    if (step == AutoSave.Step.THREAD) stopAutoSave("This is a thread. Scroll to load the posts you want, then tap Capture. Replies aren't saved.");
                    else if (step == AutoSave.Step.SAVE) { stopAutoSave(REVIEW_TIP); capture(); }
                }
            } catch (Exception ignored) { }
        });
        ui.postDelayed(poll, 1000);
    }

    private void stopAutoSave(String message) {
        autoSave = null;
        tip.setText(message);
    }

    private void capture() {
        if (busy || browser == null) return;
        busy = true;
        int token = generation;
        File replaces = replacing;
        status.setText("Capturing loaded content…");
        // evaluateJavascript returns data only; websites cannot call native app methods.
        browser.evaluateJavascript(collector + "\nwindow.__xaveCollector.capture();", encoded -> {
            if (token != generation) return;
            try {
                Object decoded = new JSONTokener(encoded).nextValue();
                JSONObject doc = new JSONObject((String)decoded);
                if (doc.has("error")) { busy = false; error(doc.getString("error")); return; }
                doc.put("title", ReaderDocument.savedTitle(doc));
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
                if (replaces == null) { save(doc, cookies, userAgent, null, token); return; }
                io.execute(() -> {
                    JSONObject old;
                    try { old = readSavedDocument(replaces); } catch (Exception e) { old = null; }
                    JSONObject saved = old;
                    runOnUiThread(() -> {
                        if (token != generation) return;
                        // Tapping through to another post on X and capturing it saves that post alongside, never over, this one.
                        if (saved == null || !Objects.equals(ReaderDocument.postKey(saved.optString("source")), ReaderDocument.postKey(source))) {
                            save(doc, cookies, userAgent, null, token);
                        } else if (ReaderDocument.postCount(doc) >= ReaderDocument.postCount(saved)
                                && ReaderDocument.imageCount(doc) >= ReaderDocument.imageCount(saved)) {
                            save(doc, cookies, userAgent, replaces, token);
                        } else {
                            new AlertDialog.Builder(this)
                                .setMessage("Replace your saved copy? It has " + size(saved) + ". This capture has " + size(doc) + ".")
                                .setNegativeButton("Keep both", (d,w) -> save(doc, cookies, userAgent, null, token))
                                .setPositiveButton("Replace", (d,w) -> save(doc, cookies, userAgent, replaces, token))
                                .setOnCancelListener(d -> busy = false).show();
                        }
                    });
                });
            } catch (Exception e) { busy = false; error("Could not capture this page. Wait for it to load, then retry."); }
        });
    }

    private static String size(JSONObject doc) {
        int posts = ReaderDocument.postCount(doc), images = ReaderDocument.imageCount(doc);
        String pictures = images + (images == 1 ? " image" : " images");
        return posts == 0 ? pictures : posts + (posts == 1 ? " post" : " posts") + " and " + pictures;
    }

    /** Downloads the images and writes the capture. It finishes even if you leave the screen, so no save is lost silently. */
    private void save(JSONObject doc, List<String> cookies, String userAgent, File replaces, int token) {
        if (token != generation) return;
        status.setText("Downloading images for offline reading…");
        Context app = getApplicationContext();
        String key = ReaderDocument.postKey(doc.optString("source"));
        if (key != null) saving.add(key);
        captures.execute(() -> {
            File file = new File(library(), UUID.randomUUID() + ".json");
            ImageDownloads.save(doc, cookies, userAgent, DocumentFiles.images(file),
                (finished, count) -> runOnUiThread(() -> {
                    if (token == generation) status.setText("Downloading images for offline reading · " + finished + "/" + count);
                }));
            try {
                writeAtomic(file, doc.toString());
                try { writeSummary(file, doc); } catch (Exception ignored) { }
                if (replaces != null) {
                    try { DocumentFiles.delete(replaces); } catch (Exception ignored) { }
                    getPreferences(0).edit().remove("position:" + replaces.getName()).apply();
                }
                runOnUiThread(() -> {
                    if (token == generation && !isFinishing()) { showReader(doc, file, true); return; }
                    // You left the capture screen, so confirm the save wherever you are now.
                    Toast.makeText(app, ReaderDocument.savedMessage(doc), Toast.LENGTH_SHORT).show();
                    if ("library".equals(mode) && !isDestroyed()) fillLibrary();
                });
            } catch (Exception e) {
                try { DocumentFiles.delete(file); } catch (Exception ignored) { }
                runOnUiThread(() -> {
                    if (token == generation) { busy = false; error("Could not save this capture. Check available storage and retry."); }
                    else Toast.makeText(app, "Could not save a capture. Check available storage and retry.", Toast.LENGTH_LONG).show();
                });
            } finally {
                if (key != null) saving.remove(key);
            }
        });
    }

    /** Without this, a crashed or memory-killed page renderer would take the whole app down with it. */
    private boolean renderGone(WebView view) {
        if (view == sourceBrowser) { destroySource(); syncTimers(); return true; }
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

    private void showReader(JSONObject doc, File file, boolean captured) {
        if (captured && "online".equals(mode) && browser != null) {
            destroySource();
            // A page that navigated while images downloaded no longer shows this capture's post.
            if (Objects.equals(ReaderDocument.postKey(doc.optString("source")), ReaderDocument.postKey(browser.getUrl()))) {
                sourceBrowser = browser; sourceUrl = doc.optString("source");
                ((android.view.ViewGroup)browser.getParent()).removeView(browser);
                sourceBrowser.onPause(); browser = null;
            }
        }
        layout("reader"); document = doc; currentFile = file;
        LinearLayout top = bar();
        top.addView(icon("‹", "Back to library", v -> showLibrary()));
        // Back returns to the loaded page, which a single post saved on load may need for the rest of its thread.
        String hint = sourceBrowser == null ? "" : ReaderDocument.postCount(doc) == 1
            ? "Part of a thread? Press Back to load more." : "Missing something? Press Back to capture again.";
        // The save confirmation sits in the bar, clear of the text. It goes on the first scroll, or after 3 seconds without a hint.
        TextView saved = label(captured ? ReaderDocument.savedMessage(doc) + (hint.isEmpty() ? "" : "\n" + hint) : "", 14);
        saved.setTextColor(muted()); saved.setMaxLines(2); saved.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(saved, new LinearLayout.LayoutParams(0, -2, 1));
        if (captured && hint.isEmpty()) saved.postDelayed(() -> saved.setText(""), 3000);
        top.addView(icon("⋮", "More options", this::readerOptions));
        divider(screen);
        browser = new WebView(this);
        syncTimers();
        browser.setBackgroundColor(bg());
        WebSettings s = browser.getSettings();
        s.setJavaScriptEnabled(false); s.setAllowFileAccess(false); s.setAllowContentAccess(false);
        s.setBlockNetworkLoads(true); s.setTextZoom(zoom);
        File images = DocumentFiles.images(file);
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
                if (!ReaderDocument.webUrl(link)) return true;
                // Xave can only capture X, so links elsewhere go to the device's browser.
                boolean x = link.startsWith("https://") && ReaderDocument.xHost(request.getUrl().getHost());
                new AlertDialog.Builder(MainActivity.this)
                    .setMessage((x ? "Open this link online?\n" : "Open this link in your browser?\n") + link).setNegativeButton("Cancel", null)
                    .setPositiveButton("Open", (d,w) -> { if (x) openOnline(link, true); else openInBrowser(link); }).show();
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (view != browser) return;
                float ratio = getPreferences(0).getFloat("position:" + file.getName(), 0);
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
        browser.setOnScrollChangeListener((view,x,y,oldX,oldY) -> {
            if (y != oldY) saved.setText("");
            updateReadingProgress();
        });
        screen.addView(browser, new LinearLayout.LayoutParams(-1, 0, 1));
        divider(screen);
        LinearLayout footer = bar(); footer.setPadding(0, dp(8), 0, dp(8));
        TextView prev = pill("Prev", false, () -> browser.pageUp(false));
        prev.setContentDescription("Previous page");
        footer.addView(prev);
        readingProgress = label("0%", 16); readingProgress.setTypeface(Typeface.DEFAULT_BOLD);
        readingProgress.setGravity(Gravity.CENTER);
        footer.addView(readingProgress, new LinearLayout.LayoutParams(0, -2, 1));
        TextView next = pill("Next", false, () -> browser.pageDown(false));
        next.setContentDescription("Next page");
        footer.addView(next);
        browser.loadDataWithBaseURL("https://offline.xave.invalid/", ReaderDocument.html(doc, dark, name -> "images/" + name), "text/html", "UTF-8", null);
    }

    /**
     * Timers are global to every WebView. They stay paused while the reader shows a fresh capture, so its
     * loaded X page, kept for Back, runs no scripts behind it.
     */
    private void syncTimers() {
        if (browser == null) return;
        if (foreground && sourceBrowser == null) browser.resumeTimers(); else browser.pauseTimers();
    }

    private void openInBrowser(String link) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link))); }
        catch (android.content.ActivityNotFoundException e) { error("No browser is installed to open this link."); }
    }

    private void updateReadingProgress() {
        if (!"reader".equals(mode) || browser == null || readingProgress == null) return;
        float range = browser.getContentHeight() * browser.getScale() - browser.getHeight();
        int percent = browser.getContentHeight() == 0 ? 0 : range <= dp(2) ? 100 : Math.max(0, Math.min(100, Math.round(browser.getScrollY() * 100 / range)));
        readingProgress.setText(percent + "%");
    }

    private void readerOptions(View anchor) {
        String[] items = {"Text size", "Theme", "Export", "Open on X", "Delete"};
        popup(anchor, items, which -> {
            if (which == 0) {
                LinearLayout sizes = new LinearLayout(this);
                sizes.setPadding(dp(20), dp(12), dp(20), 0);
                LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -2, 1);
                half.setMargins(dp(6), 0, dp(6), 0);
                TextView smaller = pill("A−", false, () -> changeZoom(-10));
                TextView larger = pill("A+", false, () -> changeZoom(10));
                smaller.setContentDescription("Smaller text"); larger.setContentDescription("Larger text");
                sizes.addView(smaller, half); sizes.addView(larger, half);
                new AlertDialog.Builder(this).setTitle("Text size").setView(sizes).setPositiveButton("Done", null).show();
            } else if (which == 1) chooseTheme();
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

    private void chooseSaving() {
        boolean review = getPreferences(0).getBoolean("review", false);
        new AlertDialog.Builder(this).setTitle("Saving")
            .setSingleChoiceItems(new String[]{"Save when the page loads", "Review the page, then tap Capture"}, review ? 1 : 0, (dialog,which) -> {
                dialog.dismiss();
                getPreferences(0).edit().putBoolean("review", which == 1).apply();
            }).show();
    }

    private void deleteCurrent() {
        File target = currentFile;
        if (target == null || busy) return;
        new AlertDialog.Builder(this).setMessage("Delete this from your library? This cannot be undone.")
            .setNegativeButton("Keep", null)
            .setPositiveButton("Delete", (dialog,which) -> {
                busy = true; int token = generation;
                io.execute(() -> {
                    try {
                        DocumentFiles.delete(target);
                        runOnUiThread(() -> { if (token == generation) {
                            showLibrary();
                            // Cleared after leaving the reader, which records the position on exit.
                            getPreferences(0).edit().remove("position:" + target.getName()).apply();
                        } });
                    } catch (Exception e) { runOnUiThread(() -> { if (token == generation) { busy = false; error("Could not delete this article."); } }); }
                });
            }).show();
    }

    private void changeZoom(int delta) {
        zoom = Math.max(70, Math.min(190, zoom + delta));
        getPreferences(0).edit().putInt("zoom", zoom).apply();
        browser.getSettings().setTextZoom(zoom);
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

    private File library() { File dir = new File(getFilesDir(), "library"); dir.mkdirs(); return dir; }

    private void rememberPosition() {
        if (!"reader".equals(mode) || browser == null || currentFile == null) return;
        float range = browser.getContentHeight() * browser.getScale() - browser.getHeight();
        float ratio = range <= dp(2) ? 0 : Math.max(0, Math.min(1, browser.getScrollY() / range));
        getPreferences(0).edit().putFloat("position:" + currentFile.getName(), ratio).apply();
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
        if (browser != null) { browser.onResume(); syncTimers(); }
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        // Touching the capture screen means you want to look first, such as to sign in or scroll.
        if ("online".equals(mode) && autoSave != null && event.getActionMasked() == MotionEvent.ACTION_DOWN) stopAutoSave(REVIEW_TIP);
        return super.dispatchTouchEvent(event);
    }
    @Override public void onBackPressed() {
        // Only the capture just made keeps its loaded page, so Back from it returns there.
        if ("reader".equals(mode) && sourceBrowser != null) openOnline(document.optString("source"));
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
}
