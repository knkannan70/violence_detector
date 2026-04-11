package com.example.instagramreels;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ViolenceDetector {
    private static final String TAG = "ViolenceDetector";

    // =========================
    // 2D MODEL SETTINGS
    // =========================
    private static final String MODEL_FILE = "violence_frozen_compat.tflite";
    private static final int INPUT_SIZE = 224;
    private static final int FRAME_SKIP = 6;                  // faster than 2
    private static final float VIOLENCE_THRESHOLD = 0.50f;
    private static final float BLOCK_RATIO = 0.30f;
    private static final int MAX_FRAMES_TO_ANALYZE = 10;     // faster than 30

    // =========================
    // 3D MODEL SETTINGS
    // =========================
    private static final String MODEL_FILE_3D = "violence_action.tflite";
    private static final int FRAME_SIZE_3D = 224;
    private static final int NUM_FRAMES_3D = 16;             // keep as model expects 16
    private static final int STRIDE_3D = 3;                  // faster clip sampling
    private static final float THRESH_3D = 0.50f;
    private static final float BLOCK_RATIO_3D = 0.30f;

    // =========================
    // FUSION SETTINGS
    // =========================
    private static final float WEIGHT_2D = 0.50f;            // faster model gets more weight
    private static final float WEIGHT_3D = 0.50f;
    private static final float FUSION_THRESHOLD = 0.50f;

    // =========================
    // FAST EARLY DECISION
    // =========================
    private static final float FAST_SAFE_THRESHOLD_2D = 0.10f;
    private static final float FAST_BLOCK_THRESHOLD_2D = 0.75f;

    // assume ~30 FPS if exact fps not available
    private static final long FRAME_INTERVAL_MS = 33L;

    private final Context context;
    private Interpreter tflite;      // 2D
    private Interpreter tflite3d;    // 3D
    private final ExecutorService executorService;
    private final Handler mainHandler;
    private boolean isInitialized = false;
    private final AtomicBoolean isAnalyzing = new AtomicBoolean(false);

    public interface DetectionCallback {
        /**
         * Called INSTANTLY before any analysis begins, on the main thread.
         * Use this to show/start the video immediately in your UI.
         * onResult() will fire later — block the video there if isViolent=true.
         */
        void onReadyToShow();

        void onResult(boolean isViolent, float confidence, float violentRatio);
        void onError(String error);
    }

    public ViolenceDetector(Context context) {
        this.context = context;
        this.executorService = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void initialize() {
        try {
            Log.d(TAG, "Starting violence detector initialization...");

            String[] files = context.getAssets().list("");
            boolean model2dFound = false;
            boolean model3dFound = false;

            if (files != null) {
                for (String file : files) {
                    Log.d(TAG, "Asset found: " + file);

                    if (file.equals(MODEL_FILE)) {
                        model2dFound = true;
                        Log.d(TAG, "✅ 2D model file found: " + MODEL_FILE);
                    }

                    if (file.equals(MODEL_FILE_3D)) {
                        model3dFound = true;
                        Log.d(TAG, "✅ 3D model file found: " + MODEL_FILE_3D);
                    }
                }
            }

            if (!model2dFound) {
                Log.e(TAG, "❌ 2D model file not found in assets. Please add: " + MODEL_FILE);
                isInitialized = false;
                return;
            }

            if (!model3dFound) {
                Log.e(TAG, "❌ 3D model file not found in assets. Please add: " + MODEL_FILE_3D);
                isInitialized = false;
                return;
            }

            Log.d(TAG, "Loading 2D model from assets...");
            MappedByteBuffer modelBuffer2d = FileUtil.loadMappedFile(context, MODEL_FILE);
            Log.d(TAG, "2D model loaded successfully, size: " + modelBuffer2d.capacity() + " bytes");

            Log.d(TAG, "Loading 3D model from assets...");
            MappedByteBuffer modelBuffer3d = FileUtil.loadMappedFile(context, MODEL_FILE_3D);
            Log.d(TAG, "3D model loaded successfully, size: " + modelBuffer3d.capacity() + " bytes");

            Interpreter.Options options2d = new Interpreter.Options();
            options2d.setNumThreads(2);   // smoother on many phones
            options2d.setUseNNAPI(false);

            Interpreter.Options options3d = new Interpreter.Options();
            options3d.setNumThreads(2);
            options3d.setUseNNAPI(false);

            tflite = new Interpreter(modelBuffer2d, options2d);
            tflite3d = new Interpreter(modelBuffer3d, options3d);

            isInitialized = true;

            Log.d(TAG, "✅ Violence detector initialized successfully with 2D + 3D models");

            if (tflite.getInputTensorCount() > 0) {
                int[] inputShape = tflite.getInputTensor(0).shape();
                Log.d(TAG, "2D model input shape: " + java.util.Arrays.toString(inputShape));
            }

            if (tflite.getOutputTensorCount() > 0) {
                int[] outputShape = tflite.getOutputTensor(0).shape();
                Log.d(TAG, "2D model output shape: " + java.util.Arrays.toString(outputShape));
            }

            if (tflite3d.getInputTensorCount() > 0) {
                int[] inputShape3d = tflite3d.getInputTensor(0).shape();
                Log.d(TAG, "3D model input shape: " + java.util.Arrays.toString(inputShape3d));
            }

            if (tflite3d.getOutputTensorCount() > 0) {
                int[] outputShape3d = tflite3d.getOutputTensor(0).shape();
                Log.d(TAG, "3D model output shape: " + java.util.Arrays.toString(outputShape3d));
            }

        } catch (IOException e) {
            Log.e(TAG, "IO Error loading model: " + e.getMessage(), e);
            isInitialized = false;
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error during initialization: " + e.getMessage(), e);
            isInitialized = false;
        }
    }

    // =========================
    // 2D PREPROCESS
    // =========================
    private ByteBuffer preprocessImage(Bitmap bitmap) {
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3);
        byteBuffer.order(ByteOrder.nativeOrder());

        int[] intValues = new int[INPUT_SIZE * INPUT_SIZE];
        bitmap.getPixels(intValues, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);

        int pixel = 0;
        for (int i = 0; i < INPUT_SIZE; ++i) {
            for (int j = 0; j < INPUT_SIZE; ++j) {
                final int val = intValues[pixel++];

                float r = ((val >> 16) & 0xFF);
                float g = ((val >> 8) & 0xFF);
                float b = (val & 0xFF);

                // same as Python: (img / 127.5) - 1.0
                r = (r / 127.5f) - 1.0f;
                g = (g / 127.5f) - 1.0f;
                b = (b / 127.5f) - 1.0f;

                byteBuffer.putFloat(r);
                byteBuffer.putFloat(g);
                byteBuffer.putFloat(b);
            }
        }

        byteBuffer.rewind();
        return byteBuffer;
    }

    // =========================
    // 3D PREPROCESS
    // =========================
    private ByteBuffer preprocessClip3D(List<Bitmap> frames) {
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(
                4 * NUM_FRAMES_3D * FRAME_SIZE_3D * FRAME_SIZE_3D * 3
        );
        byteBuffer.order(ByteOrder.nativeOrder());

        for (Bitmap bitmap : frames) {
            Bitmap resized = Bitmap.createScaledBitmap(bitmap, FRAME_SIZE_3D, FRAME_SIZE_3D, true);

            int[] intValues = new int[FRAME_SIZE_3D * FRAME_SIZE_3D];
            resized.getPixels(intValues, 0, FRAME_SIZE_3D, 0, 0, FRAME_SIZE_3D, FRAME_SIZE_3D);

            int pixel = 0;
            for (int i = 0; i < FRAME_SIZE_3D; i++) {
                for (int j = 0; j < FRAME_SIZE_3D; j++) {
                    final int val = intValues[pixel++];

                    float r = ((val >> 16) & 0xFF) / 255.0f;
                    float g = ((val >> 8) & 0xFF) / 255.0f;
                    float b = (val & 0xFF) / 255.0f;

                    byteBuffer.putFloat(r);
                    byteBuffer.putFloat(g);
                    byteBuffer.putFloat(b);
                }
            }

            if (resized != bitmap) {
                resized.recycle();
            }
        }

        byteBuffer.rewind();
        return byteBuffer;
    }

    public float analyzeFrame(Bitmap bitmap) {
        if (!isInitialized || tflite == null) {
            Log.w(TAG, "analyzeFrame called but detector not initialized");
            return 0f;
        }

        try {
            Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true);
            ByteBuffer inputBuffer = preprocessImage(resizedBitmap);
            float[][] output = new float[1][1];
            tflite.run(inputBuffer, output);
            float confidence = output[0][0];
            resizedBitmap.recycle();

            Log.d(TAG, "Frame analysis confidence: " + String.format("%.4f", confidence));
            return confidence;

        } catch (Exception e) {
            Log.e(TAG, "Error analyzing frame: " + e.getMessage(), e);
            return 0f;
        }
    }

    // =========================
    // 3D CLIP ANALYSIS
    // =========================
    private float analyzeClip3D(List<Bitmap> frames) {
        if (!isInitialized || tflite3d == null) {
            Log.w(TAG, "analyzeClip3D called but 3D detector not initialized");
            return 0f;
        }

        try {
            ByteBuffer inputBuffer = preprocessClip3D(frames);
            float[][] output = new float[1][1];
            tflite3d.run(inputBuffer, output);
            float confidence = output[0][0];

            Log.d(TAG, "3D clip analysis confidence: " + String.format("%.4f", confidence));
            return confidence;

        } catch (Exception e) {
            Log.e(TAG, "Error analyzing 3D clip: " + e.getMessage(), e);
            return 0f;
        }
    }

    private int estimateTotalFrames(long durationMs) {
        return Math.max(1, (int) (durationMs / FRAME_INTERVAL_MS));
    }

    // Faster: only 2 clips
    private List<Integer> sampleStartPositions(int totalFrames) {
        List<Integer> starts = new ArrayList<>();
        int need = NUM_FRAMES_3D * STRIDE_3D;

        if (totalFrames <= need) {
            starts.add(0);
            return starts;
        }

        int maxStart = totalFrames - need;
        starts.add(0);
        starts.add(maxStart / 2);

        return starts;
    }

    private List<Bitmap> readClipAt(MediaMetadataRetriever retriever, int startFrame) {
        List<Bitmap> frames = new ArrayList<>();
        Bitmap lastValidFrame = null;

        for (int i = 0; i < NUM_FRAMES_3D; i++) {
            int frameIndex = startFrame + (i * STRIDE_3D);
            long timeUs = frameIndex * FRAME_INTERVAL_MS * 1000L;

            Bitmap frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame != null) {
                Bitmap rgbFrame = convertToRGB(frame);
                frames.add(rgbFrame);

                if (lastValidFrame != null && !lastValidFrame.isRecycled()) {
                    lastValidFrame.recycle();
                }
                lastValidFrame = rgbFrame.copy(Bitmap.Config.ARGB_8888, false);

                frame.recycle();
            } else if (lastValidFrame != null) {
                frames.add(lastValidFrame.copy(Bitmap.Config.ARGB_8888, false));
            }
        }

        if (lastValidFrame != null && !lastValidFrame.isRecycled()) {
            lastValidFrame.recycle();
        }

        if (frames.isEmpty()) {
            return null;
        }

        while (frames.size() < NUM_FRAMES_3D) {
            frames.add(frames.get(frames.size() - 1).copy(Bitmap.Config.ARGB_8888, false));
        }

        return frames;
    }

    private void recycleFrames(List<Bitmap> frames) {
        if (frames == null) return;
        for (Bitmap b : frames) {
            if (b != null && !b.isRecycled()) {
                b.recycle();
            }
        }
    }

    public void analyzeVideo(String videoPath, DetectionCallback callback) {
        if (!isInitialized || tflite == null || tflite3d == null) {
            callback.onError("Violence detector not initialized");
            return;
        }

        if (isAnalyzing.get()) {
            Log.d(TAG, "Already analyzing a video, skipping...");
            return;
        }

        isAnalyzing.set(true);

        // ─────────────────────────────────────────────────────────────────────
        // INSTANT DISPLAY: fire onReadyToShow() immediately on the main thread,
        // BEFORE the background analysis thread even starts.
        //
        // → Your UI shows / plays the video right away with zero wait.
        // → If onResult() later returns isViolent=true, block it at that point.
        // → If onResult() returns isViolent=false, simply leave it playing.
        // ─────────────────────────────────────────────────────────────────────
        mainHandler.post(callback::onReadyToShow);

        executorService.execute(() -> {
            MediaMetadataRetriever retriever = null;
            try {
                Log.d(TAG, "========== STARTING VIDEO ANALYSIS ==========");
                Log.d(TAG, "Video path: " + videoPath);

                retriever = new MediaMetadataRetriever();
                retriever.setDataSource(videoPath);

                String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (durationStr == null) {
                    throw new Exception("Could not get video duration");
                }

                long durationMs = Long.parseLong(durationStr);
                int estimatedTotalFrames = estimateTotalFrames(durationMs);

                Log.d(TAG, "Video duration: " + durationMs + " ms");
                Log.d(TAG, "Estimated total frames: " + estimatedTotalFrames);

                // =========================================
                // 2D ANALYSIS FIRST (FAST SCREENING)
                // =========================================
                List<Float> frameConfidences = new ArrayList<>();
                int violentFrames = 0;
                int totalProcessedFrames = 0;
                int frameCount = 0;

                long timeUs = 0;
                while (timeUs <= durationMs * 1000L && totalProcessedFrames < MAX_FRAMES_TO_ANALYZE) {
                    if (!isAnalyzing.get()) {
                        Log.d(TAG, "Analysis cancelled during 2D stage");
                        return;
                    }

                    if (frameCount % FRAME_SKIP == 0) {
                        Bitmap frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);

                        if (frame != null) {
                            totalProcessedFrames++;
                            Bitmap rgbFrame = convertToRGB(frame);
                            float confidence2d = analyzeFrame(rgbFrame);
                            frameConfidences.add(confidence2d);

                            if (confidence2d >= VIOLENCE_THRESHOLD) {
                                violentFrames++;
                            }

                            Log.d(TAG, String.format(
                                    "2D Frame %d: confidence=%.4f, violent=%s, totalViolent=%d",
                                    totalProcessedFrames,
                                    confidence2d,
                                    confidence2d >= VIOLENCE_THRESHOLD,
                                    violentFrames
                            ));

                            rgbFrame.recycle();
                            frame.recycle();

                            // Early 2D stop
                            if (totalProcessedFrames >= 6) {
                                float currentRatio = (float) violentFrames / totalProcessedFrames;

                                if (currentRatio <= FAST_SAFE_THRESHOLD_2D) {
                                    Log.d(TAG, "⚡ Early 2D stop: strongly SAFE");
                                    break;
                                }

                                if (currentRatio >= FAST_BLOCK_THRESHOLD_2D) {
                                    Log.d(TAG, "⚡ Early 2D stop: strongly VIOLENT");
                                    break;
                                }
                            }
                        }
                    }

                    frameCount++;
                    timeUs = frameCount * FRAME_INTERVAL_MS * 1000L;
                }

                float ratio2d = totalProcessedFrames > 0
                        ? (float) violentFrames / totalProcessedFrames
                        : 0f;

                float avgConfidence2d = 0f;
                for (float conf : frameConfidences) {
                    avgConfidence2d += conf;
                }
                if (totalProcessedFrames > 0) {
                    avgConfidence2d /= totalProcessedFrames;
                }

                boolean decision2d = ratio2d >= BLOCK_RATIO;

                Log.d(TAG, "----- 2D RESULT -----");
                Log.d(TAG, "2D Total frames processed: " + totalProcessedFrames);
                Log.d(TAG, "2D Violent frames: " + violentFrames);
                Log.d(TAG, "2D Non-violent frames: " + (totalProcessedFrames - violentFrames));
                Log.d(TAG, String.format("2D Violent ratio: %.2f", ratio2d));
                Log.d(TAG, String.format("2D Avg confidence: %.4f", avgConfidence2d));
                Log.d(TAG, "2D Decision: " + (decision2d ? "BLOCK-CONTENT 🚫" : "SAFE-CONTENT ✅"));

                // Fast 2D only decision
                if (ratio2d <= FAST_SAFE_THRESHOLD_2D) {
                    final boolean isViolent = false;
                    final float finalConfidence = avgConfidence2d;
                    final float finalViolentRatio = ratio2d;

                    Log.d(TAG, "⚡ Fast final decision from 2D only: SAFE-CONTENT ✅");
                    mainHandler.post(() -> {
                        callback.onResult(isViolent, finalConfidence, finalViolentRatio);
                        isAnalyzing.set(false);
                    });
                    return;
                }

                if (ratio2d >= FAST_BLOCK_THRESHOLD_2D) {
                    final boolean isViolent = true;
                    final float finalConfidence = avgConfidence2d;
                    final float finalViolentRatio = ratio2d;

                    Log.d(TAG, "⚡ Fast final decision from 2D only: BLOCK-CONTENT 🚫");
                    mainHandler.post(() -> {
                        callback.onResult(isViolent, finalConfidence, finalViolentRatio);
                        isAnalyzing.set(false);
                    });
                    return;
                }

                // =========================================
                // 3D ANALYSIS ONLY IF 2D IS UNCERTAIN
                // =========================================
                List<Integer> starts = sampleStartPositions(estimatedTotalFrames);

                List<Float> clipScores = new ArrayList<>();
                int violentClips = 0;
                int nonViolentClips = 0;

                for (int s : starts) {
                    if (!isAnalyzing.get()) {
                        Log.d(TAG, "Analysis cancelled during 3D stage");
                        return;
                    }

                    List<Bitmap> clipFrames = readClipAt(retriever, s);
                    if (clipFrames == null || clipFrames.isEmpty()) {
                        continue;
                    }

                    float score3d = analyzeClip3D(clipFrames);
                    clipScores.add(score3d);

                    if (score3d >= THRESH_3D) {
                        violentClips++;
                    } else {
                        nonViolentClips++;
                    }

                    Log.d(TAG, String.format(
                            "3D Clip start=%d: confidence=%.4f, violent=%s, violentClips=%d",
                            s,
                            score3d,
                            score3d >= THRESH_3D,
                            violentClips
                    ));

                    recycleFrames(clipFrames);

                    // Early 3D stop
                    int processedSoFar = violentClips + nonViolentClips;
                    if (processedSoFar >= 1) {
                        float currentRatio3d = (float) violentClips / processedSoFar;

                        if (currentRatio3d == 0.0f) {
                            Log.d(TAG, "⚡ Early 3D stop: SAFE");
                            break;
                        }

                        if (currentRatio3d == 1.0f) {
                            Log.d(TAG, "⚡ Early 3D stop: VIOLENT");
                            break;
                        }
                    }
                }

                int processedClips = violentClips + nonViolentClips;

                float ratio3d = processedClips > 0
                        ? (float) violentClips / processedClips
                        : 0f;

                float avgConfidence3d = 0f;
                for (float score : clipScores) {
                    avgConfidence3d += score;
                }
                if (processedClips > 0) {
                    avgConfidence3d /= processedClips;
                }

                boolean decision3d = ratio3d >= BLOCK_RATIO_3D;

                // =========================================
                // FUSION
                // =========================================
                float fusedRatioScore = (WEIGHT_2D * ratio2d) + (WEIGHT_3D * ratio3d);
                float fusedMeanScore = (WEIGHT_2D * avgConfidence2d) + (WEIGHT_3D * avgConfidence3d);
                final boolean isViolent = fusedRatioScore >= FUSION_THRESHOLD;

                final float finalConfidence = fusedMeanScore;
                final float finalViolentRatio = fusedRatioScore;

                Log.d(TAG, "\n========== ANALYSIS COMPLETE ==========");
                Log.d(TAG, "----- 3D RESULT -----");
                Log.d(TAG, "3D Processed clips: " + processedClips);
                Log.d(TAG, "3D Violent clips: " + violentClips);
                Log.d(TAG, "3D Non-violent clips: " + nonViolentClips);
                Log.d(TAG, String.format("3D Violent ratio: %.2f", ratio3d));
                Log.d(TAG, String.format("3D Avg confidence: %.4f", avgConfidence3d));
                Log.d(TAG, "3D Decision: " + (decision3d ? "BLOCK-CONTENT 🚫" : "SAFE-CONTENT ✅"));

                Log.d(TAG, "----- FUSION RESULT -----");
                Log.d(TAG, String.format("Fused ratio score: %.4f", fusedRatioScore));
                Log.d(TAG, String.format("Fused mean score: %.4f", fusedMeanScore));
                Log.d(TAG, "FUSION_THRESHOLD: " + FUSION_THRESHOLD);
                Log.d(TAG, "FINAL DECISION: " + (isViolent ? "BLOCK-CONTENT 🚫" : "SAFE-CONTENT ✅"));
                Log.d(TAG, "=========================================");

                mainHandler.post(() -> {
                    callback.onResult(isViolent, finalConfidence, finalViolentRatio);
                    isAnalyzing.set(false);
                });

            } catch (Exception e) {
                Log.e(TAG, "Error during video analysis: " + e.getMessage(), e);
                mainHandler.post(() -> {
                    callback.onError(e.getMessage());
                    isAnalyzing.set(false);
                });
            } finally {
                if (retriever != null) {
                    try {
                        retriever.release();
                    } catch (Exception e) {
                        Log.e(TAG, "Error releasing retriever: " + e.getMessage(), e);
                    }
                }
            }
        });
    }

    private Bitmap convertToRGB(Bitmap bitmap) {
        return bitmap.copy(Bitmap.Config.ARGB_8888, false);
    }

    // Quick 2D-only analysis for ultra-fast screening
    public void quickAnalyze(String videoPath, DetectionCallback callback) {
        if (!isInitialized || tflite == null) {
            callback.onError("Violence detector not initialized");
            return;
        }

        executorService.execute(() -> {
            MediaMetadataRetriever retriever = null;
            try {
                retriever = new MediaMetadataRetriever();
                retriever.setDataSource(videoPath);

                Bitmap frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);

                if (frame != null) {
                    Bitmap rgbFrame = convertToRGB(frame);
                    float confidence = analyzeFrame(rgbFrame);

                    rgbFrame.recycle();
                    frame.recycle();

                    boolean isViolent = confidence >= VIOLENCE_THRESHOLD;

                    Log.d(TAG, "Quick analysis - confidence: " + confidence + ", isViolent: " + isViolent);

                    float finalConfidence = confidence;
                    mainHandler.post(() -> callback.onResult(
                            isViolent,
                            finalConfidence,
                            isViolent ? 1.0f : 0.0f
                    ));
                } else {
                    mainHandler.post(() -> callback.onError("Could not extract frame"));
                }

            } catch (Exception e) {
                Log.e(TAG, "Error in quick analysis: " + e.getMessage(), e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            } finally {
                if (retriever != null) {
                    try {
                        retriever.release();
                    } catch (Exception e) {
                        Log.e(TAG, "Error releasing retriever: " + e.getMessage(), e);
                    }
                }
            }
        });
    }

    public void cancelAnalysis() {
        isAnalyzing.set(false);
    }

    public boolean isInitialized() {
        return isInitialized;
    }

    public boolean isAnalyzing() {
        return isAnalyzing.get();
    }

    public void close() {
        if (tflite != null) {
            try {
                tflite.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing 2D interpreter: " + e.getMessage(), e);
            }
            tflite = null;
        }

        if (tflite3d != null) {
            try {
                tflite3d.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing 3D interpreter: " + e.getMessage(), e);
            }
            tflite3d = null;
        }

        if (executorService != null) {
            executorService.shutdown();
        }

        isInitialized = false;
        isAnalyzing.set(false);
        Log.d(TAG, "Violence detector closed");
    }
}