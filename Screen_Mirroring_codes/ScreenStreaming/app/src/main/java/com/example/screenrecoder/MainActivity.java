package com.example.screenrecoder;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends Activity {
    private static final int REQUEST_CODE = 1000;
    private static final int PERMISSION_REQUEST_CODE = 1;
    private static final String TAG = "MainActivity";
    private MediaProjectionManager mediaProjectionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button startButton = findViewById(R.id.start_button);
        Button stopButton = findViewById(R.id.stop_button);

        // Initialize MediaProjectionManager
        mediaProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        startButton.setOnClickListener(v -> {
            Log.d(TAG, "Start button clicked. Checking permissions...");
            requestPermissionsAndStartRecording();
        });

        stopButton.setOnClickListener(v -> {
            Log.d(TAG, "Stop button clicked. Stopping recording...");
            stopScreenRecording();
        });
    }


    private void requestPermissionsAndStartRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // Android 10+
            String[] permissions = {
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.FOREGROUND_SERVICE
            };

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+
                permissions = new String[]{
                        Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION,
                        Manifest.permission.RECORD_AUDIO
                };
            }

            if (!hasAllPermissions(permissions)) {
                Log.d(TAG, "Requesting permissions...");
                ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
                return;
            }
        }
        Log.d(TAG, "All permissions granted. Starting screen recording...");
        startScreenRecording();
    }

    private boolean hasAllPermissions(String[] permissions) {
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Permission not granted: " + permission);
                return false;
            }
        }
        return true;
    }

    private void startScreenRecording() {
        Intent captureIntent = mediaProjectionManager.createScreenCaptureIntent();
        Log.d(TAG, "Starting activity for screen capture...");
        startActivityForResult(captureIntent, REQUEST_CODE);
    }

    private void stopScreenRecording() {
        Intent stopServiceIntent = new Intent(this, ScreenRecordService.class);
        stopService(stopServiceIntent);
        Log.d(TAG, "Screen recording service stopped.");
        Toast.makeText(this, "Recording Stopped", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK) {
            Log.d(TAG, "Screen capture permission granted. Starting service...");
            Intent serviceIntent = new Intent(this, ScreenRecordService.class);
            serviceIntent.putExtra("resultCode", resultCode);
            serviceIntent.putExtra("data", data);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } else {
            Log.d(TAG, "Screen capture permission denied.");
            Toast.makeText(this, "Screen recording permission denied.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int i = 0; i < grantResults.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Permission denied: " + permissions[i]);
                    Toast.makeText(this, "Permissions are required for screen recording.", Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            Log.d(TAG, "All permissions granted via dialog. Starting screen recording...");
            startScreenRecording();
        }
    }
}