package dev.xave.reader;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.json.JSONException;
import org.json.JSONObject;

/** Backs the library up to one zip and restores it. The zip comes from outside the app, so it is untrusted. */
final class LibraryBackup {
    private static final int ARTICLE_BYTES = 8 * 1024 * 1024;
    private static final long TOTAL_BYTES = 1024L * 1024 * 1024;
    static final int MAX_ENTRIES = 50_000;
    // A capture nests about 5 levels deep; rendering and summaries recurse through every level.
    private static final int MAX_DEPTH = 32;
    // A capture holds far fewer values, and an article with more could exhaust memory while it parses.
    private static final int MAX_VALUES = 500_000;
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
            put(zip, "library/" + article.getName(), bytes, article.lastModified());
            File images = DocumentFiles.images(article);
            File[] files = images.listFiles();
            if (files != null) for (File image : files) {
                if (!image.isFile() || !ReaderDocument.imageFile(image.getName())) continue;
                entries = count(entries);
                byte[] picture = Files.readAllBytes(image.toPath());
                if (picture.length > ImageDownloads.MAX_IMAGE_BYTES) throw new Exception("An image is too large to back up");
                total = count(total, picture.length, TOTAL_BYTES);
                put(zip, "images/" + images.getName() + "/" + image.getName(), picture, 0);
            }
            written++;
        }
        put(zip, COMPLETE, new byte[0], 0);
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
            // The library shows each article's file time as its save date and sorts by it, so the backup's time is put back.
            Map<String, Long> articles = new LinkedHashMap<>();
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
                    articles.put(article.group(1), entry.getTime());
                } else if (image.matches()) {
                    File file = new File(new File(new File(stage, "images"), image.group(1)), image.group(2));
                    file.getParentFile().mkdirs();
                    Files.write(file.toPath(), bytes);
                }
            }
            if (!complete) throw new Exception("Backup is incomplete");
            List<File> added = new ArrayList<>();
            try {
                for (String id : articles.keySet()) {
                    File file = new File(library, id + ".json");
                    // A backup can predate edits made since, so the save already on the device wins.
                    if (file.exists()) continue;
                    added.add(file);
                    // The article goes in before its images, so the startup sweep for orphaned images never sees them alone.
                    Files.move(new File(new File(stage, "library"), file.getName()).toPath(), file.toPath());
                    if (articles.get(id) > 0) file.setLastModified(articles.get(id));
                    File images = new File(new File(stage, "images"), id);
                    if (images.isDirectory()) {
                        File target = DocumentFiles.images(file);
                        delete(target);
                        target.getParentFile().mkdirs();
                        Files.move(images.toPath(), target.toPath());
                    }
                    DocumentFiles.writeSummary(file, parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)));
                }
            } catch (Exception e) {
                // A failed restore adds nothing, so whatever this one already moved in comes back out.
                for (File file : added) {
                    try { DocumentFiles.delete(file); } catch (Exception ignored) { }
                    try { delete(DocumentFiles.images(file)); } catch (Exception ignored) { }
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
     * Android's lenient JSON parser recurses once per nesting level and builds every value, and a backup's article is
     * untrusted. So an article is checked for depth and value count before it is parsed, and one past either limit is
     * refused like any other unreadable article.
     */
    private static JSONObject parse(String text) {
        if (!withinLimits(text)) return null;
        try { return new JSONObject(text); } catch (JSONException | StackOverflowError notAnArticle) { return null; }
    }

    /**
     * Counts nesting and values in one pass, skipping string contents, so an article over a limit is never built. The
     * lenient parser also takes comments, single quotes, and ';' separators, which could hide values from this count,
     * so anything outside a string that saved JSON never contains is refused.
     */
    private static boolean withinLimits(String text) {
        int depth = 0;
        int values = 0;
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
            } else if (c == '"') inString = true;
            else if (c == '{' || c == '[') {
                if (++depth > MAX_DEPTH) return false;
                values++;
            } else if (c == '}' || c == ']') depth--;
            else if (c == ',') values++;
            else if (c != ':' && !Character.isWhitespace(c) && !isLiteral(c)) return false;
            if (values > MAX_VALUES) return false;
        }
        return true;
    }

    private static boolean isLiteral(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '.' || c == '-' || c == '+';
    }

    private static int count(int entries) throws Exception {
        if (entries + 1 > MAX_ENTRIES) throw new Exception("Backup has too many files");
        return entries + 1;
    }

    private static long count(long total, long bytes, long max) throws Exception {
        if (total + bytes > max) throw new Exception("Backup exceeds size limit");
        return total + bytes;
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes, long time) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        if (time > 0) entry.setTime(time);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }
}
