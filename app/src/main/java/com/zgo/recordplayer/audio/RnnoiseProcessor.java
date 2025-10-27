package com.zgo.recordplayer.audio;

import androidx.annotation.Nullable;

/**
 * Thin Java wrapper around the RNNoise JNI bridge that owns the native handle
 * and processes 10 ms (480-sample) frames of PCM16 audio.
 */
public final class RnnoiseProcessor implements AutoCloseable {

    public static final int FRAME_SIZE = 480; // samples @ 48 kHz => 10 ms
    public static final int DECIMATED_FRAME_SIZE = FRAME_SIZE * 2 / 6; // 160 samples @ 8 kHz (20 ms window)


    static {
        System.loadLibrary("rnnoise");
    }

    private long nativeHandle;
    private final boolean denoiserEnabled;


    public RnnoiseProcessor(boolean enableDenoiser) {
        this.denoiserEnabled = enableDenoiser;
        this.nativeHandle = nativeCreate(enableDenoiser);
        if (nativeHandle == 0L) {
            throw new IllegalStateException("Failed to initialize RNNoise processor");
        }
    }

    public boolean isReleased() {
        return nativeHandle == 0L;
    }

    public boolean isDenoiserEnabled() {
        return denoiserEnabled;
    }


    /**
     * Processes a single 10 ms frame of audio.
     *
     * @param inputFrame     480-sample PCM16 data captured at 48 kHz.
     * @param denoisedOutput Optional array (length >= 480) that receives the denoised
     *                       48 kHz PCM16 output. Pass {@code null} to skip.
     * @param decimatedOut   Destination array (length >= 160) for the 8 kHz PCM16 output.
     * @return Number of 8 kHz samples written to {@code decimatedOut} (0 on the first call, 160 on the next).
     */
    public int processFrame(short[] inputFrame, @Nullable short[] denoisedOutput, short[] decimatedOut) {
        ensureOpen();
        if (inputFrame == null || inputFrame.length != FRAME_SIZE) {
            throw new IllegalArgumentException("inputFrame must be exactly " + FRAME_SIZE + " samples");
        }
        if (decimatedOut == null || decimatedOut.length < DECIMATED_FRAME_SIZE) {
            throw new IllegalArgumentException("decimatedOut must have length >= " + DECIMATED_FRAME_SIZE);
        }
        if (denoisedOutput != null && denoisedOutput.length < FRAME_SIZE) {
            throw new IllegalArgumentException("denoisedOutput must have length >= " + FRAME_SIZE);
        }
        int result = nativeProcessFrame(nativeHandle, inputFrame, denoisedOutput, decimatedOut);
        if (result < 0) {
            throw new IllegalStateException("RNNoise native processing failed with code " + result);
        }
        return result;
    }




    @Override
    public void close() {
        release();
    }

    public void release() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle);
            nativeHandle = 0L;
        }
    }

    private void ensureOpen() {
        if (nativeHandle == 0L) {
            throw new IllegalStateException("RNNoise processor already released");
        }
    }

    private static native long nativeCreate(boolean enableDenoiser);

    private static native int nativeProcessFrame(long handle, short[] inputFrame, short[] denoisedOutput, short[] decimatedOutput);

    private static native void nativeDestroy(long handle);
}
