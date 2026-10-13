package app.photoprompt.offline;

final class NativeBridge {
    static final String LOAD_ERROR;
    static {
        String error = null;
        try { System.loadLibrary("photo_ai"); }
        catch (UnsatisfiedLinkError e) { error = e.getMessage(); }
        LOAD_ERROR = error;
    }
    interface Progress { void onProgress(int stage, byte[] generated); }
    static native long newJob();
    static native void cancel(long job);
    static native void freeJob(long job);
    // [0] UTF-8 generation, [1] "complete" or "length".
    static native byte[][] generate(long job, String model, String projector,
                                    byte[] rgb, int width, int height,
                                    String instruction, Progress progress);
}
