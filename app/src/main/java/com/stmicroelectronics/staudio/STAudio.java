package com.stmicroelectronics.staudio;

import android.app.Application;
import android.os.Build;
import android.util.Log;

import org.jetbrains.annotations.NotNull;

import timber.log.Timber;

public class STAudio extends Application {

    private static final int MAX_LOG_LENGTH = 4000;
    private Timber.Tree tree;

    @Override
    public void onCreate() {
        super.onCreate();

        // enable if device is debuggable or if application build is debug
        if (! Build.TYPE.equals("user") || BuildConfig.DEBUG) {
            tree = new Timber.DebugTree();
        } else {
            tree = new Timber.Tree() {
                @Override
                protected void log(int priority, @org.jetbrains.annotations.Nullable String tag, @NotNull String message, @org.jetbrains.annotations.Nullable Throwable t) {
                    if (priority < Log.INFO) {
                        return;
                    }
                    if (message.length() < MAX_LOG_LENGTH) {
                        Log.println(priority, tag, message);
                    }
                }
            };
        }
        Timber.plant(tree);
    }

    @Override
    public void onTerminate() {
        super.onTerminate();

        if (tree != null) {
            Timber.uproot(tree);
        }
    }
}
