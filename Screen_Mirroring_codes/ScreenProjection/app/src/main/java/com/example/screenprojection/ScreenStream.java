package com.example.screenprojection;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.arthenica.ffmpegkit.FFmpegKit;

import com.arthenica.ffmpegkit.SessionState;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class ScreenStream extends Service {
    private static final String TAG = "ScreenStreamService";
    private static final String CHANNEL_ID = "ScreenStreamChannel";

    private MediaProjection mediaProjection;
    private MediaCodec mediaCodec;
    private int displayWidth, displayHeight, densityDpi;
    private volatile boolean isStreaming;
    private Thread streamingThread;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "ScreenStreamService started.");

        createNotificationChannel();

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Screen Streaming")
                .setContentText("Streaming your screen...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();
        startForeground(1, notification);

        int resultCode = intent.getIntExtra("resultCode", -1);
        Intent data = intent.getParcelableExtra("data");

        setupDisplayMetrics();

        MediaProjectionManager projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (projectionManager != null && data != null) {
            mediaProjection = projectionManager.getMediaProjection(resultCode, data);
            if (mediaProjection != null) {
                Log.d(TAG, "MediaProjection initialized successfully.");
                registerMediaProjectionCallback();

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setupMediaCodec(() -> startStreamingToFFmpeg("rtsp://192.168./stream"));
                }
            } else {
                Log.e(TAG, "MediaProjection initialization failed.");
                stopSelf();
            }
        } else {
            Log.e(TAG, "Failed to get MediaProjectionManager or Intent data.");
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void setupDisplayMetrics() {
        Display display = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) {
            Log.e(TAG, "No display found.");
            stopSelf();
            return;
        }

        DisplayMetrics displayMetrics = new DisplayMetrics();
        display.getRealMetrics(displayMetrics);

        displayWidth = displayMetrics.widthPixels;
        displayHeight = displayMetrics.heightPixels;
        densityDpi = displayMetrics.densityDpi;
    }

    private void registerMediaProjectionCallback() {
        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                super.onStop();
                stopStreaming();
                stopSelf();
            }
        }, new Handler());
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private void setupMediaCodec(Runnable onComplete) {
        try {
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, displayWidth, displayHeight);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 5000000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            format.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1);

            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface inputSurface = mediaCodec.createInputSurface();

            mediaProjection.createVirtualDisplay(
                    "ScreenStream",
                    displayWidth,
                    displayHeight,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    inputSurface,
                    null,
                    null
            );

            mediaCodec.start();

            if (onComplete != null) {
                onComplete.run();
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to set up MediaCodec", e);
            stopSelf();
        }
    }

    private void startStreamingToFFmpeg(String rtspUrl) {
        streamingThread = new Thread(() -> {
            try {
                PipedOutputStream pipedOutputStream = new PipedOutputStream();
                PipedInputStream pipedInputStream = new PipedInputStream(pipedOutputStream);

                // Start FFmpeg process
                String ffmpegCommand = String.format(
                        "-f h264 -i pipe:0 -c:v copy -f rtsp %s", rtspUrl
                );

                FFmpegKit.executeAsync(ffmpegCommand, session -> {
                    if (session.getState() == SessionState.FAILED) {
                        Log.e(TAG, "FFmpeg execution failed: " + session.getFailStackTrace());
                    } else if (session.getState() == SessionState.COMPLETED) {
                        Log.d(TAG, "FFmpeg execution completed successfully.");
                    }
                });

                    MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                isStreaming = true;

                while (isStreaming) {
                    int outputIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 1000);
                    if (outputIndex >= 0) {
                        ByteBuffer outputBuffer = mediaCodec.getOutputBuffer(outputIndex);
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            byte[] buffer = new byte[bufferInfo.size];
                            outputBuffer.get(buffer);

                            Log.d(TAG,"Encoded value : " + Arrays.toString(buffer));
                            mediaCodec.releaseOutputBuffer(outputIndex, false);

                            // Execute FFmpeg command
//                            FFmpegKit.executeAsync(
//                                    String.format("-f h264 -i pipe:0 -c:v copy -f rtsp %s", rtspUrl),
//                                    session -> {
//                                        if (session.getState() == SessionState.FAILED) {
//                                            Log.e(TAG, "FFmpeg execution failed: " + session.getFailStackTrace());
//                                        } else if (session.getState() == SessionState.COMPLETED) {
//                                            Log.d(TAG, "FFmpeg execution completed successfully.");
//                                        }
//                                    },
//                                    log -> {
//                                        Log.d(TAG, "FFmpeg Log: " + log.getMessage());
//                                    },
//                                    statistics -> {
//                                        Log.d(TAG, String.format(
//                                                "FFmpeg Statistics - Time: %d, Bitrate: %d, Size: %d",
//                                                statistics.getTime(),
//                                                statistics.getBitrate(),
//                                                statistics.getSize()
//                                        ));
//                                    }
//                            );

                            Log.d(TAG, "Encoded data sent to FFmpeg.");
                        }
                    } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.d(TAG, "MediaCodec format changed.");
                    } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        Thread.sleep(10);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error streaming to FFmpegKit", e);
            }
        });
        streamingThread.start();
    }


    private void stopStreaming() {
        isStreaming = false;
        if (streamingThread != null) {
            try {
                streamingThread.join();
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping streaming thread", e);
            }
        }
        if (mediaCodec != null) {
            mediaCodec.stop();
            mediaCodec.release();
            mediaCodec = null;
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
    }

    @Override
    public void onDestroy() {
        stopStreaming();
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Screen Streaming Service",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
