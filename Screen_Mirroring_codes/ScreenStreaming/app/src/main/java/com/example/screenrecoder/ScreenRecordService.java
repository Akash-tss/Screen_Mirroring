package com.example.screenrecoder;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;

public class ScreenRecordService extends Service {
    private static final String CHANNEL_ID = "ScreenRecorderChannel";
    private static final String TAG = "ScreenRecordService";
    private MediaProjection mediaProjection;
    private ImageReader imageReader;
    private int mWidth;
    private int mHeight;
    private boolean isRecording = false;
    private static final int PORT = 5090;
    private Socket socket;
    private String ipAddress = "10.235.47.181";
    private Handler backgroundHandler;
    private OutputStream outputStream;

    @Override
    public void onCreate() {
        super.onCreate();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Stream Service Channel", NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        // Start background thread for socket handling
        HandlerThread handlerThread = new HandlerThread("StreamServiceThread");
        handlerThread.start();
        backgroundHandler = new Handler(handlerThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "ScreenRecordService started.");

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Screen Recorder")
                .setContentText("Recording your screen...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();
        startForeground(1, notification);

        backgroundHandler.post(this::connectToServer);

        int resultCode = intent.getIntExtra("resultCode", -1);
        Intent data = intent.getParcelableExtra("data");

        MediaProjectionManager projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (projectionManager != null && data != null) {
            mediaProjection = projectionManager.getMediaProjection(resultCode, data);
            Log.d(TAG, "MediaProjection obtained. Setting up ImageReader...");
            setupImageReader();
            startRecording();
        } else {
            Log.e(TAG, "Failed to get MediaProjectionManager.");
        }

        return START_NOT_STICKY;
    }

    private void connectToServer() {
        try {
            if (socket == null || socket.isClosed()) {
                Log.d(TAG, "Attempting to connect to " + ipAddress);
                socket = new Socket(ipAddress, PORT);
                socket.setKeepAlive(true);
                outputStream = socket.getOutputStream();
                Log.d(TAG, "Connected to server.");
            }
        } catch (IOException e) {
            Log.e(TAG, "Connection failed: " + e.getMessage());
            retryConnection();
        }
    }

    private void retryConnection() {
        backgroundHandler.postDelayed(this::connectToServer, 5000);
    }

    private void setupImageReader() {
        DisplayManager displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) {
            Log.e(TAG, "No display found.");
            return;
        }
        DisplayMetrics displayMetrics = new DisplayMetrics();
        display.getRealMetrics(displayMetrics);
        mWidth = 480;
        mHeight = 720;

        imageReader = ImageReader.newInstance(mWidth, mHeight, PixelFormat.RGBA_8888, 3);
        imageReader.setOnImageAvailableListener(this::onImageAvailable, backgroundHandler);
    }

    private void onImageAvailable(ImageReader reader) {
        try (Image image = reader.acquireLatestImage()) {
            if (image != null) {
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                Bitmap bitmap = Bitmap.createBitmap(mWidth, mHeight, Bitmap.Config.ARGB_8888);
                bitmap.copyPixelsFromBuffer(buffer);

                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, byteArrayOutputStream);
                byte[] compressedData = byteArrayOutputStream.toByteArray();

                sendFrame(compressedData);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing image", e);
        }
    }

    private void sendFrame(byte[] data) {
        if (socket != null && socket.isConnected()) {
            backgroundHandler.post(() -> {
                try {
                    // Send image size before data
                    outputStream.write(intToBytes(data.length));
                    outputStream.write(data);
                    outputStream.flush();
                    Log.d(TAG, "Frame sent: " + data.length + " bytes");
                } catch (IOException e) {
                    Log.e(TAG, "Error sending frame", e);
                    retryConnection();
                }
            });
        } else {
            Log.e(TAG, "Socket is not connected.");
            retryConnection();
        }
    }

    private byte[] intToBytes(int value) {
        return new byte[]{
                (byte) (value >> 24),
                (byte) (value >> 16),
                (byte) (value >> 8),
                (byte) value
        };
    }

    private void startRecording() {
        if (mediaProjection != null && imageReader != null) {
            int densityDpi = getResources().getDisplayMetrics().densityDpi;
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    super.onStop();
                    Log.d(TAG, "MediaProjection stopped. Releasing resources.");
                    stopRecording();
                }
            }, backgroundHandler);

            Log.d(TAG, "Creating virtual display...");
            mediaProjection.createVirtualDisplay(
                    "ScreenCapture",
                    500,
                    720,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    null
            );

            isRecording = true;
            Log.d(TAG, "Screen capture started.");
        } else {
            Log.e(TAG, "MediaProjection or ImageReader is null.");
        }
    }

    private void stopRecording() {
        if (isRecording) {
            Log.d(TAG, "Stopping MediaProjection...");
            mediaProjection.stop();
            mediaProjection = null;
            isRecording = false;
        }

        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }

        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing socket: ", e);
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
}
