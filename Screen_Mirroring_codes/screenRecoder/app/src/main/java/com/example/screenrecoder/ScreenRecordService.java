package com.example.screenrecoder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.IOException;

public class ScreenRecordService extends Service {
    private static final String CHANNEL_ID = "ScreenRecorderChannel";
    private static final String TAG = "ScreenRecordService";
    private MediaProjection mediaProjection;
    private MediaRecorder mediaRecorder;
    private String filePath;
    private int mWidth;
    private int mHeight;
    private boolean isRecording = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "ScreenRecordService started.");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel();
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Screen Recorder")
                .setContentText("Recording your screen...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();

        startForeground(1, notification);

        int resultCode = intent.getIntExtra("resultCode", -1);
        Intent data = intent.getParcelableExtra("data");

        MediaProjectionManager projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (projectionManager != null) {
            assert data != null;
            mediaProjection = projectionManager.getMediaProjection(resultCode, data);
            Log.d(TAG, "MediaProjection obtained. Setting up MediaRecorder...");
            setupMediaRecorder();
            if (mediaRecorder != null) { // Check if setup was successful
                startRecording();
            }
        } else {
            Log.e(TAG, "Failed to get MediaProjectionManager.");
        }

        return START_NOT_STICKY;
    }

    private void setupMediaRecorder() {
        try {
            File directory = new File("/storage/self/primary/Movies", "ScreenRecordings");
            if (!directory.exists() && !directory.mkdirs()) {
                Log.e(TAG, "Failed to create directory for recordings.");
                return;
            }

            filePath = directory + "/ScreenRecording_" + System.currentTimeMillis() + ".mp4";

            // Correct way to get display metrics
            DisplayManager displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) {
                Log.e(TAG, "No display found.");
                return;
            }
            DisplayMetrics displayMetrics = new DisplayMetrics();
            display.getRealMetrics(displayMetrics);
            mWidth = displayMetrics.widthPixels;
            mHeight = displayMetrics.heightPixels;

            Log.d(TAG, "WIDTH VALUE : " + mWidth);
            Log.d(TAG, "HEIGHT VALUE : " + mHeight);

            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.DEFAULT);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setOutputFile(filePath);
            mediaRecorder.setVideoSize(mWidth, mHeight);  // Correct way of setting resolution
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setVideoFrameRate(30);


            mediaRecorder.prepare();
            Log.d(TAG, "MediaRecorder setup completed. File path: " + filePath);
        } catch (IOException e) {
            Log.e(TAG, "Failed to set up MediaRecorder.", e);
            mediaRecorder = null; // Ensure mediaRecorder is null if it failed.
        }
    }

    private void startRecording() {
        try {
            if (mediaProjection != null && mediaRecorder != null) { // Check for both MediaProjection and MediaRecorder
                // Register the callback to manage the media projection lifecycle
                mediaProjection.registerCallback(new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        super.onStop();
                        Log.d(TAG, "MediaProjection stopped.");
                        stopRecording(); // Stop the recording when the media projection is stopped
                    }
                }, null);

                // Obtain the display and its metrics
                Display display = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
                if (display == null) {
                    Log.e(TAG, "No display found.");
                    return;
                }
                DisplayMetrics displayMetrics = new DisplayMetrics();
                display.getRealMetrics(displayMetrics);
                int displayWidth = displayMetrics.widthPixels;
                int displayHeight = displayMetrics.heightPixels;
                float density = getResources().getDisplayMetrics().density;
                int densityDpi = (int) (density * 160);

                Log.d(TAG,"display Width " + displayWidth);
                Log.d(TAG,"display Height " + displayHeight);
                Log.d(TAG,"display density " + densityDpi);
                Log.d(TAG, "Creating virtual display for recording...");
                mediaProjection.createVirtualDisplay(
                        "ScreenRecordService",
                        displayWidth, displayHeight, densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        mediaRecorder.getSurface(),
                        null, null
                );
                mediaRecorder.start();
                isRecording = true;
                Log.d(TAG, "Screen recording started.");
            }else{
                Log.e(TAG, "MediaProjection or MediaRecorder is null");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error starting screen recording.", e);
        }
    }
    private void stopRecording() {
        if (isRecording) {
            try {
                Log.d(TAG, "Stopping MediaRecorder...");
                mediaRecorder.stop();
                mediaRecorder.release();
                isRecording = false;
                Log.d(TAG, "Recording saved to: " + filePath);
            } catch (IllegalStateException e) {
                Log.e(TAG, "IllegalStateException while stopping MediaRecorder: ", e);
            }
            mediaRecorder = null;
        } else {
            Log.d(TAG, "MediaRecorder is not recording, so stop was skipped.");
        }

        if (mediaProjection != null) {
            Log.d(TAG, "Stopping MediaProjection...");
            mediaProjection.stop();
            mediaProjection = null;
        }

    }



    @Override
    public void onDestroy() {
        super.onDestroy();
        stopRecording();
        Log.d(TAG, "ScreenRecordService destroyed.");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d(TAG, "Creating notification channel...");
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Screen Recorder Service",
                    NotificationManager.IMPORTANCE_HIGH
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
                Log.d(TAG, "Notification channel created.");
            } else {
                Log.e(TAG, "Failed to create notification channel.");
            }
        }
    }
}