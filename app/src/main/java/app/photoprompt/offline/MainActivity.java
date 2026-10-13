package app.photoprompt.offline;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity implements WorkSession.Listener {
    private static final int PICK_PHOTO = 10, PICK_MODEL = 11, PICK_PROJECTOR = 12;
    private static final int BG = 0xff111412, CARD = 0xff1c211d, INK = 0xfff0f3e9, MUTED = 0xffa8b0a5, ACCENT = 0xffd6f484;
    private WorkSession session;
    private TextView status, modelStatus, metadata, measurements, interpretation, promptNotice, emptyPreview;
    private ImageView preview;
    private ProgressBar progress;
    private Button pick, analyze, cancel, clear, modelButton, copyPrompt, copyAll;
    private LinearLayout metadataCard, analysisCard, promptCard;
    private EditText promptEditor;
    private PhotoData displayedPhoto;
    private boolean rendering;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        session = ((PhotoPromptApp) getApplication()).session;
        buildUi();
        session.attach(this);
    }
    @Override protected void onStop() {
        super.onStop();
        if (session.analyzing && !isChangingConfigurations()) session.cancel();
    }
    @Override protected void onDestroy() {
        session.detach(this);
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        LinearLayout root = column();
        root.setPadding(dp(22), dp(20), dp(22), dp(28));
        scroll.addView(root);
        // Android 15 enforces edge-to-edge. Keep all controls inside system insets.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(dp(22) + insets.getSystemWindowInsetLeft(), dp(20) + insets.getSystemWindowInsetTop(),
                dp(22) + insets.getSystemWindowInsetRight(), dp(28) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        TextView badge = text("●  OFFLINE  /  ON DEVICE", 11, ACCENT);
        badge.setLetterSpacing(0.12f); root.addView(badge);
        TextView title = text("Photo → Prompt", 32, INK);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        root.addView(title, margin(0, 9, 0, 5));
        root.addView(text("Разберите фотографию.\nВоссоздайте её атмосферу.", 16, MUTED), margin(0, 0, 0, 20));

        LinearLayout modelRow = new LinearLayout(this); modelRow.setGravity(Gravity.CENTER_VERTICAL);
        modelStatus = text("", 13, MUTED);
        modelRow.addView(modelStatus, new LinearLayout.LayoutParams(0, -2, 1));
        modelButton = button("Модель", false, v -> showModels());
        modelRow.addView(modelButton, new LinearLayout.LayoutParams(-2, dp(48)));
        root.addView(modelRow, margin(0, 0, 0, 16));

        LinearLayout photoCard = card(root);
        photoCard.addView(text("01  /  ИСХОДНОЕ ФОТО", 11, MUTED));
        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setContentDescription("Выбранная фотография");
        preview.setBackgroundColor(Color.WHITE);
        photoCard.addView(preview, new LinearLayout.LayoutParams(-1, dp(230)));
        emptyPreview = text("＋\nВыберите фото из галереи\nJPEG · PNG · поддерживаемые телефоном форматы", 15, MUTED);
        emptyPreview.setGravity(Gravity.CENTER);
        photoCard.addView(emptyPreview, new LinearLayout.LayoutParams(-1, dp(170)));
        pick = button("Выбрать фото", true, v -> choose("image/*", PICK_PHOTO));
        photoCard.addView(pick, margin(0, 12, 0, 0));

        status = text("", 14, MUTED);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status, margin(0, 2, 0, 8));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(4)));
        analyze = button("Анализировать на телефоне", true, v -> {
            if (!session.models.ready()) showModels(); else session.analyze();
        });
        root.addView(analyze, margin(0, 12, 0, 8));
        cancel = button("Остановить", false, v -> session.cancel());
        root.addView(cancel, margin(0, 0, 0, 8));

        metadataCard = card(root);
        metadataCard.addView(text("02  /  ДАННЫЕ ФАЙЛА", 11, ACCENT));
        metadataCard.addView(text("Камера и объектив из EXIF", 20, INK), margin(0, 7, 0, 8));
        metadata = selectable("", 14); metadataCard.addView(metadata);
        measurements = selectable("", 14); metadataCard.addView(measurements, margin(0, 14, 0, 0));

        analysisCard = card(root);
        analysisCard.addView(text("03  /  ВИЗУАЛЬНЫЙ РАЗБОР", 11, ACCENT));
        analysisCard.addView(text("Предположения ИИ", 20, INK), margin(0, 7, 0, 8));
        analysisCard.addView(text("Свет, цвет, композиция и стиль. Описание может ошибаться; сходство со стилем не устанавливает автора.", 12, MUTED), margin(0, 0, 0, 12));
        interpretation = selectable("", 15); analysisCard.addView(interpretation);

        promptCard = card(root);
        promptCard.addView(text("04  /  GENERATION PROMPT", 11, ACCENT));
        promptCard.addView(text("Готов к вашим правкам", 20, INK), margin(0, 7, 0, 8));
        promptNotice = text("", 12, MUTED); promptCard.addView(promptNotice);
        promptEditor = new EditText(this);
        promptEditor.setTextColor(INK); promptEditor.setTextSize(15); promptEditor.setMinLines(4);
        promptEditor.setGravity(Gravity.TOP); promptEditor.setPadding(dp(12), dp(12), dp(12), dp(12));
        promptEditor.setBackground(shape(BG, 12, 0xff465140));
        promptEditor.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE |
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        promptEditor.setContentDescription("Промпт на английском, можно редактировать");
        promptEditor.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        promptEditor.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!rendering) { session.prompt = s.toString(); copyPrompt.setEnabled(s.toString().trim().length() > 0); }
            }
            public void afterTextChanged(Editable e) {}
        });
        promptCard.addView(promptEditor, margin(0, 10, 0, 10));
        copyPrompt = button("Копировать промпт", true, v -> copy(session.prompt));
        promptCard.addView(copyPrompt);
        copyAll = button("Копировать весь разбор", false, v -> copy(session.report()));
        root.addView(copyAll, margin(0, 4, 0, 8));
        clear = button("Удалить фото и результат", false, v -> session.clearPhoto());
        root.addView(clear, margin(0, 0, 0, 18));
        root.addView(text("Фото обрабатывается в памяти телефона. В приложении нет доступа к интернету и резервного копирования. Скопированный текст передаётся в системный буфер обмена.\n\nСеанс не сохраняется после закрытия процесса. Сохраните нужный результат копированием.", 12, MUTED));
        TextView about = text("Photo to Prompt · 0.1\nSmolVLM / Apache 2.0 · llama.cpp / MIT", 11, MUTED);
        about.setGravity(Gravity.CENTER); root.addView(about, margin(0, 24, 0, 0));
        setContentView(scroll);
        root.requestApplyInsets();
    }

    @Override public void changed() {
        rendering = true;
        status.setText(session.status);
        modelStatus.setText(session.models.ready() ? "● Модель подключена" : "○ Модель не подключена");
        modelStatus.setTextColor(session.models.ready() ? ACCENT : MUTED);
        pick.setEnabled(!session.busy); modelButton.setEnabled(!session.busy);
        analyze.setEnabled(!session.busy && session.photo != null);
        analyze.setText(session.models.ready() ? "Анализировать на телефоне" : "Подключить модель для анализа");
        clear.setEnabled(!session.busy); clear.setVisibility(session.photo == null ? View.GONE : View.VISIBLE);
        cancel.setVisibility(session.busy ? View.VISIBLE : View.GONE);
        progress.setVisibility(session.busy ? View.VISIBLE : View.GONE);
        if (session.busy) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        preview.setVisibility(session.photo == null ? View.GONE : View.VISIBLE);
        emptyPreview.setVisibility(session.photo == null ? View.VISIBLE : View.GONE);
        metadataCard.setVisibility(session.photo == null ? View.GONE : View.VISIBLE);
        if (displayedPhoto != session.photo) {
            displayedPhoto = session.photo;
            preview.setImageBitmap(session.photo == null ? null : session.photo.bitmap);
            metadata.setText(session.photo == null ? "" : session.photo.metadata);
            measurements.setText(session.photo == null ? "" : session.photo.measurements);
        }
        String visual = session.analyzing ? session.streaming : session.analysis;
        analysisCard.setVisibility(visual.isEmpty() ? View.GONE : View.VISIBLE);
        interpretation.setText(visual);
        promptCard.setVisibility(session.prompt.isEmpty() && session.analysis.isEmpty() ? View.GONE : View.VISIBLE);
        promptEditor.setEnabled(!session.busy);
        if (!promptEditor.getText().toString().equals(session.prompt)) promptEditor.setText(session.prompt);
        promptNotice.setText(session.truncated ? "Достигнут лимит длины. Проверьте, что текст закончен." :
            session.fallback ? "Модель не выделила отдельный промпт. Здесь её описание как редактируемый черновик." :
            "Английский текст для генерации изображения. Проверьте детали перед копированием.");
        copyPrompt.setEnabled(!session.busy && !session.prompt.trim().isEmpty());
        copyAll.setVisibility(session.photo == null ? View.GONE : View.VISIBLE);
        copyAll.setEnabled(!session.busy);
        rendering = false;
    }

    private void choose(String type, int code) {
        if (session.busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(type);
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        try { startActivityForResult(intent, code); }
        catch (android.content.ActivityNotFoundException e) { toast("На телефоне не найден выбор файлов."); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request == PICK_PHOTO) session.loadPhoto(data.getData());
        else if (request == PICK_MODEL || request == PICK_PROJECTOR)
            session.importModel(data.getData(), request == PICK_PROJECTOR);
    }
    private void showModels() {
        if (session.busy) return;
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = column(); body.setPadding(dp(22), dp(12), dp(22), dp(12));
        scroll.addView(body);
        body.addView(text("Один раз скачайте два файла через браузер, затем импортируйте их сюда. Вместе около 550 МБ. На время загрузки и импорта оставьте 1,2 ГБ свободного места.\n\nФотографии не передаются браузеру. После импорта анализ работает в авиарежиме. Компактная модель может давать краткие или неточные описания.", 14, INK));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Локальная модель · SmolVLM")
            .setView(scroll).setNegativeButton("Закрыть", null).create();
        body.addView(text("1. Языковая модель · 437 МБ" + (session.models.present(false) ? " · готово" : ""), 16, ACCENT), margin(0, 18, 0, 6));
        body.addView(button("Скачать файл 1 в браузере", false, v -> openDownload(ModelStore.MODEL_NAME)));
        body.addView(button("Импортировать файл 1", true, v -> { dialog.dismiss(); choose("*/*", PICK_MODEL); }));
        body.addView(text("2. Зрительная модель · 109 МБ" + (session.models.present(true) ? " · готово" : ""), 16, ACCENT), margin(0, 18, 0, 6));
        body.addView(button("Скачать файл 2 в браузере", false, v -> openDownload(ModelStore.PROJECTOR_NAME)));
        body.addView(button("Импортировать файл 2", true, v -> { dialog.dismiss(); choose("*/*", PICK_PROJECTOR); }));
        body.addView(text("При импорте проверяется SHA-256 обоих файлов. Другие модели в этой версии не поддерживаются. После успешного импорта копии из папки «Загрузки» можно удалить вручную.", 12, MUTED), margin(0, 16, 0, 12));
        body.addView(button("Удалить модель из приложения", false, v -> {
            dialog.dismiss();
            new AlertDialog.Builder(this).setTitle("Удалить файлы модели?")
                .setMessage("Для анализа их потребуется импортировать снова. Файлы в папке «Загрузки» останутся.")
                .setNegativeButton("Отмена", null).setPositiveButton("Удалить", (d, w) -> session.deleteModels()).show();
        }));
        dialog.show();
    }
    private void openDownload(String filename) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(ModelStore.BASE + filename + "?download=true"))); }
        catch (android.content.ActivityNotFoundException e) { toast("Не найден браузер. Ссылки есть в инструкции проекта."); }
    }
    private void copy(String value) {
        if (value.trim().isEmpty()) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Photo to Prompt", value));
        toast("Скопировано");
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout.LayoutParams margin(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p;
    }
    private TextView text(String value, int size, int color) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color);
        v.setLineSpacing(dp(3), 1f); return v;
    }
    private TextView selectable(String value, int size) {
        TextView v = text(value, size, INK); v.setTextIsSelectable(true); return v;
    }
    private GradientDrawable shape(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius));
        if (stroke != 0) d.setStroke(dp(1), stroke); return d;
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout v = column(); v.setPadding(dp(18), dp(18), dp(18), dp(18));
        v.setBackground(shape(CARD, 20, 0xff30382e)); parent.addView(v, margin(0, 0, 0, 16)); return v;
    }
    private Button button(String label, boolean primary, View.OnClickListener action) {
        Button v = new Button(this); v.setText(label); v.setAllCaps(false); v.setTextSize(14);
        v.setMinHeight(dp(50)); v.setPadding(dp(14), dp(10), dp(14), dp(10));
        int[][] states = {new int[] {-android.R.attr.state_enabled}, new int[] {}};
        v.setTextColor(new ColorStateList(states, new int[] {0xff818a7b, primary ? BG : INK}));
        v.setBackground(shape(primary ? ACCENT : CARD, 12, primary ? 0 : 0xff465140));
        v.setOnClickListener(action); return v;
    }
}
