package com.mozl.camera2.vedio;

import android.content.Context;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.io.File;
import java.io.IOException;

public class VideoRecorderManager {
    private static final String TAG = "VideoRecorderManager";
    private MediaRecorder mediaRecorder;
    private Context context;

    public VideoRecorderManager(Context context) {
        this.context = context.getApplicationContext(); // 使用 ApplicationContext 避免内存泄漏
    }

    /**
     * 初始化并配置 MediaRecorder。
     *
     * @param currentCameraId  当前相机ID，用于获取 CamcorderProfile。
     * @param videoSize        期望的视频尺寸。
     * @param outputFile       视频输出文件。
     * @param orientationHint  视频的旋转方向提示。
     * @param audioSource      音频源，例如 MediaRecorder.AudioSource.MIC。
     * @param videoSource      视频源，例如 MediaRecorder.VideoSource.SURFACE。
     * @throws IOException 如果 MediaRecorder 配置失败。
     */
    public void setUpMediaRecorder(String currentCameraId, Size videoSize, File outputFile, int orientationHint,
                                   int audioSource, int videoSource) throws IOException {
        if (currentCameraId == null || videoSize == null || outputFile == null) {
            throw new IOException("setUpMediaRecorder: 无效的参数。");
        }

        mediaRecorder = new MediaRecorder();
        mediaRecorder.setAudioSource(audioSource);
        mediaRecorder.setVideoSource(videoSource);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);

        mediaRecorder.setOutputFile(outputFile.getAbsolutePath());

        // 尝试使用 CamcorderProfile 获取推荐配置
        // 注意：CamcorderProfile.get() 需要一个有效的 camera ID (int).
        // 如果 currentCameraId 是 String, 需要转换。假设它是可以转换为 int 的。
        int cameraIdInt;
        try {
            cameraIdInt = Integer.parseInt(currentCameraId);
        } catch (NumberFormatException e) {
            Log.w(TAG, "无法将相机ID解析为整数以用于CamcorderProfile: " + currentCameraId + ". 使用默认值。");
            // 如果无法解析，则使用默认值或不使用profile
            cameraIdInt = 0; // 或者一个已知的有效后置摄像头ID的整数形式
        }

        CamcorderProfile profile = null;
        if (CamcorderProfile.hasProfile(cameraIdInt, CamcorderProfile.QUALITY_HIGH)) {
            profile = CamcorderProfile.get(cameraIdInt, CamcorderProfile.QUALITY_HIGH);
        } else if (CamcorderProfile.hasProfile(cameraIdInt, CamcorderProfile.QUALITY_LOW)) { // 尝试低质量作为备选
            profile = CamcorderProfile.get(cameraIdInt, CamcorderProfile.QUALITY_LOW);
        }


        int videoBitRate = (profile != null && profile.videoBitRate > 0) ? profile.videoBitRate : 10 * 1024 * 1024; // 默认 10Mbps
        int videoFrameRate = (profile != null && profile.videoFrameRate > 0) ? profile.videoFrameRate : 30;     // 默认 30fps

        mediaRecorder.setVideoEncodingBitRate(videoBitRate);
        mediaRecorder.setVideoFrameRate(videoFrameRate);
        mediaRecorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);

        mediaRecorder.setOrientationHint(orientationHint);

        try {
            mediaRecorder.prepare();
            Log.d(TAG, "MediaRecorder 配置完成。");
        } catch (IOException e) {
            Log.e(TAG, "MediaRecorder.prepare() 失败", e);
            release(); // 准备失败时释放资源
            throw e; // 重新抛出异常，让调用者知道
        }
    }

    /**
     * 开始录制。
     * 必须在 setUpMediaRecorder() 和 prepare() 成功调用后调用。
     * @throws IllegalStateException 如果在不正确的状态下调用。
     */
    public void start() throws IllegalStateException {
        if (mediaRecorder != null) {
            mediaRecorder.start();
            Log.d(TAG, "MediaRecorder 已开始录制。");
        } else {
            throw new IllegalStateException("MediaRecorder 未初始化。");
        }
    }

    /**
     * 停止录制。
     * @throws IllegalStateException 如果在不正确的状态下调用。
     */
    public void stop() throws IllegalStateException {
        if (mediaRecorder != null) {
            try {
                mediaRecorder.stop();
                Log.d(TAG, "MediaRecorder 已停止录制。");
            } catch (RuntimeException e) {
                // MediaRecorder.stop() 可能会在某些情况下（例如，没有有效数据被录制）抛出 RuntimeException
                Log.w(TAG, "MediaRecorder.stop() 抛出 RuntimeException: " + e.getMessage() + ". 可能没有有效数据被录制。");
                // 即使stop失败，也应该尝试reset和release
            }
        } else {
            // 如果 mediaRecorder 为 null，可能已经是被释放的状态，或者从未成功初始化
            Log.w(TAG, "尝试停止一个为null的MediaRecorder实例。");
        }
    }

    /**
     * 重置并释放 MediaRecorder 资源。
     */
    public void release() {
        if (mediaRecorder != null) {
            try {
                mediaRecorder.reset();   // 重置 MediaRecorder 到未初始化状态
                mediaRecorder.release(); // 释放 MediaRecorder 对象
            } catch (Exception e) {
                Log.e(TAG, "释放MediaRecorder时出错: " + e.getMessage());
            } finally {
                mediaRecorder = null;
                Log.d(TAG, "MediaRecorder 已释放。");
            }
        }
    }

    /**
     * 获取 MediaRecorder 的输入 Surface。
     * 必须在 setUpMediaRecorder() 成功调用后，但在 start() 之前或之后（取决于具体用例）调用。
     * @return MediaRecorder 的 Surface，如果 MediaRecorder 未初始化则返回 null。
     */
    public Surface getRecorderSurface() {
        if (mediaRecorder != null) {
            return mediaRecorder.getSurface();
        }
        return null;
    }

    /**
     * 检查 MediaRecorder 是否已被初始化。
     * @return 如果 mediaRecorder 实例不为 null，则为 true。
     */
    public boolean isInitialized() {
        return mediaRecorder != null;
    }
}
