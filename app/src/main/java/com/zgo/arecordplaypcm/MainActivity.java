package com.zgo.arecordplaypcm;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private enum State { IDLE, RECORDING, PLAYING }

    private static final String TAG = "MainActivity";

    public static final int MODE_48K = 0;
    public static final int MODE_8K = 1;
    private static final int SAMPLE_RATE_MODE = MODE_48K;
    private static final boolean USE_48K_PIPELINE = SAMPLE_RATE_MODE == MODE_48K;
    private static final int RECORD_SAMPLE_RATE = USE_48K_PIPELINE ? 48000 : 8000;
    private static final int OUTPUT_SAMPLE_RATE = USE_48K_PIPELINE ? 48000 : 8000;
    private static final int CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO;
    private static final int AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final long MIN_FREE_SPACE_BYTES = 1_000_000L;
    private static final String RECORDING_FILE_PREFIX = "rec_";
    private static final String RECORDING_FILE_EXT = ".pcm";

    private static final int REQ_RECORD_AUDIO = 1001;
    private static final boolean RNNOISE_ENABLED = true;

    private TextView tvStatus;
    private Button btnStart;
    private Button btnStop;
    private Button btnPlay;
    private Button btnOpenSettings;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Object recordLock = new Object();
    private final Object playbackLock = new Object();

    private volatile File lastFile;
    private File currentRecordingFile;

    private AudioRecord audioRecord;
    private Thread recordingThread;
    private volatile boolean isRecording;
    private volatile Exception recordingError;
    private boolean recordingFinalized = true;

    private AudioTrack audioTrack;
    private Thread playbackThread;
    private volatile boolean isPlaying;
    private volatile Exception playbackError;
    private volatile boolean playbackCompleted;
    private boolean playbackFinalized = true;

    private State state = State.IDLE;
    private long startTimeMs;
    private int prevAudioMode = AudioManager.MODE_NORMAL;
    private boolean audioModeAdjusted;

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
        btnStop.setOnClickListener(v -> handleStop());
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
//        btnPlay.setEnabled(decOk && getLatestRecording() != null);
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

    private void handleStop() {
        if (state == State.RECORDING) {
            stopRecording();
        } else if (state == State.PLAYING) {
            stopPlayback();
        }
    }

    private void startRecording() {
        if (state != State.IDLE) {
            return;
        }
        if (!checkPerm()) {
            requestPerm();
            return;
        }

        File dir = getRecordingDirectory();
        if (dir == null) {
            setStatus("无法访问存储目录");
            return;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            setStatus("无法创建存储目录");
            return;
        }
        if (dir.getUsableSpace() < MIN_FREE_SPACE_BYTES) {
            setStatus("存储空间不足");
            return;
        }

        String time = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        final File outFile = new File(dir, RECORDING_FILE_PREFIX + time + RECORDING_FILE_EXT);

        int minBufferSize = AudioRecord.getMinBufferSize(RECORD_SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_ENCODING);
        if (minBufferSize <= 0) {
            setStatus("录音配置不被支持");
            return;
        }
        int calculatedBufferSize = Math.max(minBufferSize, RECORD_SAMPLE_RATE / 2);
        if ((calculatedBufferSize & 1) != 0) calculatedBufferSize++;
        final int bufferSize = calculatedBufferSize;

        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, RECORD_SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_ENCODING, bufferSize);
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            setStatus("AudioRecord 初始化失败");
            audioRecord.release();
            audioRecord = null;
            return;
        }

        applyRecordingAudioMode();

        synchronized (recordLock) {
            recordingFinalized = false;
        }
        recordingError = null;
        currentRecordingFile = outFile;
        isRecording = true;
        state = State.RECORDING;
        startTimeMs = System.currentTimeMillis();
        setStatus("录音中... 0s");
        updateUi();
        mainHandler.removeCallbacks(timerRunnable);
        mainHandler.post(timerRunnable);
        try { getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); } catch (Throwable ignored) {}

        recordingThread = new Thread(() -> doRecord(outFile, bufferSize), "PCMRecorder");
        recordingThread.start();
    }

    private void doRecord(File file, int bufferSize) {
        Exception failure = null;
        try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(file))) {
            byte[] buffer = new byte[bufferSize];
            audioRecord.startRecording();
            while (isRecording) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    bos.write(buffer, 0, read);
                } else if (read == 0) {
                    continue;
                } else if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                    failure = new IOException("AudioRecord 读取失败: " + read);
                    break;
                } else {
                    failure = new IOException("AudioRecord 未知错误: " + read);
                    break;
                }
            }
            bos.flush();
        } catch (IOException | IllegalStateException e) {
            failure = e;
            Log.e(TAG, "Recording failed", e);
        } finally {
            recordingError = failure;
            isRecording = false;
            safeStopAudioRecord();
            runOnUiThread(() -> finalizeRecording(file, recordingError));
        }
    }

    private void stopRecording() {
        if (state != State.RECORDING) {
            return;
        }
        isRecording = false;
        safeStopAudioRecord();
        Thread thread = recordingThread;
        if (thread != null) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        finalizeRecording(currentRecordingFile, recordingError);
    }

    private void finalizeRecording(File file, Exception error) {
        synchronized (recordLock) {
            if (recordingFinalized) {
                return;
            }
            recordingFinalized = true;
        }
        mainHandler.removeCallbacks(timerRunnable);
        safeStopAudioRecord();
        releaseAudioRecord();
        recordingThread = null;
        isRecording = false;
        currentRecordingFile = null;
        resetAudioMode();
        try { getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); } catch (Throwable ignored) {}

        String status;
        if (error != null) {
            if (file != null && file.exists() && file.length() == 0) {
                if (!file.delete()) {
                    Log.w(TAG, "Failed to delete empty recording: " + file.getAbsolutePath());
                }
            }
            status = "录音失败: " + formatErrorMessage(error);
        } else if (file != null && file.exists() && file.length() > 0) {
            lastFile = file;
            status = "已保存: " + file.getName();
        } else {
            if (file != null && file.exists()) {
                if (!file.delete()) {
                    Log.w(TAG, "Failed to delete incomplete recording: " + file.getAbsolutePath());
                }
            }
            status = "录音失败: 未捕获到音频";
        }

        state = State.IDLE;
        updateUi();
        setStatus(status);
    }

    private void playLast() {
        if (state != State.IDLE) {
            return;
        }
        final File file = getLatestRecording();
        if (file == null || !file.exists()) {
            setStatus("没有可播放的录音");
            return;
        }
        if (file.length() == 0) {
            setStatus("录音文件为空");
            return;
        }

        int minBufferSize = AudioTrack.getMinBufferSize(OUTPUT_SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_ENCODING);
        if (minBufferSize <= 0) {
            setStatus("播放配置不被支持");
            return;
        }
        int calculatedBufferSize = Math.max(minBufferSize, OUTPUT_SAMPLE_RATE / 2);
        if ((calculatedBufferSize & 1) != 0) calculatedBufferSize++;
        final int bufferSize = calculatedBufferSize;

        AudioTrack track = new AudioTrack(
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                new AudioFormat.Builder()
                        .setEncoding(AUDIO_ENCODING)
                        .setSampleRate(OUTPUT_SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG_OUT)
                        .build(),
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
        );
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            setStatus("AudioTrack 初始化失败");
            return;
        }

        synchronized (playbackLock) {
            playbackFinalized = false;
        }
        audioTrack = track;
        playbackError = null;
        playbackCompleted = false;
        isPlaying = true;
        state = State.PLAYING;
        updateUi();
        setStatus("正在播放...");

        playbackThread = new Thread(() -> doPlayback(file, bufferSize), "PCMPlayer");
        playbackThread.start();
    }

    private void doPlayback(File file, int bufferSize) {
        Exception failure = null;
        boolean completed = false;
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[bufferSize];
            audioTrack.play();
            while (isPlaying) {
                int read = bis.read(buffer);
                if (read == -1) {
                    completed = true;
                    break;
                }
                if (read > 0) {
                    int written = audioTrack.write(buffer, 0, read);
                    if (written < 0) {
                        failure = new IOException("AudioTrack 写入错误: " + written);
                        break;
                    }
                }
            }
        } catch (IOException | IllegalStateException e) {
            failure = e;
            Log.e(TAG, "Playback failed", e);
        } finally {
            playbackError = failure;
            playbackCompleted = completed && failure == null;
            safeStopAudioTrack();
            isPlaying = false;
            runOnUiThread(() -> finalizePlayback(playbackError, playbackCompleted));
        }
    }

    private void stopPlayback() {
        if (state != State.PLAYING) {
            return;
        }
        isPlaying = false;
        safeStopAudioTrack();
        Thread thread = playbackThread;
        if (thread != null) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        finalizePlayback(playbackError, playbackCompleted);
    }

    private void finalizePlayback(Exception error, boolean completed) {
        synchronized (playbackLock) {
            if (playbackFinalized) {
                return;
            }
            playbackFinalized = true;
        }
        releaseAudioTrack();
        playbackThread = null;
        isPlaying = false;
        if (state == State.PLAYING) {
            state = State.IDLE;
        }
        updateUi();

        if (error != null) {
            setStatus("播放失败: " + formatErrorMessage(error));
        } else if (completed) {
            setStatus("播放完成");
        } else {
            setStatus("播放已停止");
        }
    }

    private void safeStopAudioRecord() {
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
            } catch (IllegalStateException ignored) {
            }
        }
    }

    private void releaseAudioRecord() {
        if (audioRecord != null) {
            try {
                audioRecord.release();
            } catch (Exception e) {
                Log.w(TAG, "Failed to release AudioRecord", e);
            }
            audioRecord = null;
        }
    }

    private void safeStopAudioTrack() {
        if (audioTrack != null) {
            try {
                if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING ||
                        audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PAUSED) {
                    audioTrack.stop();
                }
            } catch (IllegalStateException ignored) {
            }
            try {
                audioTrack.flush();
            } catch (IllegalStateException ignored) {
            }
        }
    }

    private void releaseAudioTrack() {
        if (audioTrack != null) {
            try {
                audioTrack.release();
            } catch (Exception e) {
                Log.w(TAG, "Failed to release AudioTrack", e);
            }
            audioTrack = null;
        }
    }

    private void applyRecordingAudioMode() {
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager == null) {
            audioModeAdjusted = false;
            return;
        }
        prevAudioMode = audioManager.getMode();
        try {
            audioManager.setMode(USE_48K_PIPELINE ? AudioManager.MODE_IN_COMMUNICATION : AudioManager.MODE_NORMAL);
            audioModeAdjusted = true;
        } catch (SecurityException e) {
            Log.w(TAG, "Failed to set audio mode", e);
            audioModeAdjusted = false;
        }
    }

    private void resetAudioMode() {
        if (!audioModeAdjusted) {
            return;
        }
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager != null) {
            try {
                audioManager.setMode(prevAudioMode);
            } catch (SecurityException e) {
                Log.w(TAG, "Failed to reset audio mode", e);
            }
        }
        audioModeAdjusted = false;
    }

    private String formatErrorMessage(Exception error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = error.toString();
        }
        return message;
    }

    private File getLatestRecording() {
        File dir = getRecordingDirectory();
        File latest = null;
        if (dir != null && dir.exists()) {
            File[] files = dir.listFiles((d, name) -> name != null && name.endsWith(RECORDING_FILE_EXT));
            if (files != null && files.length > 0) {
                latest = files[0];
                for (File f : files) {
                    if (f.lastModified() > latest.lastModified()) {
                        latest = f;
                    }
                }
            }
        }
        if (latest != null && latest.exists()) {
            return latest;
        }
        if (lastFile != null && lastFile.exists()) {
            return lastFile;
        }
        return null;
    }

    private File getRecordingDirectory() {
        File dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (dir == null) dir = getFilesDir();
        return dir;
    }

    private void setStatus(String s) {
        tvStatus.setText(s);
    }

    private void updateUi() {
        boolean hasPerm = checkPerm();
        btnOpenSettings.setVisibility(hasPerm ? View.GONE : View.VISIBLE);

        boolean canStart = state == State.IDLE;
        boolean canStop = state == State.RECORDING || state == State.PLAYING;
        boolean canPlay = state == State.IDLE && getLatestRecording() != null;

        btnStart.setEnabled(canStart);
        btnStop.setEnabled(canStop);
        btnPlay.setEnabled(canPlay);
        btnStop.setText(state == State.PLAYING ? "停止播放" : "停止录音");

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
        if (state == State.RECORDING) {
            stopRecording();
        } else if (state == State.PLAYING) {
            stopPlayback();
        }
        try { getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); } catch (Throwable ignored) {}
    }

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
