package com.example.instagramreels;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;

public class SettingsActivity extends AppCompatActivity {
    private static final String TAG = "SettingsActivity";
    private static final String PREFS_NAME = "ViolenceBlockPrefs";
    private static final String KEY_VIOLENCE_BLOCK = "violence_block_enabled";

    private SwitchCompat violenceBlockSwitch;
    private TextView statusText;
    private SharedPreferences sharedPreferences;
    private ViolenceDetector violenceDetector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        try {
            Toolbar toolbar = findViewById(R.id.toolbar);
            setSupportActionBar(toolbar);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setDisplayHomeAsUpEnabled(true);
                getSupportActionBar().setDisplayShowHomeEnabled(true);
            }

            violenceBlockSwitch = findViewById(R.id.violenceBlockSwitch);
            statusText = findViewById(R.id.statusText);

            sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

            violenceDetector = new ViolenceDetector(this);
            violenceDetector.initialize();

            boolean isEnabled = sharedPreferences.getBoolean(KEY_VIOLENCE_BLOCK, false);
            violenceBlockSwitch.setChecked(isEnabled);
            updateStatus(isEnabled, violenceDetector.isInitialized());

            violenceBlockSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    sharedPreferences.edit().putBoolean(KEY_VIOLENCE_BLOCK, isChecked).apply();
                    updateStatus(isChecked, violenceDetector.isInitialized());

                    String message = isChecked ?
                            "Violence blocking enabled" :
                            "Violence blocking disabled";
                    Toast.makeText(SettingsActivity.this, message, Toast.LENGTH_SHORT).show();
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate", e);
        }
    }

    private void updateStatus(boolean isEnabled, boolean isModelLoaded) {
        if (!isModelLoaded) {
            statusText.setText("Status: Model not loaded - Check TFLite file in assets");
            statusText.setTextColor(getColor(android.R.color.holo_red_light));
            violenceBlockSwitch.setEnabled(false);
        } else if (isEnabled) {
            statusText.setText("Status: Active - Detecting violent content");
            statusText.setTextColor(getColor(android.R.color.holo_green_light));
            violenceBlockSwitch.setEnabled(true);
        } else {
            statusText.setText("Status: Ready (Disabled)");
            statusText.setTextColor(getColor(android.R.color.darker_gray));
            violenceBlockSwitch.setEnabled(true);
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (violenceDetector != null) {
            violenceDetector.close();
        }
    }

    public static boolean isViolenceBlockEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_VIOLENCE_BLOCK, false);
    }
}