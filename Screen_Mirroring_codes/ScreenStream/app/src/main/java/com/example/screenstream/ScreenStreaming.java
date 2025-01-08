package com.example.screenstream;

import android.annotation.SuppressLint;
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
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class ScreenStreaming extends Service {
    private static final String TAG = "ScreenStreaming";
    private static final String CHANNEL_ID = "ScreenStreamingChannel";
    private MediaProjection mediaProjection;
    private MediaCodec mediaCodec;
    private int displayWidth, displayHeight, densityDpi;
    private final boolean configSent = false;
    private PipedOutputStream pipedOutputStream;
    private FileOutputStream fileOutputStream;


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "ScreenStreamingService started.");

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

        Log.d(TAG, "resultCode: " + resultCode);
        Log.d(TAG, "Intent data: " + (data != null ? "Valid" : "Null"));

        setupDisplayMetrics();

        MediaProjectionManager projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (projectionManager != null) {
            assert data != null;
            mediaProjection = projectionManager.getMediaProjection(resultCode, data);

            if (mediaProjection != null) {
                Log.d(TAG, "MediaProjection initialized successfully.");
                registerMediaProjectionCallback();
            } else {
                Log.e(TAG, "MediaProjection is null. Initialization failed.");
            }

            // Setup MediaCodec and start streaming after it's ready
            Log.d(TAG,"Setting up the MediaCodec and later starting the stream!!! ");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setupMediaCodec(this::startStreaming);
            }

        } else {
            Log.e(TAG, "Failed to get MediaProjectionManager.");
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void setupDisplayMetrics() {
        Log.d(TAG, "Setting up the Display...");
        Display display = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            display = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        }
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

        Log.d(TAG, "Display Width: " + displayWidth);
        Log.d(TAG, "Display Height: " + displayHeight);
        Log.d(TAG, "Density DPI: " + densityDpi);

        Log.d(TAG, "Done with setting up the Display...");
    }

    private void registerMediaProjectionCallback() {
        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                super.onStop();
                Log.d(TAG, "MediaProjection stopped.");
                stopSelf();
            }
        }, new Handler());
        Log.d(TAG, "MediaProjection callback registered.");
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private void setupMediaCodec(Runnable onComplete) {
        try {
            Log.d(TAG, "Setting up MediaCodec...");
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, displayWidth, displayHeight);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 5000000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            // Ensure SPS/PPS are prepended to sync (key) frames
            format.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1);
            format.setInteger(MediaFormat.KEY_WIDTH,displayWidth);
            format.setInteger(MediaFormat.KEY_HEIGHT,displayHeight);


            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

            Surface inputSurface = mediaCodec.createInputSurface();

            if (mediaProjection != null) {
                mediaProjection.createVirtualDisplay(
                        "ScreenStreaming",
                        displayWidth,
                        displayHeight,
                        densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        inputSurface,
                        null,
                        null
                );
                Log.d(TAG, "Virtual display created successfully.");
            } else {
                Log.e(TAG, "Cannot create virtual display. MediaProjection is null.");
            }

            mediaCodec.start();
            Log.d(TAG, "MediaCodec started successfully with configuration: " + format);

            // Notify completion
            if (onComplete != null) {
                onComplete.run();
            }

        } catch (IOException e) {
            Log.e(TAG, "Failed to set up MediaCodec", e);
            stopSelf();
        }
    }

    private void startStreaming() {
        Log.d(TAG, "Starting streaming...");

        String ipAddress = "192.168.246.151"; // Replace with your server IP
        String rtspUrl = "rtsp://" + ipAddress + ":8554/stream";

        Log.d(TAG, "RTSP URL: " + rtspUrl);

        try {
            // Create a pipe for inter-thread communication
            pipedOutputStream = new PipedOutputStream();
            PipedInputStream pipedInputStream = new PipedInputStream(pipedOutputStream);

//            String ffmpegCommand = String.format(
//                    "-loglevel verbose -analyzeduration 5000000 -probesize 5000000 -f h264 -i pipe:0 -vf scale=ceil(1080/2)*2:ceil(2340/2)*2 -rtsp_transport tcp -c:v libx264 -preset ultrafast -tune zerolatency -f rtsp %s",
//                    rtspUrl
//            );

//            String ffmpegCommand = String.format(
//                    "-loglevel debug -f h264 -analyzeduration 10000000 -probesize 10000000 -s -video_size 1080x2340 -i pipe:0 -vf scale=ceil(1080/2)*2:ceil(2340/2)*2 -rtsp_transport tcp -c:v libx264 -preset ultrafast -tune zerolatency  -f rtsp %s",
//                    rtspUrl
//            );

//            String ffmpegCommand = String.format(
//                    "-loglevel verbose -f rawvideo -pix_fmt yuv420p -f h264 -s -video_size = 1080x2340 -i pipe:0 -c:v copy -preset ultrafast -tune zerolatency -b:v 1500k rtsp %s",
//                    rtspUrl
//            );

//            String ffmpegCommand = String.format(
//                    "-f", "h264",
//                    "-i", "pipe:0", // Input from the previous step (replace pipe with the actual source)
//                    "-vcodec", "copy",
//                    "-f", "rtsp",
//                    "rtsp://192.168.246.151:8554/stream" // Use your real RTSP server here
//            );

//            @SuppressLint("DefaultLocale") String ffmpegCommand = String.format(
//                    "-loglevel verbose -f rawvideo  -s %dx%d -r 30 -i pipe:0 -c:v copy -preset ultrafast -tune zerolatency -b:v 1500k " +
//                            "-f rtsp -rtsp_transport tcp %s",
//                    displayWidth, displayHeight, rtspUrl
//            );


//            String ffmpegCommand = String.format(
//                    "-f h264 -s:v video_size 1280x720 -r 30 -i pipe:0 " +
//                            "-rtsp_transport tcp -c:v libx264 -preset ultrafast -tune zerolatency " +
//                            "-vf \"scale=ceil(iw/2)*2:ceil(ih/2)*2\" -f rtsp %s",
//                    rtspUrl
//            );

//            String ffmpegCommand = String.format(
//                    "-f h264 -i pipe:0 " +
//                            "-c:v copy -f rtsp -rtsp_transport tcp " +
//                            "-preset ultrafast -tune zerolatency " +
//                            "%s",
//                    rtspUrl
//
//            );

            @SuppressLint("DefaultLocale") String ffmpegCommand = String.format(
                    "-loglevel verbose -f h264 -s -video_size %dx%d -r 30 -i pipe:0 " +
                            "-c:v copy -f rtsp -rtsp_transport tcp " +
                            "-preset ultrafast -tune zerolatency " +
                            "%s",
                    displayWidth, displayHeight, rtspUrl
            );


            Log.d(TAG, "FFmpeg Command: " + ffmpegCommand);

            // Start FFmpeg immediately after setup
            new Thread(() -> {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {


                    throw new RuntimeException(e);
                }
                Log.d(TAG, "Starting FFmpeg...");
                FFmpegKit.executeAsync(ffmpegCommand, session -> {
                    String output = session.getOutput();
                    String error = session.getFailStackTrace();
                    ReturnCode returnCode = session.getReturnCode();
                    if (ReturnCode.isSuccess(returnCode)){
                        Log.d(TAG, "Streaming started successfully on " + rtspUrl);
                        Log.d(TAG, "FFMPEG OUTPUT: " + output);
                    } else {
                        // Log detailed error for debugging
                        Log.e(TAG, "FFmpeg execution failed with return code: " + returnCode);
                        if (output != null && !output.isEmpty()) {
                            Log.e(TAG, "FFmpeg Output: " + output);
                        }
                        if (error != null && !error.isEmpty()) {
                            Log.e(TAG, "FFmpeg Error Stack Trace: " + error);
                        }
                    }
                });
            }).start();

            // Start a thread to feed MediaCodec output to the pipe
            new Thread(() -> {
                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                try {
                    while (true) {
                        int outputIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 1000);
                        if (outputIndex >= 0) {

                            ByteBuffer outputBuffer = mediaCodec.getOutputBuffer(outputIndex);

                            if (outputBuffer != null && bufferInfo.size > 0) {
                                Log.d(TAG, "Encoded frame size: " + bufferInfo.size + " bytes");
                                byte[] buffer = new byte[bufferInfo.size];

                                MediaFormat format1 = mediaCodec.getOutputFormat();
                                ByteBuffer sps1 = format1.getByteBuffer("csd-0");
                                ByteBuffer pps1 = format1.getByteBuffer("csd-1");

                                if(sps1 != null && pps1 != null) {

                                    byte[] spsBytes1 = new byte[sps1.remaining()];
                                    sps1.get(spsBytes1);

                                    Log.d(TAG, "Sps data before adding  :" + Arrays.toString(spsBytes1));

                                    byte[] ppsBytes1 = new byte[pps1.remaining()];
                                    pps1.get(ppsBytes1);

                                    Log.d(TAG, "Pps data before adding  :" + Arrays.toString(ppsBytes1));

                                }
                                outputBuffer.get(buffer);

                                // Log the size of the encoded data being written to the pipe
                                Log.d(TAG, "Encoded buffer size: " + buffer.length + " bytes");

                                Log.d(TAG, "Encoded buffer : " + Arrays.toString(buffer));


                                int bufferSize = bufferInfo.size;
                                Log.d(TAG, "Output buffer size: " + bufferSize + " bytes, output buffer capacity:" + outputBuffer.capacity() + " bytes, output buffer limit: " + outputBuffer.limit() + " bytes");

                                // Write encoded data to the pipe
                                if (buffer.length > 0) {
                                    pipedOutputStream.write(buffer);
                                    pipedOutputStream.flush();
                                    outputBuffer.clear();
                                    Log.d(TAG, "Written " + buffer.length + " bytes to pipe.");
                                } else {
                                    Log.d(TAG, "Buffer is empty, skipping...");
                                }
                            }

                            mediaCodec.releaseOutputBuffer(outputIndex, false);

                        } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            // Handle codec format change
                            MediaFormat newFormat = mediaCodec.getOutputFormat();
                            Log.d(TAG, "Output format changed: " + newFormat);
                        } else {
                            Log.d(TAG, "No output available from MediaCodec, retrying...");
                        }
                        // Sleep briefly to allow buffers to fill
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Log.e(TAG, "Error during sleep", e);
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error writing to pipe", e);
                } finally {
                    try {
                        pipedOutputStream.close();
                    } catch (IOException e) {
                        Log.e(TAG, "Error closing pipe", e);
                    }
                }
            }).start();
        } catch (IOException e) {
            Log.e(TAG, "Failed to create pipe for FFmpeg", e);
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Stopping service...");
        if (mediaCodec != null) {
            mediaCodec.stop();
            mediaCodec.release();
        }
        if (fileOutputStream != null) {
            try {
                fileOutputStream.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing file output stream", e);
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }


    private void createNotificationChannel() {
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