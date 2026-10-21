package dev.xave.reader;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.json.JSONObject;

final class DocumentFiles {
    static JSONObject read(File file) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

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

    static void delete(File file) throws Exception {
        Files.deleteIfExists(file.toPath());
        Files.deleteIfExists(new File(file + ".meta").toPath());
        Files.deleteIfExists(new File(file + ".title-backup").toPath());
    }

    static File save(File draft, File library, JSONObject document) throws Exception {
        File saved = new File(library, draft.getName());
        writeAtomic(saved, document.toString());
        try {
            writeSummary(saved, document);
        } catch (Exception ignored) { }
        try {
            Files.deleteIfExists(draft.toPath());
            Files.deleteIfExists(new File(draft + ".meta").toPath());
        } catch (Exception ignored) { }
        return saved;
    }
}
