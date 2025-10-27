package com.zgo.arecordplaypcm;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Encapsulates PCM audio recording using AudioRecord.
 */
public class PCMRecorder {

    public interface Listener {
        void onFinished(File outputFile, Exception error);
    }

    private static final String TAG = "PCMRecorder";

    private final int sampleRate;
    private final int channelConfig;
    private final int audioEncoding;
    private final boolean enableNoiseSuppression; // Placeholder for RNNoise integration
    private final Handler callbackHandler; // Post callbacks on this handler if not null

    private volatile boolean isRecording;

    private AudioRecord audioRecord;
    private Thread recordingThread;

    public PCMRecorder(int sampleRate,
                       int channelConfig,
                       int audioEncoding,
                       boolean enableNoiseSuppression,
                       Handler callbackHandler) {
        this.sampleRate = sampleRate;
        this.channelConfig = channelConfig;
        this.audioEncoding = audioEncoding;
        this.enableNoiseSuppression = enableNoiseSuppression;
        this.callbackHandler = callbackHandler;
    }

    public boolean isRecording() {
        return isRecording;
    }

    /**
     * Start recording into the provided PCM file. The listener will be invoked once when
     * recording finishes or fails.
     */
    public boolean start(File outFile, Listener listener) {
        if (isRecording) return false;

        int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding);
        if (minBufferSize <= 0) {
            notifyFinish(listener, outFile, new IOException("Unsupported recording configuration"));
            return false;
        }
        int calculatedBufferSize = Math.max(minBufferSize, sampleRate / 2);
        if ((calculatedBufferSize & 1) != 0) calculatedBufferSize++;
        final int bufferSize = calculatedBufferSize;

        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioEncoding, bufferSize);
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            safeReleaseAudioRecord();
            notifyFinish(listener, outFile, new IOException("AudioRecord init failed"));
            return false;
        }

        isRecording = true;
        recordingThread = new Thread(() -> doRecord(outFile, bufferSize, listener), "PCMRecorder");
        recordingThread.start();
        return true;
    }

    /**
     * Signal the recording thread to stop and wait for it to finish.
     */
    public void stop() {
        if (!isRecording) return;
        isRecording = false;
        safeStopAudioRecord();
        Thread t = recordingThread;
        if (t != null) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        recordingThread = null;
    }

    private void doRecord(File file, int bufferSize, Listener listener) {
        Exception failure = null;
        try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(file))) {
            byte[] buffer = new byte[bufferSize];
            audioRecord.startRecording();
            while (isRecording) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    // Placeholder for RNNoise or additional processing
                    if (enableNoiseSuppression) {
                        // No-op for now: pass-through
                    }
                    bos.write(buffer, 0, read);
                } else if (read == 0) {
                    continue;
                } else if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                    failure = new IOException("AudioRecord read failed: " + read);
                    break;
                } else {
                    failure = new IOException("AudioRecord unknown error: " + read);
                    break;
                }
            }
            bos.flush();
        } catch (IOException | IllegalStateException e) {
            failure = e;
            Log.e(TAG, "Recording failed", e);
        } finally {
            safeStopAudioRecord();
            safeReleaseAudioRecord();
            isRecording = false;
            notifyFinish(listener, file, failure);
        }
    }

    private void notifyFinish(Listener listener, File file, Exception error) {
        if (listener == null) return;
        if (callbackHandler != null) {
            callbackHandler.post(() -> listener.onFinished(file, error));
        } else {
            listener.onFinished(file, error);
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

    private void safeReleaseAudioRecord() {
        if (audioRecord != null) {
            try {
                audioRecord.release();
            } catch (Exception ignored) {
            }
            audioRecord = null;
        }
    }
}
