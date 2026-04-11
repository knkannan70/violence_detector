package com.example.instagramreels;

public class VideoItem {
    private String id;
    private String videoUrl;
    private String thumbnailUrl;
    private boolean isBlocked;
    private boolean isAnalyzing;
    private boolean needsAnalysis;

    public VideoItem(String id, String videoUrl, String thumbnailUrl) {
        this.id = id;
        this.videoUrl = videoUrl;
        this.thumbnailUrl = thumbnailUrl;
        this.isBlocked = false;
        this.isAnalyzing = false;
        this.needsAnalysis = true;
    }

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getVideoUrl() { return videoUrl; }
    public void setVideoUrl(String videoUrl) { this.videoUrl = videoUrl; }

    public String getThumbnailUrl() { return thumbnailUrl; }
    public void setThumbnailUrl(String thumbnailUrl) { this.thumbnailUrl = thumbnailUrl; }

    public boolean isBlocked() { return isBlocked; }
    public void setBlocked(boolean blocked) { isBlocked = blocked; }

    public boolean isAnalyzing() { return isAnalyzing; }
    public void setAnalyzing(boolean analyzing) { isAnalyzing = analyzing; }

    public boolean needsAnalysis() { return needsAnalysis; }
    public void setNeedsAnalysis(boolean needsAnalysis) { this.needsAnalysis = needsAnalysis; }
}