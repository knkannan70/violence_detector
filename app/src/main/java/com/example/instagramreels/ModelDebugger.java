package com.example.instagramreels;

import android.graphics.Bitmap;
import android.util.Log;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class ModelDebugger {
    private static final String TAG = "ModelDebugger";

    public static void comparePreprocessing(Bitmap bitmap, String tag) {
        // Print first few pixel values to compare with Python
        int[] pixels = new int[10];
        bitmap.getPixels(pixels, 0, 1, 0, 0, 10, 1);

        StringBuilder sb = new StringBuilder();
        sb.append(tag).append(" first 10 pixels RGB: ");
        for (int i = 0; i < Math.min(10, pixels.length); i++) {
            int r = (pixels[i] >> 16) & 0xFF;
            int g = (pixels[i] >> 8) & 0xFF;
            int b = pixels[i] & 0xFF;
            sb.append(String.format("(%d,%d,%d) ", r, g, b));
        }
        Log.d(TAG, sb.toString());

        // Calculate normalized values
        sb = new StringBuilder();
        sb.append(tag).append(" normalized values: ");
        for (int i = 0; i < Math.min(10, pixels.length); i++) {
            int r = (pixels[i] >> 16) & 0xFF;
            float rf = (r / 127.5f) - 1.0f;
            sb.append(String.format("%.3f ", rf));
        }
        Log.d(TAG, sb.toString());
    }
}