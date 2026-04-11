package com.example.instagramreels;

import android.app.Activity;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;

public class CrashHandler implements Thread.UncaughtExceptionHandler {
    private static final String TAG = "CrashHandler";
    private final Thread.UncaughtExceptionHandler defaultHandler;
    private static WeakReference<Activity> lastActivityRef;

    public CrashHandler() {
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(this);
    }

    public static void setLastActivity(Activity activity) {
        lastActivityRef = new WeakReference<>(activity);
    }

    @Override
    public void uncaughtException(Thread thread, Throwable ex) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        ex.printStackTrace(pw);
        String stackTrace = sw.toString();

        Log.e(TAG, "========================================");
        Log.e(TAG, "!!! CRASH DETECTED !!!");
        Log.e(TAG, "========================================");
        Log.e(TAG, "Thread: " + thread.getName() + " (ID: " + thread.getId() + ")");
        Log.e(TAG, "Time: " + System.currentTimeMillis());
        Log.e(TAG, "Device: " + Build.MANUFACTURER + " " + Build.MODEL + " (API " + Build.VERSION.SDK_INT + ")");
        Log.e(TAG, "Exception: " + ex.toString());

        if (ex.getMessage() != null) {
            Log.e(TAG, "Message: " + ex.getMessage());
        }

        Log.e(TAG, "Stack trace:\n" + stackTrace);

        Throwable cause = ex.getCause();
        int causeCount = 0;
        while (cause != null && causeCount < 10) {
            Log.e(TAG, "Caused by (" + causeCount + "): " + cause.toString());
            for (StackTraceElement element : cause.getStackTrace()) {
                Log.e(TAG, "    at " + element.toString());
            }
            cause = cause.getCause();
            causeCount++;
        }

        Log.e(TAG, "========================================");

        if (defaultHandler != null) {
            defaultHandler.uncaughtException(thread, ex);
        } else {
            Process.killProcess(Process.myPid());
            System.exit(1);
        }
    }
}