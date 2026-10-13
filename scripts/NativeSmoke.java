package app.photoprompt.offline;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

// No user images: a generated drawing exercises exactly the application's JNI path.
public final class NativeSmoke {
    public static void main(String[] args) throws Exception {
        if (NativeBridge.LOAD_ERROR != null) throw new AssertionError(NativeBridge.LOAD_ERROR);
        int w = 384, h = 256;
        byte[] rgb = new byte[w * h * 3];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int r = 120, g = 190, b = 235; // sky
            if (y > 190) { r = 55; g = 155; b = 65; } // ground
            if ((x - 305) * (x - 305) + (y - 50) * (y - 50) < 27 * 27) { r = 255; g = 225; b = 50; }
            if (x > 105 && x < 250 && y > 107 && y <= 195) { r = 240; g = 207; b = 156; }
            if (y > 60 && y <= 110 && Math.abs(x - 177) < (y - 50) * 2) { r = 185; g = 40; b = 35; }
            if (x > 163 && x < 196 && y > 145 && y < 195) { r = 100; g = 60; b = 30; }
            int i = (y * w + x) * 3; rgb[i] = (byte) r; rgb[i + 1] = (byte) g; rgb[i + 2] = (byte) b;
        }
        long cancelled = NativeBridge.newJob();
        try {
            NativeBridge.cancel(cancelled);
            try {
                NativeBridge.generate(cancelled, args[0], args[1], rgb, w, h, "Describe this image.", (stage, text) -> {});
                throw new AssertionError("Cancelled job unexpectedly succeeded");
            } catch (RuntimeException expected) {
                if (!"CANCELLED".equals(expected.getMessage())) throw expected;
            }
        } finally { NativeBridge.freeJob(cancelled); }
        long job = NativeBridge.newJob();
        AtomicInteger stages = new AtomicInteger();
        try {
            byte[][] output = NativeBridge.generate(job, args[0], args[1], rgb, w, h,
                PromptFormat.INSTRUCTION, (stage, text) -> stages.getAndUpdate(v -> v | (1 << stage)));
            String text = new String(output[0], StandardCharsets.UTF_8);
            String finish = new String(output[1], StandardCharsets.UTF_8);
            if (text.trim().length() < 20) throw new AssertionError("No meaningful output");
            if (stages.get() != 30) throw new AssertionError("Missing progress stage: " + stages.get());
            if (!finish.equals("complete") && !finish.equals("length")) throw new AssertionError("Unexpected finish reason");
            PromptFormat parsed = PromptFormat.parse(text);
            if (parsed.prompt.isEmpty()) throw new AssertionError("No copyable prompt");
            if (parsed.fallback) throw new AssertionError("Structured analysis was not produced");
            for (String section : new String[] {"Subject and setting", "Composition and viewpoint", "Lighting and shadows", "Color and mood", "Focus and lens impression", "Style and processing"}) {
                if (!parsed.analysis.contains(section + ":") || !parsed.prompt.contains(section + ":"))
                    throw new AssertionError("Missing section: " + section);
            }
            Files.writeString(Path.of("native-smoke-result.txt"), "Synthetic drawing test. Not a phone benchmark.\nFinish: " +
                finish + "\nPrompt fallback: " + parsed.fallback + "\n\n" + text);
            System.out.println("PASS: JNI, cancellation, model + projector load, image encoding, generation and output parsing");
            System.out.println("SYNTHETIC_RESULT_BEGIN\n" + text + "\nSYNTHETIC_RESULT_END");
        } finally { NativeBridge.freeJob(job); }
    }
}
