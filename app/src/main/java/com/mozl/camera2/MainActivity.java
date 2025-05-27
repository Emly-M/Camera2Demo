package com.mozl.camera2; // 替换为你的包名

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image; // ImageReader 回调中仍需
import android.media.ImageReader; // ImageReader 回调中仍需
import android.media.MediaRecorder; // 用于指定音频源等常量
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.mozl.camera2.picture.PhotoCaptureManager;
import com.mozl.camera2.vedio.VideoRecorderManager;
import com.mozl.camera2.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "Camera2App";
    private static final int REQUEST_CAMERA_AUDIO_PERMISSION_CODE = 201;

    private TextureView textureView;
    private Button btnCapture;
    private Button btnRecordVideo;
    private ImageButton btnSwitchCamera;

    private String currentCameraId;
    private String frontCameraId;
    private String backCameraId;
    private Integer currentLensFacing = CameraCharacteristics.LENS_FACING_BACK;

    protected CameraDevice cameraDevice;
    protected CameraCaptureSession cameraCaptureSessions; // 当前活动的会话 (预览或录像)
    protected CaptureRequest.Builder previewRequestBuilder;
    private Size previewSize;
    private Size videoSize;
    private Size photoSize;

    private File currentPhotoFile;
    private File currentVideoFile;

    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;

    private VideoRecorderManager videoRecorderManager;
    private PhotoCaptureManager photoCaptureManager;

    private boolean isRecordingVideo = false;

    // 不使用这个 SparseIntArray 来计算JPEG方向，直接使用角度
    // private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    // static {
    //     ORIENTATIONS.append(Surface.ROTATION_0, 90);
    //     ORIENTATIONS.append(Surface.ROTATION_90, 0);
    //     ORIENTATIONS.append(Surface.ROTATION_180, 270);
    //     ORIENTATIONS.append(Surface.ROTATION_270, 180);
    // }

    private Semaphore cameraOpenCloseLock = new Semaphore(1);
    private static final String APP_MEDIA_SUBDIRECTORY = "Camera2AppExample";


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textureView = findViewById(R.id.textureView);
        btnCapture = findViewById(R.id.btnCapture);
        btnRecordVideo = findViewById(R.id.btnRecordVideo);
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera);

        if (textureView == null) {
            Log.e(TAG, "TextureView is null! Check layout file.");
            Toast.makeText(this, "发生错误，无法初始化预览", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        textureView.setSurfaceTextureListener(textureListener);

        videoRecorderManager = new VideoRecorderManager(this);
        photoCaptureManager = new PhotoCaptureManager();

        btnCapture.setOnClickListener(v -> {
            if (checkRequiredPermissions()) {
                takePicture();
            }
        });

        btnRecordVideo.setOnClickListener(v -> {
            if (checkRequiredPermissions()) {
                if (isRecordingVideo) {
                    stopRecordingVideo();
                } else {
                    startRecordingVideo();
                }
            }
        });

        btnSwitchCamera.setOnClickListener(v -> {
            if (isRecordingVideo) {
                Toast.makeText(this, "正在录像，无法切换摄像头", Toast.LENGTH_SHORT).show();
                return;
            }
            switchCamera();
        });

        findCameraIds();
    }

    private void findCameraIds() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) { Log.e(TAG, "CameraManager not available."); Toast.makeText(this, "无法访问相机服务", Toast.LENGTH_LONG).show(); finish(); return; }
        try {
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (lensFacing != null) {
                    if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) frontCameraId = cameraId;
                    else if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) backCameraId = cameraId;
                }
            }
            currentCameraId = (currentLensFacing == CameraCharacteristics.LENS_FACING_BACK && backCameraId != null) ? backCameraId : frontCameraId;
            if (currentCameraId == null && backCameraId != null) currentCameraId = backCameraId;
            if (currentCameraId == null && frontCameraId != null) currentCameraId = frontCameraId;

            if (currentCameraId == null) { Log.e(TAG, "未找到可用相机。"); Toast.makeText(this, "未找到可用相机", Toast.LENGTH_LONG).show(); btnCapture.setEnabled(false); btnRecordVideo.setEnabled(false); btnSwitchCamera.setEnabled(false); return; }
            btnSwitchCamera.setVisibility((frontCameraId != null && backCameraId != null) ? View.VISIBLE : View.GONE);
        } catch (CameraAccessException e) { Log.e(TAG, "无法访问相机以获取ID。", e); }
    }

    private void switchCamera() {
        if (isRecordingVideo) { Toast.makeText(this, "正在录像，请先停止", Toast.LENGTH_SHORT).show(); return; }
        if (frontCameraId == null || backCameraId == null) { Toast.makeText(this, "摄像头切换不可用", Toast.LENGTH_SHORT).show(); return; }
        currentLensFacing = (currentLensFacing == CameraCharacteristics.LENS_FACING_BACK) ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
        currentCameraId = (currentLensFacing == CameraCharacteristics.LENS_FACING_BACK) ? backCameraId : frontCameraId;
        Log.d(TAG, "切换到相机 ID: " + currentCameraId);
        closeCamera();
        if (textureView.isAvailable()) openCamera(textureView.getWidth(), textureView.getHeight());
        else textureView.setSurfaceTextureListener(textureListener);
    }

    private boolean checkRequiredPermissions() {
        List<String> listPermissionsNeeded = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) listPermissionsNeeded.add(Manifest.permission.CAMERA);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) listPermissionsNeeded.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) listPermissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (!listPermissionsNeeded.isEmpty()) { ActivityCompat.requestPermissions(this, listPermissionsNeeded.toArray(new String[0]), REQUEST_CAMERA_AUDIO_PERMISSION_CODE); return false; }
        return true;
    }

    TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) { Log.d(TAG, "SurfaceTextureAvailable."); if (currentCameraId == null) findCameraIds(); if (currentCameraId != null && checkRequiredPermissions()) openCamera(width, height); }
        @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {}
        @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) { return true; }
        @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
    };

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(@NonNull CameraDevice camera) { cameraOpenCloseLock.release(); Log.i(TAG, "相机 " + camera.getId() + " onOpened"); cameraDevice = camera; createCameraPreviewSession(); }
        @Override public void onDisconnected(@NonNull CameraDevice camera) { cameraOpenCloseLock.release(); Log.w(TAG, "相机 " + camera.getId() + " onDisconnected"); if (camera != null) camera.close(); cameraDevice = null; }
        @Override public void onError(@NonNull CameraDevice camera, int error) { cameraOpenCloseLock.release(); Log.e(TAG, "相机 " + camera.getId() + " onError: " + error); if (camera != null) camera.close(); cameraDevice = null; if (!isFinishing()) finish(); }
    };

    protected void startBackgroundThread() { mBackgroundThread = new HandlerThread("CameraBackground"); mBackgroundThread.start(); mBackgroundHandler = new Handler(mBackgroundThread.getLooper()); }
    protected void stopBackgroundThread() { if (mBackgroundThread != null) { mBackgroundThread.quitSafely(); try { mBackgroundThread.join(500); mBackgroundThread = null; mBackgroundHandler = null; } catch (InterruptedException e) { Log.e(TAG, "stopBackgroundThread: Interrupted", e); } } }

    private void takePicture() {
        if (null == cameraDevice || photoSize == null) {
            Log.e(TAG, "cameraDevice 或 photoSize 为 null, 无法拍照。");
            Toast.makeText(this, "相机未准备好拍照", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraDevice.getId());

            ImageReader.OnImageAvailableListener readerListener = reader -> {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image != null) {
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.capacity()];
                        buffer.get(bytes);
                        saveImageToGallery(bytes, currentPhotoFile, false);
                    }
                } finally {
                    if (image != null) image.close();
                }
            };

            photoCaptureManager.setupImageReader(photoSize, readerListener, mBackgroundHandler);
            Surface photoReaderSurface = photoCaptureManager.getImageReaderSurface();
            if (photoReaderSurface == null) {
                Log.e(TAG, "无法从 PhotoCaptureManager 获取 ImageReader Surface。");
                return;
            }

            int deviceRotation = getWindowManager().getDefaultDisplay().getRotation();
            Integer sensorOrientationObj = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int sensorOrientation = (sensorOrientationObj != null) ? sensorOrientationObj : 0;
            int jpegOrientation = getJpegOrientation(sensorOrientation, deviceRotation, currentLensFacing);

            final CaptureRequest.Builder stillCaptureBuilder = photoCaptureManager.createStillCaptureRequestBuilder(cameraDevice, jpegOrientation, photoReaderSurface);
            if (stillCaptureBuilder == null) {
                Log.e(TAG, "无法创建拍照请求。");
                return;
            }

            currentPhotoFile = createImageFile();
            if (currentPhotoFile == null) {
                Toast.makeText(this, "无法创建图片文件", Toast.LENGTH_SHORT).show();
                return;
            }

            final CameraCaptureSession.CaptureCallback captureCallback = photoCaptureManager.getCaptureCallback(
                    () -> {
                        Log.d(TAG, "拍照完成: " + (currentPhotoFile != null ? currentPhotoFile.getAbsolutePath() : "未知文件"));
                        createCameraPreviewSession();
                    },
                    () -> {
                        Log.e(TAG, "拍照失败。");
                        createCameraPreviewSession();
                    }
            );

            closePreviewSession();

            List<Surface> outputSurfaces = new ArrayList<>();
            outputSurfaces.add(photoReaderSurface);

            cameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    if (cameraDevice == null) {
                        if (session != null) session.close();
                        return;
                    }
                    try {
                        Log.d(TAG, "拍照会话已配置，执行拍照请求。");
                        session.capture(stillCaptureBuilder.build(), captureCallback, mBackgroundHandler);
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "拍照会话onConfigured: capture失败", e);
                        if (session != null) session.close();
                        createCameraPreviewSession();
                    } catch (IllegalStateException e) {
                        Log.e(TAG, "拍照会话onConfigured: capture失败 (IllegalState)", e);
                        if (session != null) session.close();
                        createCameraPreviewSession();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "为拍照创建会话失败。");
                    Toast.makeText(MainActivity.this, "拍照配置失败", Toast.LENGTH_SHORT).show();
                    createCameraPreviewSession();
                }
            }, mBackgroundHandler);

        } catch (CameraAccessException | IOException e) {
            Log.e(TAG, "拍照时发生异常", e);
            Toast.makeText(this, "拍照失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            createCameraPreviewSession();
        } catch (IllegalStateException e) {
            Log.e(TAG, "拍照时发生IllegalStateException (相机设备可能已关闭)", e);
            Toast.makeText(this, "相机错误，请重试", Toast.LENGTH_SHORT).show();
            closeCamera();
            if (textureView.isAvailable()) {
                openCamera(textureView.getWidth(), textureView.getHeight());
            } else {
                textureView.setSurfaceTextureListener(textureListener);
            }
        }
    }

    /**
     * 根据传感器方向、设备UI旋转方向和当前镜头朝向计算JPEG/视频方向。
     * @param sensorOrientation 传感器方向 (来自 CameraCharacteristics.SENSOR_ORIENTATION)。
     * @param deviceScreenRotation 设备UI的旋转状态 (来自 getWindowManager().getDefaultDisplay().getRotation())。
     * @param lensFacing 当前镜头的朝向。
     * @return JPEG/视频图像需要顺时针旋转的角度 (0, 90, 180, 270)。
     */
    private int getJpegOrientation(int sensorOrientation, int deviceScreenRotation, int lensFacing) {
        // 将设备屏幕旋转转换为度数
        int deviceOrientationDegrees = 0;
        switch (deviceScreenRotation) {
            case Surface.ROTATION_0:
                deviceOrientationDegrees = 0;
                break;
            case Surface.ROTATION_90:
                deviceOrientationDegrees = 90;
                break;
            case Surface.ROTATION_180:
                deviceOrientationDegrees = 180;
                break;
            case Surface.ROTATION_270:
                deviceOrientationDegrees = 270;
                break;
        }

        int jpegOrientation;
        if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            // 前置摄像头：传感器方向通常是270度。
            // 图像需要从传感器方向旋转到设备当前方向。
            // (传感器固有方向 - 设备当前方向 + 360) % 360
            jpegOrientation = (sensorOrientation - deviceOrientationDegrees + 360) % 360;
        } else {  // LENS_FACING_BACK (后置摄像头)
            // 后置摄像头：传感器方向通常是90度。
            // (传感器固有方向 + 设备当前方向 + 360) % 360
            jpegOrientation = (sensorOrientation + deviceOrientationDegrees + 360) % 360;
        }
        Log.d(TAG, "镜头: " + (lensFacing == CameraCharacteristics.LENS_FACING_FRONT ? "前置" : "后置") +
                ", 传感器方向: " + sensorOrientation +
                ", 设备屏幕旋转角度: " + deviceOrientationDegrees +
                ", 计算出的JPEG/视频方向: " + jpegOrientation);
        return jpegOrientation;
    }


    private File getPublicImageStorageDir() {
        File publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        File appDir = new File(publicDir, APP_MEDIA_SUBDIRECTORY);
        if (!appDir.exists() && !appDir.mkdirs()) {
            Log.e(TAG, "创建公共图片目录失败: " + appDir.getAbsolutePath() + ". 回退到应用专属目录。");
            return getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        }
        return appDir;
    }

    private File getPublicVideoStorageDir() {
        File publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
        File appDir = new File(publicDir, APP_MEDIA_SUBDIRECTORY);
        if (!appDir.exists() && !appDir.mkdirs()) {
            Log.e(TAG, "创建公共视频目录失败: " + appDir.getAbsolutePath() + ". 回退到应用专属目录。");
            return getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        }
        return appDir;
    }

    private File createImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        File storageDir = getPublicImageStorageDir();
        if (storageDir == null) throw new IOException("无法获取图片存储目录");
        File image = File.createTempFile(imageFileName, ".jpg", storageDir);
        Log.d(TAG, "图片文件将保存为: " + image.getAbsolutePath());
        return image;
    }

    private File createVideoFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String videoFileName = "MP4_" + timeStamp + "_";
        File storageDir = getPublicVideoStorageDir();
        if (storageDir == null) throw new IOException("无法获取视频存储目录");
        File video = File.createTempFile(videoFileName, ".mp4", storageDir);
        Log.d(TAG, "视频文件将保存为: " + video.getAbsolutePath());
        return video;
    }

    private void saveImageToGallery(byte[] bytes, @NonNull File fileToSave, boolean isVideo) {
        OutputStream output = null;
        Uri mediaContentUri = isVideo ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String mimeType = isVideo ? "video/mp4" : "image/jpeg";
        String appFolderInPublicDir = APP_MEDIA_SUBDIRECTORY;
        String relativePathDir = isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES;

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileToSave.getName());
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String relativePath = relativePathDir + File.separator + appFolderInPublicDir;
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri collection = isVideo ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri itemUri = getContentResolver().insert(collection, values);
            if (itemUri != null) {
                try {
                    if (!isVideo && bytes != null) { output = getContentResolver().openOutputStream(itemUri); if (output != null) output.write(bytes); else throw new IOException("OutputStream为null (Q+)"); }
                    values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0); getContentResolver().update(itemUri, values, null, null);
                    Toast.makeText(MainActivity.this, (isVideo ? "视频" : "图片") + "已保存", Toast.LENGTH_LONG).show();
                    Log.d(TAG, (isVideo ? "视频" : "图片") + "元数据已保存: " + itemUri.toString() + ", 文件应位于: " + fileToSave.getAbsolutePath());
                } catch (IOException e) { Log.e(TAG, "保存到MediaStore时IO错误 (Q+)", e); if (itemUri != null) getContentResolver().delete(itemUri, null, null);
                } finally { if (output != null) try { output.close(); } catch (IOException e) {} }
            } else Log.e(TAG, "创建MediaStore记录失败 (Q+)。");
        } else {
            values.put(MediaStore.MediaColumns.DATA, fileToSave.getAbsolutePath());
            getContentResolver().insert(mediaContentUri, values);
            try {
                if (!isVideo && bytes != null) { output = new FileOutputStream(fileToSave); output.write(bytes); }
                Log.d(TAG, (isVideo ? "视频" : "图片") + "已保存到: " + fileToSave.getAbsolutePath());
                Toast.makeText(MainActivity.this, (isVideo ? "视频" : "图片") + "已保存", Toast.LENGTH_LONG).show();
                sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(fileToSave)));
            } catch (IOException e) { Log.e(TAG, "保存到文件时IO错误 (pre-Q)", e);
            } finally { if (output != null) try { output.close(); } catch (IOException e) {} }
        }
    }

    protected void createCameraPreviewSession() {
        if (null == cameraDevice || textureView == null || !textureView.isAvailable() || previewSize == null) {
            Log.e(TAG, "createCameraPreviewSession: 先决条件未满足。");
            return;
        }
        try {
            closePreviewSession();

            SurfaceTexture texture = textureView.getSurfaceTexture();
            if (texture == null) { Log.e(TAG, "SurfaceTexture is null"); return; }
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface surface = new Surface(texture);

            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);

            cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    if (null == cameraDevice) return;
                    cameraCaptureSessions = session;
                    updatePreview();
                }
                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Toast.makeText(MainActivity.this, "预览配置失败", Toast.LENGTH_SHORT).show();
                }
            }, mBackgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "CameraAccessException in createCameraPreviewSession", e);
        } catch (IllegalStateException e) {
            Log.e(TAG, "IllegalStateException in createCameraPreviewSession", e);
        }
    }

    private void openCamera(int width, int height) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        if (currentCameraId == null) { Log.e(TAG, "currentCameraId 为 null"); return; }

        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        Log.d(TAG, "尝试打开相机: " + currentCameraId);
        boolean acquiredLock = false;
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("等待相机锁超时。");
            }
            acquiredLock = true;

            CameraCharacteristics characteristics = manager.getCameraCharacteristics(currentCameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                throw new RuntimeException("无法获取StreamConfigurationMap для камеры " + currentCameraId);
            }

            photoSize = chooseOptimalPhotoSize(map.getOutputSizes(android.graphics.ImageFormat.JPEG), width, height);
            videoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder.class));
            previewSize = chooseOptimalPreviewSize(map.getOutputSizes(SurfaceTexture.class), width, height, videoSize != null ? videoSize : photoSize);

            if (previewSize == null || videoSize == null || photoSize == null) {
                Log.e(TAG,"预览/视频/照片尺寸获取失败");
                throw new RuntimeException("预览/视频/照片尺寸获取失败");
            }
            Log.d(TAG, "预览尺寸: " + previewSize + ", 视频尺寸: " + videoSize + ", 照片尺寸: " + photoSize);

            manager.openCamera(currentCameraId, stateCallback, mBackgroundHandler);
            acquiredLock = false; // 锁的释放责任转移给 stateCallback

        } catch (CameraAccessException e) {
            Log.e(TAG, "openCamera CameraAccessException", e);
            Toast.makeText(this, "打开相机失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "openCamera InterruptedException", e);
            Toast.makeText(this, "打开相机中断", Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) { // 包括我们自己抛出的超时和尺寸获取失败
            Log.e(TAG, "openCamera RuntimeException", e);
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
        } finally {
            if (acquiredLock) { // 如果锁被获取且未转移责任
                cameraOpenCloseLock.release();
            }
        }
    }

    private static Size chooseOptimalPhotoSize(Size[] choices, int textureViewWidth, int textureViewHeight) {
        if (choices == null || choices.length == 0) return new Size(1920, 1080);
        List<Size> suitableSizes = new ArrayList<>();
        double targetRatio1 = 4.0 / 3.0;
        double targetRatio2 = 16.0 / 9.0;

        for (Size option : choices) {
            double ratio = (double) option.getWidth() / option.getHeight();
            if (Math.abs(ratio - targetRatio1) < 0.15 || Math.abs(ratio - targetRatio2) < 0.15) {
                suitableSizes.add(option);
            }
        }
        if (!suitableSizes.isEmpty()) {
            return Collections.max(suitableSizes, new CompareSizesByArea());
        }
        Log.w(TAG, "找不到合适的照片尺寸，选择列表中的第一个。");
        return choices[0];
    }

    private static Size chooseVideoSize(Size[] choices) { if(choices==null||choices.length==0)return new Size(640,480); for(Size s:choices)if(s.getWidth()==s.getHeight()*16/9&&s.getWidth()<=1920)return s; for(Size s:choices)if(s.getWidth()==s.getHeight()*16/9&&s.getWidth()<=1280)return s; Log.w(TAG,"无16:9视频尺寸");return choices[0];}
    private static Size chooseOptimalPreviewSize(Size[] choices, int tW, int tH, Size aspectRef) { if(choices==null||choices.length==0||aspectRef==null) { Log.w(TAG, "chooseOptimalPreviewSize: 无效参数或无可用尺寸"); return choices != null && choices.length > 0 ? choices[0] : new Size(640,480); } List<Size>big=new ArrayList<>(),small=new ArrayList<>(); int w=aspectRef.getWidth(),h=aspectRef.getHeight(); double tr=(double)w/h; for(Size o:choices)if(Math.abs((double)o.getWidth()/o.getHeight()-tr)<0.15)if(o.getWidth()>=tW&&o.getHeight()>=tH)big.add(o);else small.add(o); if(!big.isEmpty())return Collections.min(big,new CompareSizesByArea()); if(!small.isEmpty())return Collections.max(small,new CompareSizesByArea()); Log.w(TAG,"无匹配预览尺寸");return choices[0];}
    static class CompareSizesByArea implements Comparator<Size> { @Override public int compare(Size l, Size r){return Long.signum((long)l.getWidth()*l.getHeight()-(long)r.getWidth()*r.getHeight());}}

    protected void updatePreview() { if(cameraDevice==null||previewRequestBuilder==null||cameraCaptureSessions==null)return; try{previewRequestBuilder.set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO);previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);cameraCaptureSessions.setRepeatingRequest(previewRequestBuilder.build(),null,mBackgroundHandler);}catch(CameraAccessException|IllegalStateException e){Log.e(TAG,"updatePreview error",e);}}
    private void closePreviewSession() { if(cameraCaptureSessions!=null){try{cameraCaptureSessions.close();}catch(Exception e){Log.e(TAG,"关闭预览会话出错",e);}cameraCaptureSessions=null;}}

    private void closeCamera() {
        Log.d(TAG, "正在关闭相机...");
        boolean acquired = false;
        try {
            acquired = cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS);
            if (!acquired) {
                Log.w(TAG, "关闭相机时获取锁超时。");
            }
            closePreviewSession();
            if (null != cameraDevice) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (videoRecorderManager != null) {
                videoRecorderManager.release();
            }
            if (photoCaptureManager != null) {
                photoCaptureManager.release();
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "关闭相机时中断。", e);
            Thread.currentThread().interrupt();
        } finally {
            if (acquired) {
                cameraOpenCloseLock.release();
            }
        }
        Log.d(TAG, "相机已关闭。");
    }


    private void startRecordingVideo() {
        if (null == cameraDevice || !textureView.isAvailable() || null == previewSize || null == videoSize) { return; }
        try {
            currentVideoFile = createVideoFile();
            if (currentVideoFile == null) { return; }

            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(currentCameraId);
            Integer sensorOrientationObj = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int sensorOrientation = (sensorOrientationObj != null) ? sensorOrientationObj : 0;
            int deviceRotation = getWindowManager().getDefaultDisplay().getRotation();
            int orientationHint = getJpegOrientation(sensorOrientation, deviceRotation, currentLensFacing);

            videoRecorderManager.setUpMediaRecorder(currentCameraId, videoSize, currentVideoFile, orientationHint,
                    MediaRecorder.AudioSource.MIC, MediaRecorder.VideoSource.SURFACE);
            closePreviewSession();
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());

            final CaptureRequest.Builder recordRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);

            List<Surface> surfaces = new ArrayList<>();
            Surface previewSurface = new Surface(texture);
            surfaces.add(previewSurface);
            recordRequestBuilder.addTarget(previewSurface);

            Surface recorderSurface = videoRecorderManager.getRecorderSurface();
            if (recorderSurface == null) throw new IOException("无法从 VideoRecorderManager 获取 Recorder Surface。");
            surfaces.add(recorderSurface);
            recordRequestBuilder.addTarget(recorderSurface);

            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    cameraCaptureSessions = session;
                    try {
                        recordRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                        recordRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        session.setRepeatingRequest(recordRequestBuilder.build(), null, mBackgroundHandler);

                        videoRecorderManager.start();
                        runOnUiThread(() -> { isRecordingVideo = true; btnRecordVideo.setText("停止"); btnSwitchCamera.setEnabled(false); btnCapture.setEnabled(false); });
                        Log.d(TAG, "录像已开始。");
                    } catch (CameraAccessException | IllegalStateException e) {
                        Log.e(TAG, "录像会话onConfigured中启动录制或设置重复请求失败", e);
                        handleRecordingStartFailure();
                    }
                }
                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) { Toast.makeText(MainActivity.this,"录像会话配置失败",Toast.LENGTH_SHORT).show(); videoRecorderManager.release();}
            }, mBackgroundHandler);
        } catch (CameraAccessException | IOException | IllegalStateException e) { Log.e(TAG,"开始录像失败",e); Toast.makeText(this,"开始录像失败: "+e.getMessage(),Toast.LENGTH_SHORT).show(); videoRecorderManager.release();}
    }
    private void handleRecordingStartFailure() { videoRecorderManager.release(); isRecordingVideo=false; runOnUiThread(()->{btnRecordVideo.setText("录像");btnSwitchCamera.setEnabled(true);btnCapture.setEnabled(true);}); createCameraPreviewSession();}

    private void stopRecordingVideo() {
        if (!isRecordingVideo || !videoRecorderManager.isInitialized()) {
            if (isRecordingVideo) { isRecordingVideo = false; runOnUiThread(()->{btnRecordVideo.setText("录像");btnSwitchCamera.setEnabled(true);btnCapture.setEnabled(true);});}
            if (videoRecorderManager.isInitialized()) videoRecorderManager.release();
            if (cameraDevice != null) createCameraPreviewSession();
            return;
        }
        isRecordingVideo = false; runOnUiThread(()->{btnRecordVideo.setText("录像");btnSwitchCamera.setEnabled(true);btnCapture.setEnabled(true);});
        try {
            if (cameraCaptureSessions != null) {
                cameraCaptureSessions.stopRepeating();
                cameraCaptureSessions.abortCaptures();
            }
        }
        catch (CameraAccessException|IllegalStateException e) { Log.e(TAG, "停止录像时停止重复请求/取消捕获失败", e); }

        try {
            videoRecorderManager.stop();
            Log.d(TAG, "录像已停止。文件: " + (currentVideoFile != null ? currentVideoFile.getAbsolutePath() : "未知"));
            if (currentVideoFile != null && currentVideoFile.exists() && currentVideoFile.length() > 0) saveImageToGallery(null, currentVideoFile, true);
            else if (currentVideoFile != null) { Log.w(TAG,"录制视频文件为空或不存在"); Toast.makeText(this,"录制失败",Toast.LENGTH_SHORT).show(); currentVideoFile.delete(); }
        } catch (IllegalStateException e) { Log.e(TAG, "VideoRecorderManager.stop() 失败", e); if(currentVideoFile!=null&&currentVideoFile.exists())currentVideoFile.delete();
        } finally {
            videoRecorderManager.release();
            closePreviewSession();
            if (cameraDevice != null) createCameraPreviewSession();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode==REQUEST_CAMERA_AUDIO_PERMISSION_CODE){
            boolean allPermissionsActuallyGranted = false;
            if (grantResults.length > 0) {
                boolean allFoundInResultsAreGranted = true;
                for (int result : grantResults) {
                    if (result != PackageManager.PERMISSION_GRANTED) {
                        allFoundInResultsAreGranted = false;
                        break;
                    }
                }
                if (allFoundInResultsAreGranted) {
                    allPermissionsActuallyGranted = true;
                }
            }

            if (allPermissionsActuallyGranted) {
                Log.d(TAG,"所有权限已授予。");
                if (textureView.isAvailable()) {
                    if (currentCameraId == null) findCameraIds();
                    if (currentCameraId != null) openCamera(textureView.getWidth(),textureView.getHeight());
                } else {
                    textureView.setSurfaceTextureListener(textureListener);
                }
            } else {
                Toast.makeText(this,"权限被拒绝或取消，无法使用相机功能。",Toast.LENGTH_LONG).show();
                btnCapture.setEnabled(false);
                btnRecordVideo.setEnabled(false);
                btnSwitchCamera.setEnabled(false);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume(); Log.d(TAG,"onResume"); startBackgroundThread();
        if(textureView.isAvailable()){Log.d(TAG,"onResume: TextureView可用");
            if(checkRequiredPermissions()){if(currentCameraId==null)findCameraIds();if(currentCameraId!=null)openCamera(textureView.getWidth(),textureView.getHeight());else Log.e(TAG,"onResume: currentCameraId为null");}}else{Log.d(TAG,"onResume: TextureView不可用");textureView.setSurfaceTextureListener(textureListener);}}

    @Override
    protected void onPause() { super.onPause(); Log.d(TAG,"onPause"); if(isRecordingVideo)stopRecordingVideo(); closeCamera(); stopBackgroundThread();}
}
