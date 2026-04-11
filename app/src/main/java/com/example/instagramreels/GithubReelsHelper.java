package com.example.instagramreels;

import android.os.AsyncTask;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class GithubReelsHelper {
    private static final String TAG = "GithubReelsHelper";
    private static final String GITHUB_JSON_URL = "https://raw.githubusercontent.com/knkannan70/reelsStreamer/refs/heads/main/videos.json";

    private static final int CONNECTION_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 30000;

    public interface ReelListener {
        void onLoaded(List<ReelItem> reels);
        void onError(String errorMessage);
    }

    public static void fetchReels(ReelListener listener) {
        new FetchReelsTask(listener).execute();
    }

    private static class FetchReelsTask extends AsyncTask<Void, Void, List<ReelItem>> {
        private final ReelListener listener;
        private String errorMessage;
        private final Random random = new Random();

        FetchReelsTask(ReelListener listener) {
            this.listener = listener;
        }

        @Override
        protected List<ReelItem> doInBackground(Void... voids) {
            List<ReelItem> reels = new ArrayList<>();
            HttpURLConnection connection = null;
            BufferedReader reader = null;

            try {
                Log.d(TAG, "Fetching reels from: " + GITHUB_JSON_URL);

                URL url = new URL(GITHUB_JSON_URL);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(CONNECTION_TIMEOUT);
                connection.setReadTimeout(READ_TIMEOUT);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)");

                int responseCode = connection.getResponseCode();
                Log.d(TAG, "Response code: " + responseCode);

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    errorMessage = "Server returned HTTP " + responseCode;
                    return reels;
                }

                reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }

                String jsonText = response.toString().trim();
                Log.d(TAG, "JSON response length: " + jsonText.length());

                if (jsonText.isEmpty()) {
                    errorMessage = "Empty response from server";
                    return reels;
                }

                JSONArray jsonArray = new JSONArray(jsonText);
                Log.d(TAG, "Found " + jsonArray.length() + " videos in JSON");

                for (int i = 0; i < jsonArray.length(); i++) {
                    try {
                        JSONObject item = jsonArray.getJSONObject(i);

                        String videoUrl = item.optString("url", "");
                        String title = item.optString("title", "Video " + (i + 1));
                        String username = item.optString("username", "@user_" + (i + 1));
                        String caption = item.optString("caption", title);

                        if (videoUrl.isEmpty()) {
                            Log.w(TAG, "Skipping item " + i + " - no URL");
                            continue;
                        }

                        Log.d(TAG, "Processing video URL: " + videoUrl);

                        ReelItem reel = createReelItem(videoUrl, title, username, caption, i);
                        reels.add(reel);

                    } catch (Exception e) {
                        Log.e(TAG, "Error processing video item " + i, e);
                    }
                }

                if (!reels.isEmpty()) {
                    Collections.shuffle(reels, new Random(System.currentTimeMillis()));
                }
                Log.d(TAG, "Successfully loaded " + reels.size() + " reels");

            } catch (Exception e) {
                errorMessage = "Network error: " + e.getMessage();
                Log.e(TAG, "Fetch error", e);
            } finally {
                try {
                    if (reader != null) reader.close();
                } catch (Exception ignored) {}
                if (connection != null) connection.disconnect();
            }

            return reels;
        }

        @Override
        protected void onPostExecute(List<ReelItem> reels) {
            if (listener == null) return;

            if (errorMessage != null && reels.isEmpty()) {
                Log.e(TAG, "Error: " + errorMessage);
                listener.onError(errorMessage);
            } else if (reels.isEmpty()) {
                listener.onError("No videos available. Please try again later.");
            } else {
                listener.onLoaded(reels);
            }
        }

        private ReelItem createReelItem(String videoUrl, String title, String username,
                                        String caption, int index) {
            String[] usernames = {
                    "@travel_adventures", "@foodie_diaries", "@fitness_motivation",
                    "@art_and_craft", "@music_lovers", "@comedy_central", "@pet_vibes",
                    "@tech_reviews", "@gaming_community", "@fashion_style", "@nature_wonders",
                    "@sports_highlights", "@dance_crew", "@cooking_tips", "@movie_clips"
            };

            String[] captions = {
                    title + "\n\n#reels #viral #trending #shorts",
                    title + "\n\nCheck this out! 🔥 #viral #reels",
                    title + "\n\nAmazing content! 🎥 #trending #viral",
                    title + "\n\nYou won't believe this! 😱 #reels #viral",
                    title + "\n\nMust watch! 👀 #trending #shorts"
            };

            String finalUsername = username != null && !username.isEmpty() ?
                    username : usernames[index % usernames.length];
            String finalCaption = caption != null && !caption.isEmpty() ?
                    caption : captions[random.nextInt(captions.length)];

            ReelItem reel = new ReelItem(videoUrl, title, finalUsername, finalCaption);

            reel.setLikes(1000 + random.nextInt(9000));
            reel.setComments(50 + random.nextInt(450));
            reel.setShares(10 + random.nextInt(90));

            return reel;
        }
    }
}