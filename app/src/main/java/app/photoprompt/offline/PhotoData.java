package app.photoprompt.offline;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import androidx.exifinterface.media.ExifInterface;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class PhotoData {
    final Bitmap bitmap;
    final String metadata, measurements;
    private PhotoData(Bitmap bitmap, String metadata, String measurements) {
        this.bitmap = bitmap; this.metadata = metadata; this.measurements = measurements;
    }
    static PhotoData load(Context context, Uri uri) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, options);
        }
        int width = options.outWidth, height = options.outHeight;
        if (width <= 0 || height <= 0) throw new IOException("Не удалось прочитать фото. Попробуйте JPEG или PNG.");
        ExifInterface exif = null;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in != null) exif = new ExifInterface(in);
        } catch (IOException | RuntimeException ignored) { /* EXIF is optional. */ }
        String[][] tags = {
            {"Camera manufacturer", ExifInterface.TAG_MAKE},
            {"Camera model", ExifInterface.TAG_MODEL},
            {"Lens manufacturer", ExifInterface.TAG_LENS_MAKE},
            {"Lens model", ExifInterface.TAG_LENS_MODEL},
            {"Focal length (mm)", ExifInterface.TAG_FOCAL_LENGTH},
            {"35 mm equivalent focal length (mm)", ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM},
            {"Aperture (f-number)", ExifInterface.TAG_F_NUMBER},
            {"Exposure time (seconds)", ExifInterface.TAG_EXPOSURE_TIME},
            {"ISO", ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY}
        };
        StringBuilder metadata = new StringBuilder("Reported by the file; not independently verified.\n");
        for (String[] tag : tags) {
            String value = exif == null ? null : exif.getAttribute(tag[1]);
            if (value != null) value = value.replaceAll("[\\p{Cntrl}]", " ").trim();
            if (value != null && value.length() > 160) value = value.substring(0, 160);
            metadata.append(tag[0]).append(": ").append(value == null || value.isEmpty() ? "Not available" : value).append('\n');
        }
        metadata.append("Exact camera or lens cannot be recovered from pixels alone.");
        options.inJustDecodeBounds = false;
        options.inSampleSize = 1;
        while (Math.max(width, height) / options.inSampleSize > 1024) options.inSampleSize *= 2;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            decoded = BitmapFactory.decodeStream(in, null, options);
        }
        if (decoded == null) throw new IOException("Этот формат изображения не поддерживается.");
        int orientation = exif == null ? ExifInterface.ORIENTATION_NORMAL :
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        Matrix transform = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: transform.setScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_180: transform.setRotate(180); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: transform.setScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: transform.setRotate(90); transform.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_90: transform.setRotate(90); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: transform.setRotate(-90); transform.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_270: transform.setRotate(-90); break;
            default: break;
        }
        Bitmap oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), transform, true);
        if (oriented != decoded) decoded.recycle();
        float scale = Math.min(1f, 512f / Math.max(oriented.getWidth(), oriented.getHeight()));
        Bitmap small = Bitmap.createScaledBitmap(oriented, Math.max(1, Math.round(oriented.getWidth() * scale)),
            Math.max(1, Math.round(oriented.getHeight() * scale)), true);
        if (small != oriented) oriented.recycle();
        boolean swapped = orientation >= ExifInterface.ORIENTATION_TRANSPOSE && orientation <= ExifInterface.ORIENTATION_ROTATE_270;
        int displayWidth = swapped ? height : width, displayHeight = swapped ? width : height;
        String measurements = String.format(Locale.US,
            "Display dimensions: %d × %d px\nAspect ratio: %.3f:1\n%s\n" +
            "Palette measured from a resized preview; it does not identify film stock.",
            displayWidth, displayHeight, (double) displayWidth / displayHeight, palette(small));
        return new PhotoData(small, metadata.toString(), measurements);
    }
    byte[] rgb() {
        int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        byte[] result = new byte[pixels.length * 3];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i], a = (p >>> 24) & 255;
            // Composite transparent PNG pixels on white, matching the preview.
            result[i * 3] = (byte) ((((p >> 16) & 255) * a + 255 * (255 - a)) / 255);
            result[i * 3 + 1] = (byte) ((((p >> 8) & 255) * a + 255 * (255 - a)) / 255);
            result[i * 3 + 2] = (byte) (((p & 255) * a + 255 * (255 - a)) / 255);
        }
        return result;
    }
    private static String palette(Bitmap bitmap) {
        int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        int[] counts = new int[512];
        long[] reds = new long[512], greens = new long[512], blues = new long[512];
        for (int p : pixels) {
            int a = p >>> 24;
            int r = (((p >> 16) & 255) * a + 255 * (255 - a)) / 255;
            int g = (((p >> 8) & 255) * a + 255 * (255 - a)) / 255;
            int b = ((p & 255) * a + 255 * (255 - a)) / 255;
            int bin = (r >> 5) * 64 + (g >> 5) * 8 + (b >> 5);
            counts[bin]++; reds[bin] += r; greens[bin] += g; blues[bin] += b;
        }
        List<Integer> bins = new ArrayList<>();
        for (int i = 0; i < 512; i++) if (counts[i] > 0) bins.add(i);
        bins.sort((a, b) -> Integer.compare(counts[b], counts[a]));
        StringBuilder result = new StringBuilder("Dominant sampled colors: ");
        for (int i = 0; i < Math.min(5, bins.size()); i++) {
            int bin = bins.get(i), count = counts[bin];
            if (i > 0) result.append(", ");
            result.append(String.format(Locale.US, "#%02X%02X%02X", reds[bin] / count, greens[bin] / count, blues[bin] / count));
        }
        return result.toString();
    }
}
