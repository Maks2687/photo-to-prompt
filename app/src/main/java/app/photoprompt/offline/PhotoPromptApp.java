package app.photoprompt.offline;

import android.app.Application;

public final class PhotoPromptApp extends Application {
    WorkSession session;
    @Override public void onCreate() {
        super.onCreate();
        session = new WorkSession(this);
    }
}
