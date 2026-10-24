package dev.xave.reader;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
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
        long total = 0;
        File[] articles = new File(root, "library").listFiles();
        if (articles != null) for (File article : articles) {
            if (!article.isFile() || !ARTICLE.matcher("library/" + article.getName()).matches()) continue;
            byte[] bytes = Files.readAllBytes(article.toPath());
            if (bytes.length > ARTICLE_BYTES) throw new Exception("An article is too large to back up");
            if (parse(new String(bytes, StandardCharsets.UTF_8)) == null) continue;
            total = count(total, bytes.length, TOTAL_BYTES);
            put(zip, "library/" + article.getName(), bytes);
            File images = DocumentFiles.images(article);
            File[] files = images.listFiles();
            if (files != null) for (File image : files) {
                if (!image.isFile() || !ReaderDocument.imageFile(image.getName())) continue;
                byte[] picture = Files.readAllBytes(image.toPath());
                if (picture.length > ImageDownloads.MAX_IMAGE_BYTES) throw new Exception("An image is too large to back up");
                total = count(total, picture.length, TOTAL_BYTES);
                put(zip, "images/" + images.getName() + "/" + image.getName(), picture);
            }
            written++;
        }
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
            List<String> articles = new ArrayList<>();
            long total = 0;
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
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
                    if (!shallow(text) || parse(text) == null) continue;
                    File file = new File(new File(stage, "library"), article.group(1) + ".json");
                    file.getParentFile().mkdirs();
                    Files.write(file.toPath(), bytes);
                    if (!articles.contains(article.group(1))) articles.add(article.group(1));
                } else if (image.matches()) {
                    File file = new File(new File(new File(stage, "images"), image.group(1)), image.group(2));
                    file.getParentFile().mkdirs();
                    Files.write(file.toPath(), bytes);
                }
            }
            int imported = 0;
            for (String id : articles) {
                File file = new File(library, id + ".json");
                // A backup can predate edits made since, so the save already on the device wins.
                if (file.exists()) continue;
                File images = new File(new File(stage, "images"), id);
                if (images.isDirectory()) {
                    File target = DocumentFiles.images(file);
                    delete(target);
                    target.getParentFile().mkdirs();
                    Files.move(images.toPath(), target.toPath());
                }
                Files.move(new File(new File(stage, "library"), file.getName()).toPath(), file.toPath());
                DocumentFiles.writeSummary(file, parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)));
                imported++;
            }
            return imported;
        } finally {
            delete(stage);
        }
    }

    /** Android's JSON parser recurses once per nesting level, so a deeply nested article could overflow the stack. */
    private static boolean shallow(String text) {
        int depth = 0;
        boolean string = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (string) { if (c == '\\') i++; else if (c == '"') string = false; }
            else if (c == '"') string = true;
            else if ((c == '{' || c == '[') && ++depth > 64) return false;
            else if (c == '}' || c == ']') depth--;
        }
        return true;
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

    private static JSONObject parse(String text) {
        try { return new JSONObject(text); }
        catch (JSONException notAnArticle) { return null; }
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
