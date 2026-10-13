package app.photoprompt.offline;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

final class ModelStore {
    static final String BASE = "https://huggingface.co/ggml-org/SmolVLM-500M-Instruct-GGUF/resolve/72e9860/";
    static final String MODEL_NAME = "SmolVLM-500M-Instruct-Q8_0.gguf";
    static final String PROJECTOR_NAME = "mmproj-SmolVLM-500M-Instruct-Q8_0.gguf";
    static final String MODEL_HASH = "9d4612de6a42214499e301494a3ecc2be0abdd9de44e663bda63f1152fad1bf4";
    static final String PROJECTOR_HASH = "d1eb8b6b23979205fdf63703ed10f788131a3f812c7b1f72e0119d5d81295150";
    private final Context context;
    private final File directory;
    interface Progress { void update(long bytes); }
    ModelStore(Context context) {
        this.context = context;
        directory = new File(context.getNoBackupFilesDir(), "models");
    }
    File file(boolean projector) { return new File(directory, projector ? PROJECTOR_NAME : MODEL_NAME); }
    boolean present(boolean projector) {
        return file(projector).isFile() && file(projector).length() > 1_000_000;
    }
    boolean ready() { return present(false) && present(true); }
    void importFile(Uri uri, boolean projector, AtomicBoolean cancelled, Progress progress) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Не удалось создать папку модели.");
        AtomicFile target = new AtomicFile(file(projector));
        FileOutputStream out = null;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Не удалось открыть файл.");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            out = target.startWrite();
            byte[] buffer = new byte[262144];
            long count = 0, last = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (cancelled.get()) throw new IOException("CANCELLED");
                count += read;
                if (count > 700_000_000L) throw new IOException("Этот файл слишком большой. Выберите файл SmolVLM-500M из инструкции.");
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
                if (count - last >= 4_000_000L) { last = count; progress.update(count); }
            }
            if (cancelled.get()) throw new IOException("CANCELLED");
            String hash = hex(digest.digest());
            String expected = projector ? PROJECTOR_HASH : MODEL_HASH;
            if (!hash.equals(expected)) throw new IOException(
                "Файл не совпадает с нужной моделью. Проверьте, что выбрали " +
                (projector ? PROJECTOR_NAME : MODEL_NAME) + " и загрузка завершена.");
            target.finishWrite(out);
            out = null;
        } finally {
            if (out != null) target.failWrite(out);
        }
    }
    void delete() {
        new AtomicFile(file(false)).delete();
        new AtomicFile(file(true)).delete();
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
}
