package com.example.instagramreels;

import android.os.Looper;
import android.util.Log;

public class ANRWatchdog extends Thread {
    private static final String TAG = "ANRWatchdog";
    private static final int TIMEOUT = 5000;
    private boolean running = true;
    private long lastTick;

    @Override
    public void run() {
        while (running) {
            lastTick = System.currentTimeMillis();
            try {
                Thread.sleep(TIMEOUT);
            } catch (InterruptedException e) {
                break;
            }

            if (System.currentTimeMillis() - lastTick > TIMEOUT) {
                Log.e(TAG, "Possible ANR detected! Main thread blocked for > " + TIMEOUT + "ms");

                StackTraceElement[] stackTrace = Looper.getMainLooper().getThread().getStackTrace();
                Log.e(TAG, "Main thread stack trace:");
                for (StackTraceElement element : stackTrace) {
                    Log.e(TAG, "    at " + element.toString());
                }
            }
        }
    }

    public void stopWatchdog() {
        running = false;
        interrupt();
    }
}