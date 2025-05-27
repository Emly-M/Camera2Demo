package com.mozl.camera2.picture;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.media.ImageReader;
import android.os.Handler;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;

public class PhotoCaptureManager {
    private static final String TAG = "PhotoCaptureManager";
    private ImageReader imageReader;
    private Handler backgroundHandler; // 用于 ImageReader 的回调

    public PhotoCaptureManager() {}

    /**
     * 设置照片捕捉所需的 ImageReader。
     *
     * @param photoSize         期望的照片尺寸。
     * @param listener          当图片可用时的回调监听器。
     * @param backgroundHandler 用于执行监听器回调的 Handler。
     */
    public void setupImageReader(Size photoSize, ImageReader.OnImageAvailableListener listener, Handler backgroundHandler) {
        if (photoSize == null || listener == null || backgroundHandler == null) {
            Log.e(TAG, "setupImageReader: 无效的参数。");
            return;
        }
        this.backgroundHandler = backgroundHandler;
        // 如果之前的 imageReader 存在，先关闭它
        if (imageReader != null) {
            imageReader.close();
        }
        imageReader = ImageReader.newInstance(photoSize.getWidth(), photoSize.getHeight(), ImageFormat.JPEG, 1 /*maxImages*/);
        imageReader.setOnImageAvailableListener(listener, backgroundHandler);
        Log.d(TAG, "ImageReader 设置完成，尺寸: " + photoSize.getWidth() + "x" + photoSize.getHeight());
    }

    /**
     * 获取 ImageReader 的 Surface。
     * 必须在 setupImageReader() 成功调用后调用。
     * @return ImageReader 的 Surface，如果未初始化则返回 null。
     */
    public Surface getImageReaderSurface() {
        if (imageReader != null) {
            return imageReader.getSurface();
        }
        Log.w(TAG, "getImageReaderSurface: ImageReader 未初始化。");
        return null;
    }

    /**
     * 创建用于静态图像捕捉的 CaptureRequest.Builder。
     *
     * @param cameraDevice    当前的 CameraDevice。
     * @param jpegOrientation JPEG 图像的旋转方向。
     * @param targetSurface   ImageReader 的 Surface，作为捕捉目标。
     * @return 配置好的 CaptureRequest.Builder，如果参数无效则返回 null。
     * @throws CameraAccessException 如果访问相机设备失败。
     */
    public CaptureRequest.Builder createStillCaptureRequestBuilder(CameraDevice cameraDevice, int jpegOrientation, Surface targetSurface) throws CameraAccessException {
        if (cameraDevice == null || targetSurface == null) {
            Log.e(TAG, "createStillCaptureRequestBuilder: 无效的参数。");
            return null;
        }
        CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        builder.addTarget(targetSurface);
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        builder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation);
        // 可以根据需要设置其他参数，如闪光灯、对焦模式等
        // builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        // builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
        return builder;
    }

    /**
     * 获取用于拍照的 CameraCaptureSession.CaptureCallback。
     *
     * @param onCompletedRunnable 拍照完成时执行的 Runnable。
     * @param onFailedRunnable    拍照失败时执行的 Runnable。
     * @return CameraCaptureSession.CaptureCallback 实例。
     */
    public CameraCaptureSession.CaptureCallback getCaptureCallback(Runnable onCompletedRunnable, Runnable onFailedRunnable) {
        return new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                super.onCaptureCompleted(session, request, result);
                Log.d(TAG, "拍照请求完成。");
                if (onCompletedRunnable != null) {
                    onCompletedRunnable.run();
                }
            }

            @Override
            public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull android.hardware.camera2.CaptureFailure failure) {
                super.onCaptureFailed(session, request, failure);
                Log.e(TAG, "拍照请求失败，原因: " + failure.getReason());
                if (onFailedRunnable != null) {
                    onFailedRunnable.run();
                }
            }
        };
    }

    /**
     * 释放 ImageReader 资源。
     */
    public void release() {
        if (imageReader != null) {
            try {
                imageReader.close();
            } catch (Exception e) {
                Log.e(TAG, "关闭ImageReader时出错: " + e.getMessage());
            }
            imageReader = null;
            Log.d(TAG, "ImageReader 已释放。");
        }
        backgroundHandler = null; // 清理引用
    }

    /**
     * 检查 ImageReader 是否已被初始化。
     * @return 如果 imageReader 实例不为 null，则为 true。
     */
    public boolean isInitialized() {
        return imageReader != null;
    }
}
