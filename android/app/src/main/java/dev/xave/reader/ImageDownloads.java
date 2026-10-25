package dev.xave.reader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Downloads a capture's images into its images folder so it reads offline. */
final class ImageDownloads {
    private static final int MAX_IMAGES = 40;
    static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_TOTAL_BYTES = 16 * 1024 * 1024;
    private static final long TIME_LIMIT_SECONDS = 45;

    /**
     * Saves each image in the folder; image blocks then refer to it by file name. Images that fail stay without
     * a file and render as placeholders. cookies holds one entry per block, null where none may be sent.
     */
    static void save(JSONObject doc, List<String> cookies, String userAgent, File images,
                     BiConsumer<Integer, Integer> progress) {
        JSONArray blocks = doc.optJSONArray("blocks");
        ArrayList<Integer> pictures = new ArrayList<>();
        for (int i=0; i<blocks.length(); i++) if ("image".equals(blocks.optJSONObject(i).optString("kind"))) pictures.add(i);
        int count = Math.min(MAX_IMAGES, pictures.size());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIME_LIMIT_SECONDS);
        AtomicLong total = new AtomicLong();
        AtomicInteger done = new AtomicInteger();
        ArrayList<Callable<String>> downloads = new ArrayList<>();
        if (count > 0) images.mkdirs();
        for (int n=0; n<count; n++) {
            int index = n;
            JSONObject block = blocks.optJSONObject(pictures.get(n));
            String cookie = cookies.get(pictures.get(n));
            downloads.add(() -> {
                try { return download(block.optString("url"), cookie, userAgent, new File(images, String.valueOf(index)), total, deadline); }
                catch (Exception e) { return null; }
                finally { progress.accept(done.incrementAndGet(), count); }
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<String>> results = pool.invokeAll(downloads, TIME_LIMIT_SECONDS, TimeUnit.SECONDS);
            for (int n=0; n<count; n++) {
                try {
                    String name = results.get(n).get();
                    if (name != null) blocks.getJSONObject(pictures.get(n)).put("file", name);
                } catch (Exception ignored) { }
            }
        } catch (InterruptedException ignored) { }
        finally { pool.shutdownNow(); }
        // An inline image is now a file or a placeholder, so its payload need not stay in the article.
        for (int i : pictures) if (blocks.optJSONObject(i).optString("url").startsWith("data:")) blocks.optJSONObject(i).remove("url");
    }

    /** Returns the saved file name, which is the given base name plus the image type's extension. */
    private static String download(String link, String cookie, String userAgent, File base, AtomicLong total, long deadline) throws Exception {
        String type;
        byte[] bytes;
        Matcher inline = Pattern.compile("data:(image/[a-z]+);base64,([A-Za-z0-9+/=]+)").matcher(link);
        if (inline.matches()) {
            type = inline.group(1);
            bytes = Base64.getDecoder().decode(inline.group(2));
            if (bytes.length > MAX_IMAGE_BYTES) throw new Exception("Resource exceeds size limit");
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
                try (InputStream in = connection.getInputStream()) { bytes = readLimited(in, MAX_IMAGE_BYTES, deadline); }
            } finally { connection.disconnect(); }
        }
        String extension = ReaderDocument.imageExtension(type);
        if (extension == null) throw new Exception("Unsupported image format");
        if (total.addAndGet(bytes.length) > MAX_TOTAL_BYTES) throw new Exception("Image limit");
        File file = new File(base + "." + extension);
        Files.write(file.toPath(), bytes);
        return file.getName();
    }

    /**
     * Reads at most limit bytes. The deadline, a System.nanoTime value, bounds a server that trickles bytes,
     * which the per-read timeout alone does not.
     */
    static byte[] readLimited(InputStream in, int limit, long deadline) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (out.size() + n > limit) throw new Exception("Resource exceeds size limit");
            if (Thread.interrupted() || System.nanoTime() > deadline) throw new Exception("Download stopped");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
