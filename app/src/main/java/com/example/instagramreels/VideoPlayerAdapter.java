package com.example.instagramreels;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.ui.StyledPlayerView;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.Util;

import java.io.File;
import java.util.List;

public class VideoPlayerAdapter extends RecyclerView.Adapter<VideoPlayerAdapter.VideoViewHolder> {
    private static final String TAG = "VideoPlayerAdapter";

    private Context context;
    private List<VideoItem> videoItems;
    private ViolenceDetector violenceDetector;
    private ExoPlayer currentPlayer;
    private int currentPosition = -1;

    public VideoPlayerAdapter(Context context, List<VideoItem> videoItems, ViolenceDetector violenceDetector) {
        this.context = context;
        this.videoItems = videoItems;
        this.violenceDetector = violenceDetector;
    }

    @NonNull
    @Override
    public VideoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_video_player, parent, false);
        return new VideoViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VideoViewHolder holder, int position) {
        VideoItem videoItem = videoItems.get(position);
        holder.bind(videoItem, position);
    }

    @Override
    public int getItemCount() {
        return videoItems.size();
    }

    public void pauseCurrentPlayer() {
        if (currentPlayer != null) {
            currentPlayer.pause();
        }
    }

    public void releaseCurrentPlayer() {
        if (currentPlayer != null) {
            currentPlayer.release();
            currentPlayer = null;
        }
    }

    public void setCurrentPosition(int position) {
        // ── CHANGE 1: stop the previous video the moment user scrolls away ──
        if (currentPlayer != null) {
            currentPlayer.setPlayWhenReady(false);
        }
        this.currentPosition = position;
        notifyItemChanged(position);
    }

    class VideoViewHolder extends RecyclerView.ViewHolder {
        private StyledPlayerView playerView;
        private ImageView thumbnailView;
        private FrameLayout blockedOverlay;
        private TextView blockedText;
        private ProgressBar loadingIndicator;
        private ExoPlayer player;
        private boolean isBlocked = false;

        public VideoViewHolder(@NonNull View itemView) {
            super(itemView);
            playerView = itemView.findViewById(R.id.player_view);
            thumbnailView = itemView.findViewById(R.id.thumbnail_view);
            blockedOverlay = itemView.findViewById(R.id.blocked_overlay);
            blockedText = itemView.findViewById(R.id.blocked_text);
            loadingIndicator = itemView.findViewById(R.id.loading_indicator);
        }

        public void bind(VideoItem videoItem, int position) {
            // Show loading indicator
            loadingIndicator.setVisibility(View.VISIBLE);
            playerView.setVisibility(View.GONE);
            blockedOverlay.setVisibility(View.GONE);

            // Load thumbnail
            Glide.with(context)
                    .load(videoItem.getThumbnailUrl())
                    .into(thumbnailView);

            // Check if video is already blocked
            if (videoItem.isBlocked()) {
                showBlockedScreen();
                loadingIndicator.setVisibility(View.GONE);
                return;
            }

            // Check if we should analyze this video
            if (videoItem.needsAnalysis() && !videoItem.isAnalyzing()) {
                videoItem.setAnalyzing(true);
                analyzeVideo(videoItem, position);
            } else if (!videoItem.isBlocked() && !videoItem.needsAnalysis()) {
                // Video is safe, prepare player
                preparePlayer(videoItem, position);
            }
        }

        private void analyzeVideo(VideoItem videoItem, int position) {
            String videoPath = videoItem.getVideoUrl();

            // First do quick analysis for instant blocking
            violenceDetector.quickAnalyze(videoPath, new ViolenceDetector.DetectionCallback() {
                @Override
                public void onReadyToShow() {
                    // quickAnalyze is a single-frame probe — no early show needed here
                }

                @Override
                public void onResult(boolean isViolent, float confidence, float violentRatio) {
                    if (isViolent) {
                        // Block immediately based on quick analysis
                        videoItem.setBlocked(true);
                        videoItem.setAnalyzing(false);
                        videoItem.setNeedsAnalysis(false);

                        // Show blocked screen
                        if (getAdapterPosition() == position) {
                            showBlockedScreen();
                            loadingIndicator.setVisibility(View.GONE);
                        }

                        Log.d(TAG, "Video " + position + " BLOCKED immediately (confidence: " + confidence + ")");
                    } else {
                        // Quick analysis says safe — start full analysis.
                        violenceDetector.analyzeVideo(videoPath, new ViolenceDetector.DetectionCallback() {

                            // ── CHANGE 2: show & play the video instantly while
                            //             full analysis runs in the background ──
                            @Override
                            public void onReadyToShow() {
                                if (getAdapterPosition() == position) {
                                    preparePlayer(videoItem, position);
                                }
                            }

                            // ── CHANGE 3: if full analysis decides violent,
                            //             stop the already-playing player and block ──
                            @Override
                            public void onResult(boolean isViolent, float confidence, float violentRatio) {
                                videoItem.setBlocked(isViolent);
                                videoItem.setAnalyzing(false);
                                videoItem.setNeedsAnalysis(false);

                                if (getAdapterPosition() == position) {
                                    if (isViolent) {
                                        // Stop the player that onReadyToShow already started
                                        if (player != null) {
                                            player.setPlayWhenReady(false);
                                            player.release();
                                            player = null;
                                        }
                                        showBlockedScreen();
                                        Log.d(TAG, "Video " + position + " BLOCKED after full analysis");
                                    } else {
                                        // Already playing — nothing to do
                                        Log.d(TAG, "Video " + position + " SAFE");
                                    }
                                    loadingIndicator.setVisibility(View.GONE);
                                }
                            }

                            @Override
                            public void onError(String error) {
                                Log.e(TAG, "Analysis error for video " + position + ": " + error);
                                videoItem.setAnalyzing(false);
                                videoItem.setNeedsAnalysis(false);

                                // On error, treat as safe (or you could block to be safe)
                                if (getAdapterPosition() == position) {
                                    preparePlayer(videoItem, position);
                                    loadingIndicator.setVisibility(View.GONE);
                                }
                            }
                        });
                    }
                }

                @Override
                public void onError(String error) {
                    Log.e(TAG, "Quick analysis error for video " + position + ": " + error);
                    videoItem.setAnalyzing(false);
                    videoItem.setNeedsAnalysis(false);

                    if (getAdapterPosition() == position) {
                        preparePlayer(videoItem, position);
                        loadingIndicator.setVisibility(View.GONE);
                    }
                }
            });
        }

        private void preparePlayer(VideoItem videoItem, int position) {
            if (getAdapterPosition() != position) return;

            try {
                // Initialize player
                player = new ExoPlayer.Builder(context).build();
                playerView.setPlayer(player);

                // Set up media source
                DataSource.Factory dataSourceFactory = new DefaultDataSourceFactory(
                        context,
                        Util.getUserAgent(context, "InstagramReels")
                );

                MediaItem mediaItem = MediaItem.fromUri(Uri.fromFile(new File(videoItem.getVideoUrl())));
                ProgressiveMediaSource mediaSource = new ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(mediaItem);

                player.setMediaSource(mediaSource);
                player.prepare();

                // Set this as current player
                if (currentPosition == position) {
                    currentPlayer = player;
                }

                // Hide loading, show player
                playerView.setVisibility(View.VISIBLE);
                thumbnailView.setVisibility(View.GONE);
                loadingIndicator.setVisibility(View.GONE);

                // Auto-play when ready
                player.addListener(new Player.Listener() {
                    @Override
                    public void onPlaybackStateChanged(int playbackState) {
                        if (playbackState == Player.STATE_READY) {
                            if (currentPosition == position) {
                                player.setPlayWhenReady(true);
                            }
                        }
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Error preparing player: " + e.getMessage());
                loadingIndicator.setVisibility(View.GONE);
            }
        }

        private void showBlockedScreen() {
            playerView.setVisibility(View.GONE);
            thumbnailView.setVisibility(View.GONE);
            blockedOverlay.setVisibility(View.VISIBLE);
            blockedText.setText("⛔ VIOLENT CONTENT BLOCKED");
            loadingIndicator.setVisibility(View.GONE);
            isBlocked = true;

            Log.d(TAG, "Blocked screen shown for video");
        }

        public void pause() {
            if (player != null) {
                player.setPlayWhenReady(false);
            }
        }

        public void play() {
            if (player != null && !isBlocked) {
                player.setPlayWhenReady(true);
            }
        }

        public void release() {
            if (player != null) {
                player.release();
                player = null;
            }
        }
    }
}