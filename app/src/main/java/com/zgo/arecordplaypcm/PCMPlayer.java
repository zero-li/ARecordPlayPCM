package com.zgo.arecordplaypcm;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Encapsulates PCM playback using AudioTrack.
 */
public class PCMPlayer {

    public interface Listener {
        void onFinished(Exception error, boolean completed);
    }

    private static final String TAG = "PCMPlayer";

    private final int sampleRate;
    private final int channelConfig;
    private final int audioEncoding;
    private final Handler callbackHandler;

    private volatile boolean isPlaying;

    private AudioTrack audioTrack;
    private Thread playbackThread;

    public PCMPlayer(int sampleRate,
                     int channelConfig,
                     int audioEncoding,
                     Handler callbackHandler) {
        this.sampleRate = sampleRate;
        this.channelConfig = channelConfig;
        this.audioEncoding = audioEncoding;
        this.callbackHandler = callbackHandler;
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    /**
     * Start playback for the given PCM file. The listener will be invoked once when finished.
     */
    public boolean start(File file, Listener listener) {
        if (isPlaying) return false;

        int minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioEncoding);
        if (minBufferSize <= 0) {
            notifyFinish(listener, new IOException("Unsupported playback configuration"), false);
            return false;
        }
        int calculatedBufferSize = Math.max(minBufferSize, sampleRate / 2);
        if ((calculatedBufferSize & 1) != 0) calculatedBufferSize++;
        final int bufferSize = calculatedBufferSize;

        AudioTrack track = new AudioTrack(
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                new AudioFormat.Builder()
                        .setEncoding(audioEncoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build(),
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
        );
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            notifyFinish(listener, new IOException("AudioTrack init failed"), false);
            return false;
        }
        audioTrack = track;

        isPlaying = true;
        playbackThread = new Thread(() -> doPlayback(file, bufferSize, listener), "PCMPlayer");
        playbackThread.start();
        return true;
    }

    /** Signal playback to stop and wait for thread to finish. */
    public void stop() {
        if (!isPlaying) return;
        isPlaying = false;
        safeStopAudioTrack();
        Thread t = playbackThread;
        if (t != null) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        playbackThread = null;
    }

    private void doPlayback(File file, int bufferSize, Listener listener) {
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
                        failure = new IOException("AudioTrack write error: " + written);
                        break;
                    }
                }
            }
        } catch (IOException | IllegalStateException e) {
            failure = e;
            Log.e(TAG, "Playback failed", e);
        } finally {
            safeStopAudioTrack();
            safeReleaseAudioTrack();
            isPlaying = false;
            notifyFinish(listener, failure, completed && failure == null);
        }
    }

    private void notifyFinish(Listener listener, Exception error, boolean completed) {
        if (listener == null) return;
        if (callbackHandler != null) {
            callbackHandler.post(() -> listener.onFinished(error, completed));
        } else {
            listener.onFinished(error, completed);
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

    private void safeReleaseAudioTrack() {
        if (audioTrack != null) {
            try {
                audioTrack.release();
            } catch (Exception ignored) {
            }
            audioTrack = null;
        }
    }
}
