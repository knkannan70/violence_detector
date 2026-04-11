package com.example.instagramreels;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SnapHelper;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private RecyclerView reelsRecyclerView;
    private ReelsAdapter reelsAdapter;
    private List<ReelItem> reelItems;
    private ProgressBar progressBar;
    private TextView errorText;
    private TextView emptyText;
    private Handler handler = new Handler(Looper.getMainLooper());

    // Violence block settings
    private SharedPreferences sharedPreferences;
    private boolean blockViolence = false;

    // Crash and ANR handlers
    private CrashHandler crashHandler;
    private ANRWatchdog anrWatchdog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize crash handlers
        crashHandler = new CrashHandler();
        CrashHandler.setLastActivity(this);

        anrWatchdog = new ANRWatchdog();
        anrWatchdog.start();

        setContentView(R.layout.activity_main);

        // Initialize views
        initViews();

        // Setup toolbar
        setupToolbar();

        // Load settings
        sharedPreferences = getSharedPreferences("ViolenceBlockPrefs", MODE_PRIVATE);
        blockViolence = sharedPreferences.getBoolean("violence_block_enabled", false);

        // Initialize reel list
        reelItems = new ArrayList<>();

        // Setup RecyclerView
        setupRecyclerView();

        // Load videos
        loadVideosFromGitHub();
    }

    private void initViews() {
        reelsRecyclerView = findViewById(R.id.reelsRecyclerView);
        progressBar = findViewById(R.id.progressBar);
        errorText = findViewById(R.id.errorText);
        emptyText = findViewById(R.id.emptyText);

        // Show loading initially
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        if (errorText != null) errorText.setVisibility(View.GONE);
        if (emptyText != null) {
            emptyText.setVisibility(View.GONE);
            emptyText.setText("No videos available.\nPull to refresh or check your connection.");
        }
    }

    private void setupToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
    }

    private void setupRecyclerView() {
        Log.d(TAG, "Setting up RecyclerView");

        try {
            // Create adapter with empty list initially
            reelsAdapter = new ReelsAdapter(this, reelItems, blockViolence);

            // Setup layout manager
            LinearLayoutManager layoutManager = new LinearLayoutManager(
                    this,
                    LinearLayoutManager.VERTICAL,
                    false
            );
            reelsRecyclerView.setLayoutManager(layoutManager);
            reelsRecyclerView.setAdapter(reelsAdapter);

            // Add snap helper for paging effect
            SnapHelper snapHelper = new PagerSnapHelper();
            snapHelper.attachToRecyclerView(reelsRecyclerView);

            // Add scroll listener for video playback
            reelsRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                    super.onScrollStateChanged(recyclerView, newState);

                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        // Play the most visible video when scrolling stops
                        playMostVisibleVideo();
                    } else {
                        // Pause all videos while scrolling
                        if (reelsAdapter != null) {
                            reelsAdapter.pauseAllVideos();
                        }
                    }
                }
            });

            Log.d(TAG, "RecyclerView setup completed");

        } catch (Exception e) {
            Log.e(TAG, "Error setting up RecyclerView", e);
            showError("Failed to setup video player");
        }
    }

    private void playMostVisibleVideo() {
        if (reelsRecyclerView == null || reelsRecyclerView.getLayoutManager() == null || reelItems.isEmpty()) return;

        LinearLayoutManager lm = (LinearLayoutManager) reelsRecyclerView.getLayoutManager();

        // Find the most visible item
        int firstVisible = lm.findFirstVisibleItemPosition();
        int lastVisible = lm.findLastVisibleItemPosition();

        // Calculate which item is most visible
        if (firstVisible != RecyclerView.NO_POSITION && lastVisible != RecyclerView.NO_POSITION) {
            View firstView = lm.findViewByPosition(firstVisible);
            View lastView = lm.findViewByPosition(lastVisible);

            if (firstView != null && lastView != null) {
                int firstVisibleHeight = firstView.getHeight() -
                        (firstView.getTop() < 0 ? Math.abs(firstView.getTop()) : 0);
                int lastVisibleHeight = lastView.getBottom() > reelsRecyclerView.getHeight() ?
                        reelsRecyclerView.getHeight() - lastView.getTop() : lastView.getHeight();

                // Play the one with more visible area
                if (firstVisibleHeight > lastVisibleHeight && firstVisible < reelItems.size()) {
                    reelsAdapter.playVideoAtPosition(firstVisible);
                } else if (lastVisible < reelItems.size()) {
                    reelsAdapter.playVideoAtPosition(lastVisible);
                }
            }
        }
    }

    private void loadVideosFromGitHub() {
        Log.d(TAG, "Loading videos from GitHub");

        // Show loading
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        if (errorText != null) errorText.setVisibility(View.GONE);
        if (emptyText != null) emptyText.setVisibility(View.GONE);

        GithubReelsHelper.fetchReels(new GithubReelsHelper.ReelListener() {
            @Override
            public void onLoaded(List<ReelItem> reels) {
                runOnUiThread(() -> {
                    // Hide loading
                    if (progressBar != null) progressBar.setVisibility(View.GONE);

                    try {
                        if (reels != null && !reels.isEmpty()) {
                            Log.d(TAG, "Loaded " + reels.size() + " reels from GitHub");

                            // Clear and add new reels
                            reelItems.clear();
                            reelItems.addAll(reels);

                            // Update adapter
                            if (reelsAdapter != null) {
                                reelsAdapter.updateReels(reelItems);
                                reelsAdapter.updateViolenceBlockStatus(blockViolence);
                            }

                            // Hide empty text if visible
                            if (emptyText != null) emptyText.setVisibility(View.GONE);

                            // Play first video after a short delay
                            handler.postDelayed(() -> {
                                try {
                                    playMostVisibleVideo();
                                } catch (Exception e) {
                                    Log.e(TAG, "Error playing first video", e);
                                }
                            }, 500);

                        } else {
                            Log.e(TAG, "No reels loaded from GitHub");
                            showEmptyState(getString(R.string.no_videos_github));
                            loadFallbackVideos();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error in onLoaded", e);
                        showError(getString(R.string.error_loading_videos));
                    }
                });
            }

            @Override
            public void onError(String errorMessage) {
                runOnUiThread(() -> {
                    // Hide loading
                    if (progressBar != null) progressBar.setVisibility(View.GONE);

                    Log.e(TAG, "GitHub load error: " + errorMessage);
                    showEmptyState(String.format(getString(R.string.github_load_error), errorMessage) +
                            getString(R.string.trying_fallback));

                    // Load fallback videos on error
                    loadFallbackVideos();
                });
            }
        });
    }

    private void loadFallbackVideos() {
        Log.d(TAG, "Loading fallback videos");

        // Clear existing
        reelItems.clear();

        // Add working fallback videos from reliable sources
        reelItems.add(new ReelItem(
                "https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
                "Big Buck Bunny",
                "@animation_lover",
                "Classic open source animation\n\n#animation #fun"
        ));

        reelItems.add(new ReelItem(
                "https://storage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4",
                "Elephants Dream",
                "@short_film_fan",
                "Beautiful animated short\n\n#animation #art"
        ));

        reelItems.add(new ReelItem(
                "https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4",
                "Bigger Blazes",
                "@action_hub",
                "Action packed sequence\n\n#action #thriller"
        ));

        reelItems.add(new ReelItem(
                "https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4",
                "Bigger Escapes",
                "@adventure_time",
                "Epic escape scenes\n\n#adventure #action"
        ));

        reelItems.add(new ReelItem(
                "https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerFun.mp4",
                "Bigger Fun",
                "@comedy_clips",
                "Funny moments compilation\n\n#comedy #fun"
        ));

        // Update adapter
        if (reelsAdapter != null) {
            reelsAdapter.updateReels(reelItems);
            reelsAdapter.updateViolenceBlockStatus(blockViolence);
        }

        // Hide empty text
        if (emptyText != null) emptyText.setVisibility(View.GONE);

        // Play first video
        handler.postDelayed(this::playMostVisibleVideo, 500);
    }

    private void showError(String message) {
        if (errorText != null) {
            errorText.setVisibility(View.VISIBLE);
            errorText.setText(message);
        }

        // Show empty text if no videos
        if (reelItems.isEmpty() && emptyText != null) {
            emptyText.setVisibility(View.VISIBLE);
            emptyText.setText(R.string.no_videos);
        }
    }

    private void showEmptyState(String message) {
        if (reelItems.isEmpty() && emptyText != null) {
            emptyText.setVisibility(View.VISIBLE);
            emptyText.setText(message);
        }

        if (errorText != null) {
            errorText.setVisibility(View.GONE);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh settings
        blockViolence = sharedPreferences.getBoolean("violence_block_enabled", false);
        if (reelsAdapter != null) {
            reelsAdapter.updateViolenceBlockStatus(blockViolence);
            // Resume playing if we have videos
            if (!reelItems.isEmpty()) {
                handler.postDelayed(this::playMostVisibleVideo, 300);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Pause all videos when activity is paused
        if (reelsAdapter != null) {
            reelsAdapter.pauseAllVideos();
        }
    }

    @Override
    protected void onDestroy() {
        // Clean up resources
        if (reelsAdapter != null) {
            reelsAdapter.releaseAllPlayers();
        }
        handler.removeCallbacksAndMessages(null);

        // Stop ANR watchdog
        if (anrWatchdog != null) {
            anrWatchdog.stopWatchdog();
        }

        super.onDestroy();
    }
}