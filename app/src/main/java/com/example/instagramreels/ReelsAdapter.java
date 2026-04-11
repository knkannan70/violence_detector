package com.example.instagramreels;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.StyledPlayerView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ReelsAdapter extends RecyclerView.Adapter<ReelsAdapter.ReelViewHolder> {
    private static final String TAG = "ReelsAdapter";

    private Context context;
    private List<ReelItem> reelItems;
    private Map<Integer, ExoPlayer> playerMap;
    private int currentPlayingPosition = -1;
    private ViolenceDetector violenceDetector;
    private boolean violenceBlockEnabled;

    public ReelsAdapter(Context context, List<ReelItem> reelItems, boolean blockViolence) {
        Log.d(TAG, "Constructor started");

        try {
            this.context = context;
            this.reelItems = reelItems != null ? reelItems : new ArrayList<>();
            this.playerMap = new HashMap<>();

            Log.d(TAG, "reelItems size: " + this.reelItems.size());

            // Initialize violence detector with error handling
            try {
                Log.d(TAG, "Initializing ViolenceDetector");
                this.violenceDetector = new ViolenceDetector(context);
                this.violenceDetector.initialize();

                if (this.violenceDetector != null && this.violenceDetector.isInitialized()) {
                    Log.d(TAG, "✅ Violence detector initialized successfully");
                } else {
                    Log.e(TAG, "❌ Violence detector failed to initialize");
                    this.violenceDetector = null;
                }
            } catch (Exception e) {
                Log.e(TAG, "❌ Error initializing violence detector", e);
                this.violenceDetector = null;
            }

            this.violenceBlockEnabled = blockViolence;
            Log.d(TAG, "violenceBlockEnabled: " + violenceBlockEnabled);

        } catch (Exception e) {
            Log.e(TAG, "FATAL ERROR in constructor", e);
            this.violenceDetector = null;
            this.playerMap = new HashMap<>();
        }

        Log.d(TAG, "Constructor completed");
    }

    @NonNull
    @Override
    public ReelViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_reel, parent, false);
        return new ReelViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ReelViewHolder holder, int position) {
        try {
            ReelItem reel = reelItems.get(position);

            // Check if violence blocking is enabled and detector is available
            if (violenceBlockEnabled && violenceDetector != null && violenceDetector.isInitialized()) {
                // If video hasn't been analyzed yet
                if (!reel.isAnalyzed()) {
                    Log.d(TAG, "Video at position " + position + " not analyzed yet, showing loading");
                    holder.showLoading();
                    analyzeVideoForViolence(reel, holder, position);
                    return;
                }
                // If video was analyzed and found to be violent
                else if (reel.isBlocked()) {
                    Log.d(TAG, "Video at position " + position + " is BLOCKED (confidence: " +
                            String.format("%.2f", reel.getViolenceConfidence()) +
                            ", ratio: " + String.format("%.2f", reel.getViolentRatio()) + ")");
                    holder.showBlockedMessage(reel.getViolenceConfidence(), reel.getViolentRatio());
                    return;
                }
                // Video was analyzed and found safe
                else {
                    Log.d(TAG, "Video at position " + position + " is SAFE (confidence: " +
                            String.format("%.2f", reel.getViolenceConfidence()) + ")");
                }
            }

            // If violence blocking is disabled OR video is safe, bind normally
            holder.hideBlockedMessage();
            holder.bind(reel, position);

        } catch (Exception e) {
            Log.e(TAG, "Error in onBindViewHolder at position " + position, e);
        }
    }

    @Override
    public int getItemCount() {
        return reelItems != null ? reelItems.size() : 0;
    }

    @Override
    public void onViewRecycled(@NonNull ReelViewHolder holder) {
        super.onViewRecycled(holder);
        holder.releasePlayer();

        // Cancel any ongoing analysis for this position
        if (holder.currentPosition != -1 && violenceDetector != null) {
            violenceDetector.cancelAnalysis();
        }
    }

    public void playVideoAtPosition(int position) {
        try {
            if (currentPlayingPosition != -1 && currentPlayingPosition != position) {
                ExoPlayer previousPlayer = playerMap.get(currentPlayingPosition);
                if (previousPlayer != null) {
                    previousPlayer.setPlayWhenReady(false);
                }
            }

            currentPlayingPosition = position;
            ExoPlayer player = playerMap.get(position);
            if (player != null) {
                player.setPlayWhenReady(true);
                Log.d(TAG, "Playing video at position: " + position);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error playing video at position " + position, e);
        }
    }

    public void pauseAllVideos() {
        try {
            for (ExoPlayer player : playerMap.values()) {
                if (player != null) {
                    player.setPlayWhenReady(false);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error pausing videos", e);
        }
    }

    public void releaseAllPlayers() {
        try {
            for (ExoPlayer player : playerMap.values()) {
                if (player != null) {
                    player.release();
                }
            }
            playerMap.clear();
            currentPlayingPosition = -1;

            // Close violence detector
            if (violenceDetector != null) {
                violenceDetector.close();
            }

            Log.d(TAG, "All players released");
        } catch (Exception e) {
            Log.e(TAG, "Error releasing players", e);
        }
    }

    public void updateViolenceBlockStatus(boolean enabled) {
        try {
            this.violenceBlockEnabled = enabled;
            Log.d(TAG, "Violence block status updated: " + violenceBlockEnabled);
            notifyDataSetChanged();
        } catch (Exception e) {
            Log.e(TAG, "Error updating violence block status", e);
        }
    }

    public void updateReels(List<ReelItem> newReels) {
        this.reelItems = newReels;
        notifyDataSetChanged();
    }

    private void analyzeVideoForViolence(ReelItem reel, ReelViewHolder holder, int position) {
        downloadAndAnalyzeVideo(reel, holder, position);
    }

    private void downloadAndAnalyzeVideo(ReelItem reel, ReelViewHolder holder, int position) {
        new Thread(() -> {
            try {
                Log.d(TAG, "Downloading video for analysis at position: " + position);

                URL url = new URL(reel.getVideoUrl());
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setDoInput(true);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(30000);
                connection.connect();

                InputStream input = connection.getInputStream();
                File cacheDir = context.getCacheDir();
                File videoFile = new File(cacheDir, "video_" + position + "_" + System.currentTimeMillis() + ".mp4");
                FileOutputStream output = new FileOutputStream(videoFile);

                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                }
                output.close();
                input.close();

                reel.setVideoPath(videoFile.getAbsolutePath());
                Log.d(TAG, "Video downloaded to: " + videoFile.getAbsolutePath());

                // Analyze the video if detector is available
                if (violenceDetector != null && violenceDetector.isInitialized()) {
                    Log.d(TAG, "Starting violence analysis for video at position: " + position);

                    // First do quick analysis for instant feedback
                    violenceDetector.quickAnalyze(videoFile.getAbsolutePath(),
                            new ViolenceDetector.DetectionCallback() {
                                @Override
                                public void onReadyToShow() {

                                }

                                @Override
                                public void onResult(boolean isViolent, float confidence, float violentRatio) {
                                    if (isViolent) {
                                        // Block immediately based on first frame
                                        ((android.app.Activity) context).runOnUiThread(() -> {
                                            try {
                                                reel.setViolenceConfidence(confidence);
                                                reel.setViolentRatio(violentRatio);
                                                reel.setBlocked(true);
                                                reel.setAnalyzed(true);

                                                Log.d(TAG, "QUICK ANALYSIS - Video " + position +
                                                        " BLOCKED (confidence: " + String.format("%.4f", confidence) + ")");

                                                holder.showBlockedMessage(confidence, violentRatio);
                                                videoFile.delete(); // Delete violent video
                                                notifyItemChanged(position);
                                            } catch (Exception e) {
                                                Log.e(TAG, "Error in quick analysis result", e);
                                            }
                                        });
                                    } else {
                                        // Quick analysis says safe, do full analysis to be sure
                                        violenceDetector.analyzeVideo(videoFile.getAbsolutePath(),
                                                new ViolenceDetector.DetectionCallback() {
                                                    @Override
                                                    public void onReadyToShow() {

                                                    }

                                                    @Override
                                                    public void onResult(boolean isViolent, float confidence, float violentRatio) {
                                                        ((android.app.Activity) context).runOnUiThread(() -> {
                                                            try {
                                                                reel.setViolenceConfidence(confidence);
                                                                reel.setViolentRatio(violentRatio);
                                                                reel.setBlocked(isViolent);
                                                                reel.setAnalyzed(true);

                                                                Log.d(TAG, "FULL ANALYSIS - Video " + position +
                                                                        ": isViolent=" + isViolent +
                                                                        ", confidence=" + String.format("%.4f", confidence) +
                                                                        ", ratio=" + String.format("%.2f", violentRatio));

                                                                if (isViolent && violenceBlockEnabled) {
                                                                    holder.showBlockedMessage(confidence, violentRatio);
                                                                    videoFile.delete(); // Delete violent video
                                                                    Log.d(TAG, "Violent video deleted: " + videoFile.getPath());
                                                                } else {
                                                                    holder.hideBlockedMessage();
                                                                    holder.bind(reel, position);
                                                                }

                                                                notifyItemChanged(position);
                                                            } catch (Exception e) {
                                                                Log.e(TAG, "Error in analysis result", e);
                                                            }
                                                        });
                                                    }

                                                    @Override
                                                    public void onError(String error) {
                                                        ((android.app.Activity) context).runOnUiThread(() -> {
                                                            Log.e(TAG, "Analysis error for position " + position + ": " + error);
                                                            reel.setAnalyzed(true);
                                                            reel.setBlocked(false);
                                                            holder.hideBlockedMessage();
                                                            holder.bind(reel, position);
                                                            notifyItemChanged(position);
                                                        });
                                                    }
                                                });
                                    }
                                }

                                @Override
                                public void onError(String error) {
                                    ((android.app.Activity) context).runOnUiThread(() -> {
                                        Log.e(TAG, "Quick analysis error for position " + position + ": " + error);
                                        reel.setAnalyzed(true);
                                        reel.setBlocked(false);
                                        holder.hideBlockedMessage();
                                        holder.bind(reel, position);
                                        notifyItemChanged(position);
                                    });
                                }
                            });
                } else {
                    // Detector not available, just play the video
                    Log.w(TAG, "Violence detector not available, playing video directly");
                    ((android.app.Activity) context).runOnUiThread(() -> {
                        reel.setAnalyzed(true);
                        reel.setBlocked(false);
                        holder.hideBlockedMessage();
                        holder.bind(reel, position);
                        notifyItemChanged(position);
                    });
                }

            } catch (Exception e) {
                Log.e(TAG, "Download/analysis error at position " + position + ": " + e.getMessage(), e);
                ((android.app.Activity) context).runOnUiThread(() -> {
                    reel.setAnalyzed(true);
                    reel.setBlocked(false);
                    holder.hideBlockedMessage();
                    holder.bind(reel, position);
                    notifyItemChanged(position);
                });
            }
        }).start();
    }

    public class ReelViewHolder extends RecyclerView.ViewHolder {
        StyledPlayerView playerView;
        TextView usernameText, captionText, likesCount, commentsCount, sharesCount;
        ImageView profileImage, likeButton, commentButton, shareButton, moreButton;
        ImageView likeIcon;
        ProgressBar videoProgressBar;
        ImageView violenceBlockImage;
        ExoPlayer player;
        int currentPosition = -1;
        private long lastClickTime = 0;

        public ReelViewHolder(@NonNull View itemView) {
            super(itemView);

            try {
                playerView = itemView.findViewById(R.id.playerView);
                usernameText = itemView.findViewById(R.id.usernameText);
                captionText = itemView.findViewById(R.id.captionText);
                likesCount = itemView.findViewById(R.id.likesCount);
                commentsCount = itemView.findViewById(R.id.commentsCount);
                sharesCount = itemView.findViewById(R.id.sharesCount);
                profileImage = itemView.findViewById(R.id.profileImage);
                likeButton = itemView.findViewById(R.id.likeButton);
                commentButton = itemView.findViewById(R.id.commentButton);
                shareButton = itemView.findViewById(R.id.shareButton);
                moreButton = itemView.findViewById(R.id.moreButton);
                likeIcon = itemView.findViewById(R.id.likeIcon);
                videoProgressBar = itemView.findViewById(R.id.videoProgressBar);
                violenceBlockImage = itemView.findViewById(R.id.violenceBlockImage);
            } catch (Exception e) {
                Log.e(TAG, "Error initializing ViewHolder", e);
            }
        }

        public void bind(ReelItem reel, int position) {
            try {
                this.currentPosition = position;

                if (usernameText != null) usernameText.setText(reel.getUsername());
                if (captionText != null) captionText.setText(reel.getCaption());
                if (likesCount != null) likesCount.setText(formatCount(reel.getLikes()));
                if (commentsCount != null) commentsCount.setText(formatCount(reel.getComments()));
                if (sharesCount != null) sharesCount.setText(formatCount(reel.getShares()));

                updateLikeButton(reel);
                loadProfileImage();

                // Hide violence block image if visible
                if (violenceBlockImage != null) {
                    violenceBlockImage.setVisibility(View.GONE);
                }

                // Hide progress bar
                if (videoProgressBar != null) {
                    videoProgressBar.setVisibility(View.GONE);
                }

                // Setup player with video URL
                setupPlayer(reel.getVideoUrl(), position);
                setupListeners(reel);
                setupDoubleTapListener(reel);

                Log.d(TAG, "Bound view at position: " + position);

            } catch (Exception e) {
                Log.e(TAG, "Error in bind at position " + position, e);
            }
        }

        private void updateLikeButton(ReelItem reel) {
            if (likeButton != null) {
                likeButton.setImageResource(reel.isLiked() ?
                        R.drawable.ic_heart : R.drawable.ic_like_outline);
            }
        }

        private void loadProfileImage() {
            if (profileImage != null) {
                try {
                    Glide.with(context)
                            .load(R.drawable.ic_profile_placeholder)
                            .circleCrop()
                            .placeholder(R.drawable.ic_profile_placeholder)
                            .error(R.drawable.ic_profile_placeholder)
                            .into(profileImage);
                } catch (Exception e) {
                    Log.e(TAG, "Error loading profile image", e);
                }
            }
        }

        private void setupPlayer(String videoUrl, int position) {
            try {
                if (player != null) {
                    player.release();
                    player = null;
                }

                if (playerView == null) {
                    Log.e(TAG, "playerView is null at position: " + position);
                    return;
                }

                player = new ExoPlayer.Builder(context).build();
                playerView.setPlayer(player);
                playerView.setUseController(false);
                playerView.setKeepScreenOn(true);

                MediaItem mediaItem = MediaItem.fromUri(videoUrl);
                player.setMediaItem(mediaItem);

                player.addListener(new Player.Listener() {
                    @Override
                    public void onPlaybackStateChanged(int playbackState) {
                        if (playbackState == Player.STATE_ENDED) {
                            if (player != null) {
                                player.seekTo(0);
                                player.setPlayWhenReady(true);
                            }
                        } else if (playbackState == Player.STATE_BUFFERING) {
                            if (videoProgressBar != null) {
                                videoProgressBar.setVisibility(View.VISIBLE);
                            }
                        } else if (playbackState == Player.STATE_READY) {
                            if (videoProgressBar != null) {
                                videoProgressBar.setVisibility(View.GONE);
                            }
                        }
                    }

                    @Override
                    public void onPlayerError(PlaybackException error) {
                        Log.e(TAG, "Player error at position " + position + ": " + error.getMessage());
                        if (videoProgressBar != null) {
                            videoProgressBar.setVisibility(View.GONE);
                        }
                    }
                });

                player.prepare();
                playerMap.put(position, player);

                Log.d(TAG, "Player setup for position: " + position);

            } catch (Exception e) {
                Log.e(TAG, "Error setting up player at position " + position, e);
            }
        }

        private void setupListeners(ReelItem reel) {
            if (likeButton != null) {
                likeButton.setOnClickListener(v -> toggleLike(reel));
            }

            if (commentButton != null) {
                commentButton.setOnClickListener(v ->
                        Toast.makeText(context, "Comments coming soon!", Toast.LENGTH_SHORT).show());
            }

            if (shareButton != null) {
                shareButton.setOnClickListener(v ->
                        Toast.makeText(context, "Share: " + reel.getCaption(), Toast.LENGTH_SHORT).show());
            }

            if (moreButton != null) {
                moreButton.setOnClickListener(v ->
                        Toast.makeText(context, "More options", Toast.LENGTH_SHORT).show());
            }
        }

        private void setupDoubleTapListener(ReelItem reel) {
            if (playerView != null) {
                playerView.setOnClickListener(v -> {
                    long clickTime = System.currentTimeMillis();
                    if (clickTime - lastClickTime < 300) {
                        performDoubleTapLike(reel);
                        lastClickTime = 0;
                    } else {
                        togglePlayPause();
                        lastClickTime = clickTime;
                    }
                });
            }
        }

        private void togglePlayPause() {
            if (player != null) {
                player.setPlayWhenReady(!player.isPlaying());
            }
        }

        private void toggleLike(ReelItem reel) {
            try {
                boolean isLiked = !reel.isLiked();
                reel.setLiked(isLiked);
                reel.setLikes(reel.getLikes() + (isLiked ? 1 : -1));

                if (likesCount != null) {
                    likesCount.setText(formatCount(reel.getLikes()));
                }
                updateLikeButton(reel);

                if (isLiked) {
                    showLikeAnimation();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error toggling like", e);
            }
        }

        private void showLikeAnimation() {
            if (likeIcon == null) return;

            likeIcon.setVisibility(View.VISIBLE);
            likeIcon.setAlpha(1f);
            likeIcon.setScaleX(0f);
            likeIcon.setScaleY(0f);

            likeIcon.animate()
                    .scaleX(1.2f).scaleY(1.2f)
                    .setDuration(200)
                    .withEndAction(() -> {
                        likeIcon.animate()
                                .scaleX(1f).scaleY(1f)
                                .alpha(0f)
                                .setDuration(300)
                                .withEndAction(() -> likeIcon.setVisibility(View.GONE))
                                .start();
                    })
                    .start();
        }

        private void performDoubleTapLike(ReelItem reel) {
            if (!reel.isLiked()) {
                toggleLike(reel);
            }
        }

        public void playPlayer() {
            if (player != null) {
                player.setPlayWhenReady(true);
            }
        }

        public void pausePlayer() {
            if (player != null) {
                player.setPlayWhenReady(false);
            }
        }

        public void releasePlayer() {
            if (player != null) {
                player.release();
                player = null;
                if (currentPosition != -1) {
                    playerMap.remove(currentPosition);
                }
                Log.d(TAG, "Player released for position: " + currentPosition);
            }
        }

        public void showLoading() {
            try {
                if (playerView != null) {
                    playerView.setVisibility(View.GONE);
                }

                if (violenceBlockImage != null) {
                    violenceBlockImage.setVisibility(View.GONE);
                }

                if (videoProgressBar != null) {
                    videoProgressBar.setVisibility(View.VISIBLE);
                }

                FrameLayout parent = (FrameLayout) itemView;
                if (parent.findViewWithTag("loading") == null) {
                    LinearLayout loadingLayout = new LinearLayout(context);
                    loadingLayout.setTag("loading");
                    loadingLayout.setOrientation(LinearLayout.VERTICAL);
                    loadingLayout.setGravity(android.view.Gravity.CENTER);
                    loadingLayout.setBackgroundColor(0xFF000000);

                    ProgressBar progressBar = new ProgressBar(context);
                    loadingLayout.addView(progressBar);

                    TextView textView = new TextView(context);
                    textView.setText("Analyzing content...");
                    textView.setTextColor(0xFFFFFFFF);
                    textView.setTextSize(16);
                    textView.setPadding(0, 16, 0, 0);
                    loadingLayout.addView(textView);

                    parent.addView(loadingLayout, new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                    ));

                    Log.d(TAG, "Loading overlay shown");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error showing loading", e);
            }
        }

        public void showBlockedMessage(float confidence, float violentRatio) {
            try {
                if (playerView != null) {
                    playerView.setVisibility(View.GONE);
                }

                if (violenceBlockImage != null) {
                    violenceBlockImage.setVisibility(View.VISIBLE);
                }

                if (videoProgressBar != null) {
                    videoProgressBar.setVisibility(View.GONE);
                }

                hideBlockedMessage(); // Remove any existing overlays

                FrameLayout parent = (FrameLayout) itemView;
                LinearLayout blockedLayout = new LinearLayout(context);
                blockedLayout.setTag("blocked");
                blockedLayout.setOrientation(LinearLayout.VERTICAL);
                blockedLayout.setGravity(android.view.Gravity.CENTER);
                blockedLayout.setBackgroundColor(0xFF000000);
                blockedLayout.setPadding(32, 32, 32, 32);

                // Blocked icon
                TextView icon = new TextView(context);
                icon.setText("⚠️");
                icon.setTextSize(60);
                icon.setTextColor(0xFFFF4444);
                blockedLayout.addView(icon);

                // Title
                TextView title = new TextView(context);
                title.setText("CONTENT BLOCKED");
                title.setTextColor(0xFFFF4444);
                title.setTextSize(28);
                title.setTypeface(null, android.graphics.Typeface.BOLD);
                title.setPadding(0, 16, 0, 8);
                blockedLayout.addView(title);

                // Message
                TextView message = new TextView(context);
                message.setText("Violent content detected");
                message.setTextColor(0xFFFFFFFF);
                message.setTextSize(18);
                blockedLayout.addView(message);

                // Statistics
                LinearLayout statsLayout = new LinearLayout(context);
                statsLayout.setOrientation(LinearLayout.HORIZONTAL);
                statsLayout.setGravity(android.view.Gravity.CENTER);
                statsLayout.setPadding(0, 24, 0, 0);

                // Confidence
                LinearLayout confidenceLayout = new LinearLayout(context);
                confidenceLayout.setOrientation(LinearLayout.VERTICAL);
                confidenceLayout.setPadding(16, 0, 16, 0);

                TextView confidenceLabel = new TextView(context);
                confidenceLabel.setText("Confidence");
                confidenceLabel.setTextColor(0xFFAAAAAA);
                confidenceLabel.setTextSize(12);
                confidenceLayout.addView(confidenceLabel);

                TextView confidenceValue = new TextView(context);
                confidenceValue.setText(String.format("%.1f%%", confidence * 100));
                confidenceValue.setTextColor(0xFFFFFFFF);
                confidenceValue.setTextSize(18);
                confidenceValue.setTypeface(null, android.graphics.Typeface.BOLD);
                confidenceLayout.addView(confidenceValue);

                statsLayout.addView(confidenceLayout);

                // Ratio
                LinearLayout ratioLayout = new LinearLayout(context);
                ratioLayout.setOrientation(LinearLayout.VERTICAL);
                ratioLayout.setPadding(16, 0, 16, 0);

                TextView ratioLabel = new TextView(context);
                ratioLabel.setText("Violent Frames");
                ratioLabel.setTextColor(0xFFAAAAAA);
                ratioLabel.setTextSize(12);
                ratioLayout.addView(ratioLabel);

                TextView ratioValue = new TextView(context);
                ratioValue.setText(String.format("%.0f%%", violentRatio * 100));
                ratioValue.setTextColor(0xFFFFFFFF);
                ratioValue.setTextSize(18);
                ratioValue.setTypeface(null, android.graphics.Typeface.BOLD);
                ratioLayout.addView(ratioValue);

                statsLayout.addView(ratioLayout);

                blockedLayout.addView(statsLayout);

                // Instruction
                TextView instruction = new TextView(context);
                instruction.setText("Scroll down for more videos");
                instruction.setTextColor(0xFF888888);
                instruction.setTextSize(14);
                instruction.setPadding(0, 32, 0, 0);
                blockedLayout.addView(instruction);

                parent.addView(blockedLayout, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                ));

                Log.d(TAG, "Blocked overlay shown - confidence: " + confidence + ", ratio: " + violentRatio);

            } catch (Exception e) {
                Log.e(TAG, "Error showing blocked message", e);
            }
        }

        public void hideBlockedMessage() {
            try {
                FrameLayout parent = (FrameLayout) itemView;
                View blockedView = parent.findViewWithTag("blocked");
                if (blockedView != null) {
                    parent.removeView(blockedView);
                }

                View loadingView = parent.findViewWithTag("loading");
                if (loadingView != null) {
                    parent.removeView(loadingView);
                }

                if (violenceBlockImage != null) {
                    violenceBlockImage.setVisibility(View.GONE);
                }

                if (playerView != null) {
                    playerView.setVisibility(View.VISIBLE);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error hiding blocked message", e);
            }
        }
    }

    private String formatCount(int count) {
        if (count < 1000) return String.valueOf(count);
        if (count < 1000000) return String.format("%.1fK", count / 1000.0);
        return String.format("%.1fM", count / 1000000.0);
    }
}