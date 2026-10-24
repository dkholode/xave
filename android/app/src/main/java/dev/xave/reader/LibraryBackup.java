package dev.xave.reader;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
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

    /** Writes each readable article with its images. Summaries are left out because read() rebuilds them. */
    static int write(File root, OutputStream out) throws Exception {
        ZipOutputStream zip = new ZipOutputStream(out);
        int written = 0;
        File[] articles = new File(root, "library").listFiles();
        if (articles != null) for (File article : articles) {
            if (!article.isFile() || !article.getName().endsWith(".json")) continue;
            byte[] bytes = Files.readAllBytes(article.toPath());
            if (parse(new String(bytes, StandardCharsets.UTF_8)) == null) continue;
            put(zip, "library/" + article.getName(), bytes);
            File images = DocumentFiles.images(article);
            File[] files = images.listFiles();
            if (files != null) for (File image : files) {
                if (image.isFile() && ReaderDocument.imageFile(image.getName())) put(zip, "images/" + images.getName() + "/" + image.getName(), Files.readAllBytes(image.toPath()));
            }
            written++;
        }
        zip.finish();
        return written;
    }

    /** Imports articles the library lacks, with their images. Returns the number of articles imported. */
    static int read(InputStream in, File root) throws Exception {
        File library = new File(root, "library");
        library.mkdirs();
        ZipInputStream zip = new ZipInputStream(in);
        // write() puts each article before its images, so an image is kept only for an article this restore added.
        Set<String> added = new HashSet<>();
        int imported = 0;
        long total = 0;
        ZipEntry entry = zip.getNextEntry();
        while (entry != null) {
            String name = entry.getName();
            Matcher article = ARTICLE.matcher(name);
            Matcher image = IMAGE.matcher(name);
            if (article.matches()) {
                byte[] bytes = ImageDownloads.readLimited(zip, ARTICLE_BYTES, Long.MAX_VALUE);
                total = count(total, bytes.length);
                String text = new String(bytes, StandardCharsets.UTF_8);
                JSONObject doc = parse(text);
                File file = new File(library, article.group(1) + ".json");
                // A backup can predate edits made since, so the save already on the device wins.
                if (doc != null && !file.exists()) {
                    DocumentFiles.writeAtomic(file, text);
                    DocumentFiles.writeSummary(file, doc);
                    added.add(article.group(1));
                    imported++;
                }
            } else if (image.matches() && added.contains(image.group(1))) {
                byte[] bytes = ImageDownloads.readLimited(zip, ImageDownloads.MAX_IMAGE_BYTES, Long.MAX_VALUE);
                total = count(total, bytes.length);
                File images = DocumentFiles.images(new File(library, image.group(1) + ".json"));
                File file = new File(images, image.group(2));
                images.mkdirs();
                Files.write(file.toPath(), bytes);
            }
            entry = zip.getNextEntry();
        }
        return imported;
    }

    private static JSONObject parse(String text) {
        try { return new JSONObject(text); }
        catch (JSONException notAnArticle) { return null; }
    }

    private static long count(long total, int bytes) throws Exception {
        if (total + bytes > TOTAL_BYTES) throw new Exception("Backup exceeds size limit");
        return total + bytes;
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }
}
