package com.zgo.arecordplaypcm;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity{

    private enum State { IDLE, RECORDING, PLAYING }

    private static final String TAG = "MainActivity";

    public static final int MODE_48K = 0;
    public static final int MODE_8K = 1;
    private static final int SAMPLE_RATE_MODE = MODE_48K;
    private static final boolean USE_48K_PIPELINE = SAMPLE_RATE_MODE == MODE_48K;
    private static final int RECORD_SAMPLE_RATE = USE_48K_PIPELINE ? 48000 : 8000;
    private static final int OUTPUT_SAMPLE_RATE = 8000;

    private static final int REQ_RECORD_AUDIO = 1001;
    private static final boolean RNNOISE_ENABLED = true;

    private TextView tvStatus;
    private Button btnStart;
    private Button btnStop;
    private Button btnPlay;
    private Button btnOpenSettings;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());


    private volatile File lastFile;

    private State state = State.IDLE;
    private long startTimeMs;
    private int prevAudioMode = AudioManager.MODE_NORMAL;
    private final Runnable timerRunnable = new Runnable() {
        @Override public void run() {
            if (state == State.RECORDING) {
                long sec = (System.currentTimeMillis() - startTimeMs) / 1000L;
                setStatus("录音中... " + sec + "s");
                mainHandler.postDelayed(this, 1000);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.i(TAG, "Sample rate mode: " + (USE_48K_PIPELINE ? "MODE_48K" : "MODE_8K"));
        Log.i(TAG, "Record sample rate: " + RECORD_SAMPLE_RATE + " Hz");
        Log.i(TAG, "Output sample rate: " + OUTPUT_SAMPLE_RATE + " Hz");

        tvStatus = findViewById(R.id.tvStatus);
        btnStart = findViewById(R.id.btnStartRecord);
        btnStop = findViewById(R.id.btnStopRecord);
        btnPlay = findViewById(R.id.btnPlay);
        btnOpenSettings = findViewById(R.id.btnOpenSettings);



        btnStart.setOnClickListener(v -> {
            if (!checkPerm()) requestPerm();
            else startRecording();
        });
        btnStop.setOnClickListener(v -> stopRecording());
        btnPlay.setOnClickListener(v -> playLast());
        btnOpenSettings.setOnClickListener(v -> openAppSettings());

        updateFeatureAvailability();
        updateUi();

        // Handle system back using OnBackPressedDispatcher for predictive back
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {

            }
        });
    }

    private void updateFeatureAvailability() {
//        boolean encOk = recorder.isAmrNbEncoderAvailable();
//        boolean decOk = player.isDecoderAvailable();
//        btnStart.setEnabled(encOk);
//        btnPlay.setEnabled(decOk && getLatestAmr() != null);
//        if (!encOk || !decOk) {
//            setStatus("设备不支持AMR-NB 编解码");
//        }
    }

    private boolean checkPerm() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPerm() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", getPackageName(), null));
        startActivity(intent);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                btnOpenSettings.setVisibility(View.GONE);
                startRecording();
            } else {
                setStatus("麦克风权限被拒绝");
                btnOpenSettings.setVisibility(View.VISIBLE);
                btnOpenSettings.requestFocus();
            }
        }
    }

    private void startRecording() {

    }


    private void stopRecording() {

    }

    private void playLast() {

    }

    private File getLatestAmr() {
        File dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (dir == null) dir = getFilesDir();
        File[] files = dir.listFiles((d, name) -> name != null && name.endsWith(".amr"));
        if (files == null || files.length == 0) return lastFile;
        File latest = files[0];
        for (File f : files) {
            if (f.lastModified() > latest.lastModified()) latest = f;
        }
        return latest;
    }

    private void setStatus(String s) {
        tvStatus.setText(s);
    }

    private void updateUi() {
        boolean hasPerm = checkPerm();
        btnOpenSettings.setVisibility(hasPerm ? View.GONE : View.VISIBLE);

        boolean canStart = state == State.IDLE;
        boolean canStop = state == State.RECORDING;
        boolean canPlay = state == State.IDLE && getLatestAmr() != null;

        btnStart.setEnabled(canStart);
        btnStop.setEnabled(canStop);
        btnPlay.setEnabled(canPlay);

        // Focus first available item for DPAD
        focusFirstAvailable();
    }

    private void focusFirstAvailable() {
        if (btnStart.isEnabled()) { btnStart.requestFocus(); return; }
        if (btnStop.isEnabled()) { btnStop.requestFocus(); return; }
        if (btnPlay.isEnabled()) { btnPlay.requestFocus(); return; }
        if (btnOpenSettings.getVisibility() == View.VISIBLE) { btnOpenSettings.requestFocus(); }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // If we return from settings, update permission-dependent UI
        updateUi();
    }

    @Override
    protected void onStop() {
        super.onStop();
        try { getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); } catch (Throwable ignored) {}
        state = State.IDLE;
        updateUi();
    }

//    @Override
//    public void onError(Exception e) {
//        runOnUiThread(() -> setStatus("错误: " + e.getMessage()));
//    }
//
//    @Override
//    public void onStopped(File file) {
//        runOnUiThread(() -> setStatus("已保存: " + (file != null ? file.getName() : "")));
//    }
//
//    @Override
//    public void onPlayCompleted() {
//        runOnUiThread(() -> {
//            state = State.IDLE;
//            updateUi();
//            setStatus("播放完成");
//        });
//    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Debounce long-press repeats
        if (event.getRepeatCount() > 0) {
            return true;
        }
        View current = getCurrentFocus();
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            {
                if (current == null) { focusFirstAvailable(); return true; }
                View next = current.focusSearch(View.FOCUS_UP);
                if (next != null) next.requestFocus();
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_DOWN:
            {
                if (current == null) { focusFirstAvailable(); return true; }
                View next = current.focusSearch(View.FOCUS_DOWN);
                if (next != null) next.requestFocus();
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER: {
                if (current != null) {
                    current.performClick();
                    return true;
                }
                break;
            }
        }
        return super.onKeyDown(keyCode, event);
    }
}
