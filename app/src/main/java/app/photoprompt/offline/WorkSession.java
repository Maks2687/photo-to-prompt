package app.photoprompt.offline;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class WorkSession {
    interface Listener { void changed(); }
    final ModelStore models;
    PhotoData photo;
    boolean busy, analyzing, fallback, truncated;
    String status = "Выберите фото. Анализ останется на телефоне.";
    String analysis = "", prompt = "", streaming = "";
    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private WeakReference<Listener> listener = new WeakReference<>(null);
    private AtomicBoolean cancelled = new AtomicBoolean(false);
    private long nativeJob;

    WorkSession(Context context) { this.context = context; models = new ModelStore(context); }
    void attach(Listener listener) { this.listener = new WeakReference<>(listener); emit(); }
    void detach(Listener listener) { if (this.listener.get() == listener) this.listener.clear(); }
    private void emit() { Listener target = listener.get(); if (target != null) target.changed(); }
    private void begin(String message) {
        busy = true; cancelled = new AtomicBoolean(false); status = message; emit();
    }
    private void finish(String message) {
        if (nativeJob != 0) { NativeBridge.freeJob(nativeJob); nativeJob = 0; }
        busy = false; analyzing = false; streaming = ""; status = message; emit();
    }
    void loadPhoto(Uri uri) {
        if (busy) return;
        begin("Читаю фото и EXIF…");
        AtomicBoolean flag = cancelled;
        worker.execute(() -> {
            try {
                PhotoData loaded = PhotoData.load(context, uri);
                main.post(() -> {
                    if (flag.get()) { loaded.bitmap.recycle(); finish("Загрузка отменена."); return; }
                    PhotoData previous = photo;
                    photo = loaded; analysis = ""; prompt = ""; fallback = false; truncated = false;
                    finish(models.ready() ? "Фото готово. Можно запустить анализ." : "Фото готово. Подключите модель через кнопку «Модель».");
                    if (previous != null) previous.bitmap.recycle();
                });
            } catch (Exception e) { main.post(() -> finish("Не удалось открыть фото: " + safeMessage(e))); }
            catch (OutOfMemoryError e) { main.post(() -> finish("Не хватило памяти. Закройте другие приложения и выберите фото меньшего размера.")); }
        });
    }
    void importModel(Uri uri, boolean projector) {
        if (busy) return;
        begin("Импортирую и проверяю файл модели…");
        AtomicBoolean flag = cancelled;
        worker.execute(() -> {
            try {
                models.importFile(uri, projector, flag, count -> main.post(() -> {
                    status = "Импорт и проверка: " + (count / 1_000_000) + " МБ…"; emit();
                }));
                main.post(() -> finish(models.ready() ? "Оба файла проверены. Модель готова к анализу." :
                    "Файл проверен. Импортируйте второй файл через кнопку «Модель»."));
            } catch (Exception e) {
                main.post(() -> finish(flag.get() ? "Импорт отменён." : safeMessage(e)));
            }
        });
    }
    void analyze() {
        if (busy || photo == null) return;
        if (!models.ready()) { status = "Сначала подключите оба файла модели."; emit(); return; }
        if (NativeBridge.LOAD_ERROR != null) { status = "Не удалось загрузить ИИ-движок. Нужен APK для arm64-v8a."; emit(); return; }
        try { nativeJob = NativeBridge.newJob(); }
        catch (RuntimeException | OutOfMemoryError e) { status = "Не хватило памяти для запуска ИИ."; emit(); return; }
        if (nativeJob == 0) { status = "Не удалось запустить ИИ."; emit(); return; }
        analyzing = true; analysis = ""; prompt = ""; streaming = ""; fallback = false; truncated = false;
        begin("Загружаю локальную модель…");
        long job = nativeJob;
        AtomicBoolean flag = cancelled;
        PhotoData input = photo;
        worker.execute(() -> {
            try {
                byte[][] generated = NativeBridge.generate(job, models.file(false).getAbsolutePath(),
                    models.file(true).getAbsolutePath(), input.rgb(), input.bitmap.getWidth(), input.bitmap.getHeight(),
                    PromptFormat.INSTRUCTION, (stage, bytes) -> {
                        String text = new String(bytes, StandardCharsets.UTF_8);
                        main.post(() -> {
                            if (flag.get()) return;
                            String[] stages = {"", "Загружаю языковую модель…", "Загружаю зрительную модель…",
                                "Разбираю фото по шести разделам. Это может занять несколько минут…", "Пишу разбор на английском…"};
                            status = stages[Math.max(0, Math.min(stage, stages.length - 1))];
                            streaming = text; emit();
                        });
                    });
                String text = new String(generated[0], StandardCharsets.UTF_8);
                boolean limit = "length".equals(new String(generated[1], StandardCharsets.UTF_8));
                PromptFormat result = PromptFormat.parse(text);
                main.post(() -> {
                    if (flag.get()) { finish("Анализ отменён."); return; }
                    analysis = result.analysis; prompt = result.prompt; fallback = result.fallback; truncated = limit;
                    finish(limit ? "Достигнут лимит длины. Результат может быть неполным — проверьте текст." :
                        result.fallback ? "Описание готово. Модель не выделила промпт: в его поле помещён черновик описания." :
                        "Готово. Проверьте описание и скопируйте промпт.");
                });
            } catch (Exception e) {
                main.post(() -> finish(flag.get() ? "Анализ отменён." :
                    "ИИ не смог завершить анализ. Закройте другие приложения и повторите. Код: " + safeMessage(e)));
            } catch (OutOfMemoryError e) {
                main.post(() -> finish("Не хватило памяти. Закройте другие приложения и повторите анализ."));
            }
        });
    }
    void cancel() {
        if (!busy) return;
        cancelled.set(true);
        if (nativeJob != 0) NativeBridge.cancel(nativeJob);
        status = "Останавливаю… Текущий этап обработки изображения может завершиться не сразу."; emit();
    }
    void clearPhoto() {
        if (busy) return;
        PhotoData previous = photo;
        photo = null; analysis = ""; prompt = ""; streaming = ""; fallback = false; truncated = false;
        status = "Фото и результат удалены из текущего сеанса."; emit();
        if (previous != null) previous.bitmap.recycle();
    }
    void deleteModels() {
        if (busy) return;
        models.delete(); status = "Файлы модели удалены из приложения."; emit();
    }
    String report() {
        if (photo == null) return "";
        String result = PromptFormat.fullReport(photo.metadata, photo.measurements, analysis, prompt);
        if (truncated) result += "\n\nGeneration reached the output limit and may be incomplete.";
        if (fallback) result += "\n\nThe model did not provide a separate prompt; its description is supplied as an editable draft.";
        return result;
    }
    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message.substring(0, Math.min(message.length(), 300));
    }
}
