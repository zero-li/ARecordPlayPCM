package com.zgo.arecordplaypcm;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.util.Log;

import com.zgo.recordplayer.audio.RnnoiseProcessor;

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
    private final boolean enableNoiseSuppression; // RNNoise integration toggle
    private final Handler callbackHandler; // Post callbacks on this handler if not null

    private volatile boolean isRecording;

    private AudioRecord audioRecord;
    private Thread recordingThread;

    // RNNoise processor instance; created when recording starts if enabled
    private RnnoiseProcessor rnnoiseProcessor;

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
            // Prepare buffers. We read shorts (PCM16) from AudioRecord.
            final int shortsPerBuffer = Math.max(1, bufferSize / 2);
            short[] readBuffer = new short[shortsPerBuffer];

            // Frame-based processing for RNNoise (480-sample frames at 48 kHz)
            short[] frameBuffer = new short[RnnoiseProcessor.FRAME_SIZE];
            int frameFill = 0;
            short[] denoisedFrame = null; // allocated lazily when RNNoise is active
            short[] decimatedOut = null;  // required by JNI; contents unused here

            // Reusable byte buffers for writing to file
            byte[] rawWriteBuffer = new byte[shortsPerBuffer * 2];
            byte[] frameWriteBuffer = new byte[RnnoiseProcessor.FRAME_SIZE * 2];

            // Initialize RNNoise if requested
            boolean useRnnoise = false;
            if (enableNoiseSuppression) {
                try {
                    rnnoiseProcessor = new RnnoiseProcessor(true);
                    useRnnoise = true;
                    Log.i(TAG, "RNNoise enabled");
                } catch (Throwable e) {
                    rnnoiseProcessor = null;
                    useRnnoise = false;
                    Log.e(TAG, "Failed to initialize RNNoise; falling back to raw audio", e);
                }
            }

            audioRecord.startRecording();
            while (isRecording) {
                int read = audioRecord.read(readBuffer, 0, readBuffer.length);
                if (read > 0) {
                    if (useRnnoise && rnnoiseProcessor != null) {
                        int idx = 0;
                        while (idx < read) {
                            int toCopy = Math.min(RnnoiseProcessor.FRAME_SIZE - frameFill, read - idx);
                            System.arraycopy(readBuffer, idx, frameBuffer, frameFill, toCopy);
                            frameFill += toCopy;
                            idx += toCopy;

                            if (frameFill == RnnoiseProcessor.FRAME_SIZE) {
                                if (denoisedFrame == null) denoisedFrame = new short[RnnoiseProcessor.FRAME_SIZE];
                                if (decimatedOut == null) decimatedOut = new short[RnnoiseProcessor.DECIMATED_FRAME_SIZE];
                                try {
                                    rnnoiseProcessor.processFrame(frameBuffer, denoisedFrame, decimatedOut);
                                    shortsToLittleEndianBytes(denoisedFrame, 0, RnnoiseProcessor.FRAME_SIZE, frameWriteBuffer);
                                    bos.write(frameWriteBuffer, 0, frameWriteBuffer.length);
                                } catch (Exception e) {
                                    Log.e(TAG, "RNNoise processing failed, switching to raw audio", e);
                                    // Fallback: write the unprocessed frame we accumulated
                                    shortsToLittleEndianBytes(frameBuffer, 0, RnnoiseProcessor.FRAME_SIZE, frameWriteBuffer);
                                    bos.write(frameWriteBuffer, 0, frameWriteBuffer.length);
                                    // Disable RNNoise for the remainder of this recording
                                    try { rnnoiseProcessor.close(); } catch (Throwable ignored) {}
                                    rnnoiseProcessor = null;
                                    useRnnoise = false;
                                } finally {
                                    frameFill = 0;
                                }
                            }
                        }
                    } else {
                        // Passthrough: write captured shorts directly
                        shortsToLittleEndianBytes(readBuffer, 0, read, rawWriteBuffer);
                        bos.write(rawWriteBuffer, 0, read * 2);
                    }
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

            // Flush any partial frame on stop when RNNoise active by zero-padding
            if (rnnoiseProcessor != null && frameFill > 0) {
                for (int i = frameFill; i < RnnoiseProcessor.FRAME_SIZE; i++) frameBuffer[i] = 0;
                if (denoisedFrame == null) denoisedFrame = new short[RnnoiseProcessor.FRAME_SIZE];
                if (decimatedOut == null) decimatedOut = new short[RnnoiseProcessor.DECIMATED_FRAME_SIZE];
                try {
                    rnnoiseProcessor.processFrame(frameBuffer, denoisedFrame, decimatedOut);
                    shortsToLittleEndianBytes(denoisedFrame, 0, RnnoiseProcessor.FRAME_SIZE, frameWriteBuffer);
                    bos.write(frameWriteBuffer, 0, frameWriteBuffer.length);
                } catch (Exception e) {
                    Log.e(TAG, "RNNoise processing failed during flush; writing raw partial", e);
                    shortsToLittleEndianBytes(frameBuffer, 0, frameFill, rawWriteBuffer);
                    bos.write(rawWriteBuffer, 0, frameFill * 2);
                }
                frameFill = 0;
            }

            bos.flush();
        } catch (IOException | IllegalStateException e) {
            failure = e;
            Log.e(TAG, "Recording failed", e);
        } finally {
            safeStopAudioRecord();
            safeReleaseAudioRecord();
            if (rnnoiseProcessor != null) {
                try { rnnoiseProcessor.close(); } catch (Throwable ignored) {}
                rnnoiseProcessor = null;
            }
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

    private static void shortsToLittleEndianBytes(short[] src, int srcOffset, int lengthInShorts, byte[] dest) {
        int di = 0;
        for (int i = 0; i < lengthInShorts; i++) {
            short v = src[srcOffset + i];
            dest[di++] = (byte) (v & 0xff);
            dest[di++] = (byte) ((v >> 8) & 0xff);
        }
    }
}
