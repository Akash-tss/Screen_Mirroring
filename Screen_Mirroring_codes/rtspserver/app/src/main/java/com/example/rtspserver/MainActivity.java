package com.example.rtspserver;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.os.Environment;
import android.annotation.SuppressLint;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

public class MainActivity extends AppCompatActivity {
    private static final int VIDEO_PICK_REQUEST = 1;
    private static final String TAG = "RTSPVLC";
    private boolean isStreaming = false;
    private String streamCommand;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button selectVideoButton = findViewById(R.id.selectVideoButton);
        Button stopStreamingButton = findViewById(R.id.stopStreamingButton);

        // Start video selection when the button is clicked
        selectVideoButton.setOnClickListener(v -> openVideoPicker());

        // Stop streaming when the button is clicked
        stopStreamingButton.setOnClickListener(v -> stopStreaming());

        // Request permissions if needed (for Android 6.0 and above)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, 100);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(intent);
                }
            }
        }
    }

    public String getLocalIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> addrs = intf.getInetAddresses(); addrs.hasMoreElements();) {
                    InetAddress addr = addrs.nextElement();
                    // Skip link-local addresses (IPv6) and loopback addresses
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (SocketException ex) {
            Log.e(TAG, "Error getting local IP address", ex);
        }
        return null;  // If no IPv4 address is found
    }

    // Open the file picker to choose a video
    private void openVideoPicker() {
        Log.d(TAG, "openVideoPicker: Select button pressed");
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(intent, VIDEO_PICK_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VIDEO_PICK_REQUEST && resultCode == RESULT_OK && data != null) {
            Uri videoUri = data.getData();
            if (videoUri != null) {
                String videoPath = getPathFromUri(videoUri);
                if (videoPath != null) {
                    Log.d(TAG, "video selected");
                    startRtspServer(videoPath);
                } else {
                    Toast.makeText(this, "Failed to get video path", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    // Start the RTSP server with the selected video
    private void startRtspServer(String videoPath) {
        if (isStreaming) {
            Toast.makeText(this, "Streaming is already running", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "RTSP STARTED ");
            return;
        }

        // Replace "<your-ip-address>" with your phone's actual IP address on the network
        String phoneIpAddress = getLocalIpAddress();  // Use your device's local IP
        Log.d(TAG,"IP ADDRESS IS : " + phoneIpAddress);
        String ipaddress = "192.168.246.151"; // use the same address or replace with device ip

        String rtspUrl = "rtsp://" + ipaddress + ":8554/stream";

        Log.d(TAG, rtspUrl);

        @SuppressLint("DefaultLocale") String streamCommand = String.format(
                "-re -i \"%s\" -rtsp_transport tcp -c:v libx264 -preset ultrafast -tune zerolatency -vf \"scale=ceil(iw/2)*2:ceil(ih/2)*2\" -f rtsp %s",
                videoPath, rtspUrl
        );


        Log.d(TAG, "Stream command: " + streamCommand);

        Toast.makeText(this, "Starting stream on " + rtspUrl, Toast.LENGTH_LONG).show();
        Log.d(TAG, "Starting stream on " + rtspUrl);

        FFmpegKit.executeAsync(streamCommand, session -> {
            ReturnCode returnCode = session.getReturnCode();
            Log.d(TAG, "Return code: " + returnCode);
            if (ReturnCode.isSuccess(returnCode)) {
                Log.d(TAG,"Return code :" + returnCode);
                Log.d(TAG, "Streaming started successfully on " + rtspUrl);
                runOnUiThread(() -> Toast.makeText(this, "Streaming started successfully!", Toast.LENGTH_LONG).show());
                isStreaming = true;
                Log.e(TAG, "FFmpeg execution failed: " + session.getOutput());
            } else {
                Log.e(TAG, "Failed to start streaming. ReturnCode: " + returnCode);
                Log.e(TAG, "Logs: " + session.getAllLogsAsString());
                runOnUiThread(() -> Toast.makeText(this, "Failed to start streaming. Check logs.", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void stopStreaming() {
        if (isStreaming) {
            FFmpegKit.cancel(); // Cancel the FFmpeg process
            isStreaming = false;
            Toast.makeText(this, "Streaming stopped", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Streaming stopped.");
        } else {
            Toast.makeText(this, "No stream to stop", Toast.LENGTH_SHORT).show();
        }
    }

    // Helper method to get the file path from the Uri
    private String getPathFromUri(Uri uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
                    String fileName = cursor.getString(nameIndex);
                    File file = new File(getExternalFilesDir(null), fileName);
                    try (InputStream inputStream = getContentResolver().openInputStream(uri);
                         OutputStream outputStream = new FileOutputStream(file)) {
                        byte[] buffer = new byte[1024];
                        int length;
                        while ((length = inputStream.read(buffer)) > 0) {
                            outputStream.write(buffer, 0, length);
                        }
                    }
                    return file.getAbsolutePath();
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to get path from Uri", e);
            }
        } else {
            String[] projection = {MediaStore.Video.Media.DATA};
            try (Cursor cursor = getContentResolver().query(uri, projection, null, null, null)) {
                if (cursor != null) {
                    int columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA);
                    cursor.moveToFirst();
                    return cursor.getString(columnIndex);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to get path from Uri", e);
            }
        }
        return null;
    }
}