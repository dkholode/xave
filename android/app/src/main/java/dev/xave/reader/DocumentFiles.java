package dev.xave.reader;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import org.json.JSONObject;

final class DocumentFiles {
    static void writeAtomic(File file, String content) throws Exception {
        File temporary = File.createTempFile(file.getName() + ".", ".tmp", file.getParentFile());
        try {
            try (FileOutputStream stream = new FileOutputStream(temporary)) {
                stream.write(content.getBytes(StandardCharsets.UTF_8));
                stream.getFD().sync();
            }
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    static void writeSummary(File file, JSONObject document) throws Exception {
        writeAtomic(new File(file + ".meta"), ReaderDocument.summary(document).toString());
    }

    /** An article's images live in a folder named after it, beside the library folder. */
    static File images(File article) {
        File root = article.getParentFile().getParentFile();
        return new File(new File(root, "images"), article.getName().replaceFirst("\\.json$", ""));
    }

    static String dataUrl(File image) throws Exception {
        return "data:" + ReaderDocument.imageType(image.getName()) + ";base64,"
            + Base64.getEncoder().encodeToString(Files.readAllBytes(image.toPath()));
    }

    static void delete(File article) throws Exception {
        Files.deleteIfExists(article.toPath());
        Files.deleteIfExists(new File(article + ".meta").toPath());
        Files.deleteIfExists(new File(article + ".title-backup").toPath());
        removeUnusedImages(article.getParentFile().getParentFile(), article.getName());
    }

    /** A capture interrupted before its article was written leaves images that no article can reach. */
    static void removeOrphanImages(File root) throws Exception {
        File[] folders = new File(root, "images").listFiles();
        if (folders != null) for (File folder : folders) removeUnusedImages(root, folder.getName() + ".json");
    }

    private static void removeUnusedImages(File root, String name) throws Exception {
        File article = new File(new File(root, "library"), name);
        if (article.exists()) return;
        File images = images(article);
        File[] files = images.listFiles();
        if (files != null) for (File image : files) Files.deleteIfExists(image.toPath());
        Files.deleteIfExists(images.toPath());
    }
}
