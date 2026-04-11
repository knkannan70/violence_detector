package com.example.instagramreels;

import android.util.Log;

public class ReelItem {
    private static final String TAG = "ReelItem";

    private String videoUrl;
    private String title;
    private String username;
    private String caption;
    private boolean isPlayable;

    // Violence detection fields
    private boolean analyzed = false;
    private boolean blocked = false;
    private float violenceConfidence = 0f;
    private float violentRatio = 0f;
    private String videoPath;

    // Social media fields
    private int likes = 1245;
    private int comments = 89;
    private int shares = 34;
    private boolean isLiked = false;

    public ReelItem(String videoUrl, String title, String username, String caption) {
        this.videoUrl = validateAndFixUrl(videoUrl);
        this.title = title != null && !title.isEmpty() ? title : "Untitled";
        this.username = username != null && !username.isEmpty() ? username : "@user";
        this.caption = caption != null && !caption.isEmpty() ? caption : "";
        this.isPlayable = false;

        Log.d(TAG, "Created ReelItem: " + this.title + " - URL: " + this.videoUrl);
    }

    /**
     * Validates and fixes common URL issues, especially for GitHub URLs
     */
    private String validateAndFixUrl(String url) {
        if (url == null || url.isEmpty()) {
            Log.e(TAG, "URL is null or empty");
            return null;
        }

        // Trim and clean the URL
        url = url.trim();
        Log.d(TAG, "Original URL: " + url);

        // Fix common GitHub URL patterns
        if (url.contains("github.com")) {
            // Case 1: GitHub blob URL (needs conversion to raw)
            if (url.contains("/blob/")) {
                url = url.replace("github.com", "raw.githubusercontent.com")
                        .replace("/blob/", "/");
                Log.d(TAG, "Converted blob URL to raw: " + url);
            }
            // Case 2: Already has raw but might need branch specification
            else if (url.contains("raw.githubusercontent.com")) {
                // Check if URL has branch specified (main/master)
                String[] parts = url.split("/");
                if (parts.length >= 5) {
                    // Check if the 5th part is a branch name or filename
                    String possibleBranch = parts[5];
                    if (!possibleBranch.equals("main") && !possibleBranch.equals("master") &&
                            !possibleBranch.contains(".")) {
                        // No branch specified, insert 'main'
                        StringBuilder fixed = new StringBuilder();
                        for (int i = 0; i < 5; i++) {
                            fixed.append(parts[i]).append("/");
                        }
                        fixed.append("main/");
                        for (int i = 5; i < parts.length; i++) {
                            fixed.append(parts[i]);
                            if (i < parts.length - 1) fixed.append("/");
                        }
                        url = fixed.toString();
                        Log.d(TAG, "Added missing branch to raw URL: " + url);
                    }
                }
            }
            // Case 3: Regular github.com URL without blob (try to convert to raw)
            else {
                // Example: https://github.com/user/repo/file.mp4
                String[] parts = url.split("/");
                if (parts.length >= 5) {
                    StringBuilder raw = new StringBuilder("https://raw.githubusercontent.com/");
                    raw.append(parts[3]).append("/"); // username
                    raw.append(parts[4]).append("/"); // repo
                    raw.append("main/"); // branch
                    for (int i = 5; i < parts.length; i++) {
                        raw.append(parts[i]);
                        if (i < parts.length - 1) raw.append("/");
                    }
                    url = raw.toString();
                    Log.d(TAG, "Converted to raw GitHub URL: " + url);
                }
            }
        }

        // Ensure URL has proper protocol
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
            Log.d(TAG, "Added https protocol: " + url);
        }

        // Validate file extension
        if (!url.endsWith(".mp4") && !url.endsWith(".webm") && !url.endsWith(".3gp") &&
                !url.endsWith(".mkv") && !url.endsWith(".mov")) {
            Log.w(TAG, "URL doesn't end with common video extension: " + url);
        }

        return url;
    }

    // Getters and Setters
    public String getVideoUrl() { return videoUrl; }
    public void setVideoUrl(String videoUrl) { this.videoUrl = validateAndFixUrl(videoUrl); }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getCaption() { return caption; }
    public void setCaption(String caption) { this.caption = caption; }

    public boolean isPlayable() { return isPlayable; }
    public void setPlayable(boolean playable) { isPlayable = playable; }

    // Violence detection getters/setters
    public boolean isAnalyzed() { return analyzed; }
    public void setAnalyzed(boolean analyzed) { this.analyzed = analyzed; }

    public boolean isBlocked() { return blocked; }
    public void setBlocked(boolean blocked) { this.blocked = blocked; }

    public float getViolenceConfidence() { return violenceConfidence; }
    public void setViolenceConfidence(float violenceConfidence) { this.violenceConfidence = violenceConfidence; }

    public float getViolentRatio() { return violentRatio; }
    public void setViolentRatio(float violentRatio) { this.violentRatio = violentRatio; }

    public String getVideoPath() { return videoPath; }
    public void setVideoPath(String videoPath) { this.videoPath = videoPath; }

    // Social media getters/setters
    public int getLikes() { return likes; }
    public void setLikes(int likes) { this.likes = likes; }

    public int getComments() { return comments; }
    public void setComments(int comments) { this.comments = comments; }

    public int getShares() { return shares; }
    public void setShares(int shares) { this.shares = shares; }

    public boolean isLiked() { return isLiked; }
    public void setLiked(boolean liked) { isLiked = liked; }

    /**
     * Resets analysis state for re-analysis
     */
    public void resetAnalysis() {
        this.analyzed = false;
        this.blocked = false;
        this.violenceConfidence = 0f;
        this.violentRatio = 0f;
        this.videoPath = null;
        Log.d(TAG, "Analysis reset for: " + title);
    }

    /**
     * Checks if the video URL is likely valid
     */
    public boolean hasValidUrl() {
        return videoUrl != null &&
                !videoUrl.isEmpty() &&
                (videoUrl.startsWith("http://") || videoUrl.startsWith("https://"));
    }

    @Override
    public String toString() {
        return "ReelItem{" +
                "title='" + title + '\'' +
                ", username='" + username + '\'' +
                ", analyzed=" + analyzed +
                ", blocked=" + blocked +
                ", confidence=" + String.format("%.2f", violenceConfidence) +
                ", ratio=" + String.format("%.2f", violentRatio) +
                '}';
    }
}