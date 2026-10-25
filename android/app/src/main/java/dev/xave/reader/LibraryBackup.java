package dev.xave.reader;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Backs the library up to one zip and restores it. The zip comes from outside the app, so it is untrusted. */
final class LibraryBackup {
    private static final int ARTICLE_BYTES = 8 * 1024 * 1024;
    private static final long TOTAL_BYTES = 1024L * 1024 * 1024;
    static final int MAX_ENTRIES = 50_000;
    // A capture nests about 5 levels deep; rendering and summaries recurse through every level.
    private static final int MAX_DEPTH = 32;
    // ZipInputStream reads a backup cut off between entries as one that ended there, so write() puts this last and read() requires it.
    static final String COMPLETE = "backup-complete";
    // Whole-name matching is the zip-slip guard: a name with ".." or a leading "/" fails the match and is never written.
    private static final Pattern ARTICLE = Pattern.compile("library/([0-9a-f-]{36})\\.json");
    private static final Pattern IMAGE = Pattern.compile("images/([0-9a-f-]{36})/(\\d{1,3}\\.(png|jpg|gif|webp))");

    /**
     * Writes each article read() would accept, with its images. Summaries are left out because read() rebuilds them.
     * Throws when the library exceeds read()'s limits, so a backup that reports success can always be restored.
     */
    static int write(File root, OutputStream out) throws Exception {
        ZipOutputStream zip = new ZipOutputStream(out);
        int written = 0;
        int entries = 1;
        long total = 0;
        File[] articles = new File(root, "library").listFiles();
        if (articles != null) for (File article : articles) {
            if (!article.isFile() || !ARTICLE.matcher("library/" + article.getName()).matches()) continue;
            byte[] bytes = Files.readAllBytes(article.toPath());
            if (bytes.length > ARTICLE_BYTES) throw new Exception("An article is too large to back up");
            if (parse(new String(bytes, StandardCharsets.UTF_8)) == null) continue;
            total = count(total, bytes.length, TOTAL_BYTES);
            entries = count(entries);
            put(zip, "library/" + article.getName(), bytes);
            File images = DocumentFiles.images(article);
            File[] files = images.listFiles();
            if (files != null) for (File image : files) {
                if (!image.isFile() || !ReaderDocument.imageFile(image.getName())) continue;
                entries = count(entries);
                byte[] picture = Files.readAllBytes(image.toPath());
                if (picture.length > ImageDownloads.MAX_IMAGE_BYTES) throw new Exception("An image is too large to back up");
                total = count(total, picture.length, TOTAL_BYTES);
                put(zip, "images/" + images.getName() + "/" + image.getName(), picture);
            }
            written++;
        }
        put(zip, COMPLETE, new byte[0]);
        zip.finish();
        return written;
    }

    /** Imports articles the library lacks, with their images. Returns the number of articles imported. */
    static int read(InputStream in, File root) throws Exception {
        return read(in, root, TOTAL_BYTES);
    }

    /**
     * Unpacks the whole zip into a staging folder first, so a backup that fails partway changes nothing, then moves
     * each new article in with its images. maxBytes caps the expanded size of every entry, skipped ones included.
     */
    static int read(InputStream in, File root, long maxBytes) throws Exception {
        File library = new File(root, "library");
        library.mkdirs();
        File stage = new File(root, "restore");
        delete(stage);
        try {
            ZipInputStream zip = new ZipInputStream(in);
            Set<String> articles = new LinkedHashSet<>();
            long total = 0;
            int entries = 0;
            boolean complete = false;
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries = count(entries);
                complete = COMPLETE.equals(entry.getName());
                Matcher article = ARTICLE.matcher(entry.getName());
                Matcher image = IMAGE.matcher(entry.getName());
                if (!article.matches() && !image.matches()) {
                    total = count(total, drain(zip, maxBytes - total), maxBytes);
                    continue;
                }
                byte[] bytes = ImageDownloads.readLimited(zip, article.matches() ? ARTICLE_BYTES : ImageDownloads.MAX_IMAGE_BYTES, Long.MAX_VALUE);
                total = count(total, bytes.length, maxBytes);
                if (article.matches()) {
                    String text = new String(bytes, StandardCharsets.UTF_8);
                    if (parse(text) == null) continue;
                    File file = new File(new File(stage, "library"), article.group(1) + ".json");
                    file.getParentFile().mkdirs();
                    Files.write(file.toPath(), bytes);
                    articles.add(article.group(1));
                } else if (image.matches()) {
                    File file = new File(new File(new File(stage, "images"), image.group(1)), image.group(2));
                    file.getParentFile().mkdirs();
                    Files.write(file.toPath(), bytes);
                }
            }
            if (!complete) throw new Exception("Backup is incomplete");
            List<File> added = new ArrayList<>();
            try {
                for (String id : articles) {
                    File file = new File(library, id + ".json");
                    // A backup can predate edits made since, so the save already on the device wins.
                    if (file.exists()) continue;
                    added.add(file);
                    File images = new File(new File(stage, "images"), id);
                    if (images.isDirectory()) {
                        File target = DocumentFiles.images(file);
                        delete(target);
                        target.getParentFile().mkdirs();
                        Files.move(images.toPath(), target.toPath());
                    }
                    Files.move(new File(new File(stage, "library"), file.getName()).toPath(), file.toPath());
                    DocumentFiles.writeSummary(file, parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)));
                }
            } catch (Exception e) {
                // A failed restore adds nothing, so whatever this one already moved in comes back out.
                for (File file : added) {
                    try { DocumentFiles.delete(file); } catch (Exception ignored) { }
                    delete(DocumentFiles.images(file));
                }
                throw e;
            }
            return added.size();
        } finally {
            delete(stage);
        }
    }

    /** Reads past an entry the restore doesn't use, so a zip bomb hidden in one still hits the size limit. */
    private static long drain(InputStream in, long limit) throws Exception {
        byte[] buffer = new byte[8192];
        long size = 0;
        for (int n = in.read(buffer); n != -1; n = in.read(buffer)) {
            if ((size += n) > limit) throw new Exception("Backup exceeds size limit");
        }
        return size;
    }

    private static void delete(File file) throws Exception {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        Files.deleteIfExists(file.toPath());
    }

    /**
     * Android's lenient JSON parser recurses once per nesting level, and a backup's article is untrusted, so one
     * nested deeply enough to overflow the stack is refused like any other unreadable article.
     */
    private static JSONObject parse(String text) {
        try {
            JSONObject document = new JSONObject(text);
            return shallow(document) ? document : null;
        } catch (JSONException | StackOverflowError notAnArticle) { return null; }
    }

    /** Walks one nesting level at a time instead of recursing, so the check itself can't overflow the stack. */
    private static boolean shallow(JSONObject document) {
        List<Object> level = Collections.singletonList(document);
        for (int depth = 0; !level.isEmpty(); depth++) {
            if (depth > MAX_DEPTH) return false;
            List<Object> next = new ArrayList<>();
            for (Object value : level) {
                if (value instanceof JSONObject) {
                    JSONObject object = (JSONObject) value;
                    for (Iterator<String> keys = object.keys(); keys.hasNext(); ) next.add(object.opt(keys.next()));
                } else if (value instanceof JSONArray) {
                    JSONArray array = (JSONArray) value;
                    for (int i = 0; i < array.length(); i++) next.add(array.opt(i));
                }
            }
            level = next;
        }
        return true;
    }

    private static int count(int entries) throws Exception {
        if (entries + 1 > MAX_ENTRIES) throw new Exception("Backup has too many files");
        return entries + 1;
    }

    private static long count(long total, long bytes, long max) throws Exception {
        if (total + bytes > max) throw new Exception("Backup exceeds size limit");
        return total + bytes;
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }
}
