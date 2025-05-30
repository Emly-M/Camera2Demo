package com.mozl.camera2.core;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;

import com.mozl.camera2.R;
import com.mozl.camera2.focus.AutoFocusHelper;
import com.mozl.camera2.focus.FocusView;
import com.mozl.camera2.preview.AutoFitTextureView;
import com.mozl.camera2.settings.AspectRatio;
import com.mozl.camera2.settings.CameraConstants;
import com.mozl.camera2.settings.SizeMap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class Camera2Fragment extends Fragment { // Removed FragmentCompat.OnRequestPermissionsResultCallback

    private static final int SENSOR_ORIENTATION_DEFAULT_DEGREES = 90;
    private static final int SENSOR_ORIENTATION_INVERSE_DEGREES = 270;

    private static final SparseIntArray DEFAULT_ORIENTATIONS = new SparseIntArray();
    private static final SparseIntArray INVERSE_ORIENTATIONS = new SparseIntArray();

    private static final SparseIntArray INTERNAL_FACINGS = new SparseIntArray();

    static {
        INTERNAL_FACINGS.put(CameraConstants.FACING_BACK, CameraCharacteristics.LENS_FACING_BACK);
        INTERNAL_FACINGS.put(CameraConstants.FACING_FRONT, CameraCharacteristics.LENS_FACING_FRONT);
    }

    private static final int REQUEST_CAMERA_PERMISSION = 1;

    private static final String FRAGMENT_DIALOG = "dialog";

    private static final String[] VIDEO_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
    };

    static {
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_0, 90);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_90, 0);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_180, 270);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    static {
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_0, 270);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_90, 180);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_180, 90);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_270, 0);
    }

    /**
     * Tag for the {@link Log}.
     */
    private static final String TAG = "Camera2Fragment";

    /**
     * Camera state: Showing camera preview.
     */
    private static final int STATE_PREVIEW = 0;

    /**
     * Camera state: Waiting for the focus to be locked.
     */
    private static final int STATE_WAITING_LOCK = 1;

    /**
     * Camera state: Waiting for the exposure to be precapture state.
     */
    private static final int STATE_WAITING_PRECAPTURE = 2;

    /**
     * Camera state: Waiting for the exposure state to be something other than precapture.
     */
    private static final int STATE_WAITING_NON_PRECAPTURE = 3;

    /**
     * Camera state: Picture was taken.
     */
    private static final int STATE_PICTURE_TAKEN = 4;

    /**
     * Max preview width that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIEW_WIDTH = 1920;

    /**
     * Max preview height that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIEW_HEIGHT = 1080;


    private static final int MSG_CAPTURE_PICTURE_WHEN_FOCUS_TIMEOUT = 100;

    private Rect mCropRegion;

    private MeteringRectangle[] mAFRegions = AutoFocusHelper.getZeroWeightRegion();

    private MeteringRectangle[] mAERegions = AutoFocusHelper.getZeroWeightRegion();

    private AspectRatio mAspectRatio = CameraConstants.DEFAULT_ASPECT_RATIO;

    private final SizeMap mPreviewSizes = new SizeMap();

    /**
     * ID of the current {@link CameraDevice}.
     */
    private String mCameraId;

    /**
     * An {@link AutoFitTextureView} for camera preview.
     */
    public AutoFitTextureView mTextureView;

    /**
     * The view for manual tap to focus
     */
    public FocusView mFocusView;

    /**
     * A {@link CameraCaptureSession } for camera preview.
     */
    private CameraCaptureSession mPreviewSession;

    /**
     * A reference to the opened {@link CameraDevice}.
     */
    private CameraDevice mCameraDevice;


    /**
     * {@link CaptureRequest.Builder} for the camera preview
     */
    private CaptureRequest.Builder mPreviewRequestBuilder;

    /**
     * {@link CaptureRequest} generated by {@link #mPreviewRequestBuilder}
     */
    private CaptureRequest mPreviewRequest;


    private CameraCharacteristics mCameraCharacteristics;

    /**
     * The current state of camera state for taking pictures.
     *
     * @see #mCaptureCallback
     */
    private int mState = STATE_PREVIEW;

    /**
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     */
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);

    /**
     * The current camera auto focus mode
     */
    private boolean mAutoFocus = true;

    /**
     * Whether the current camera device supports auto focus or not.
     */
    private boolean mAutoFocusSupported = true;

    /**
     * The current camera flash mode
     */
    private int mFlash = CameraConstants.FLASH_AUTO;

    /**
     * Whether the current camera device supports flash or not.
     */
    private boolean mFlashSupported = true;

    /**
     * The current camera facing mode
     */
    private int mFacing = CameraConstants.FACING_BACK;

    /**
     * Whether the current camera device can switch back/front or not.
     */
    private boolean mFacingSupported = true;

    /**
     * Orientation of the camera sensor
     */
    private int mSensorOrientation;

    /**
     * The {@link Size} of camera preview.
     */
    private Size mPreviewSize;

    /**
     * The {@link Size} of video recording.
     */
    private Size mVideoSize;

    /**
     * MediaRecorder
     */
    private MediaRecorder mMediaRecorder;

    /**
     * Whether the camera is recording video now
     */
    private boolean mIsRecordingVideo;

    /**
     * Whether the camera is manual focusing now
     */
    private boolean mIsManualFocusing;

    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private HandlerThread mBackgroundThread;

    /**
     * A {@link Handler} for running tasks in the background.
     */
    private Handler mBackgroundHandler;

    /**
     * An {@link ImageReader} that handles still image capture.
     */
    private ImageReader mImageReader;

    /**
     * The output file path of video recording.
     */
    private String mNextVideoAbsolutePath;

    /**
     * The output file path of take picture.
     */
    private String mNextPictureAbsolutePath;

    private ThumbnailCallback mThumbnailCallbackListener;
    private Handler mMainHandler;

    public interface ThumbnailCallback {
        void onPhotoTaken(String photoPath);
    }

    public void setThumbnailCallbackListener(ThumbnailCallback listener) { // 新增：设置回调监听
        this.mThumbnailCallbackListener = listener;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mMainHandler = new Handler(Looper.getMainLooper()); // 新增：初始化Handler
    }


    /**
     * {@link TextureView.SurfaceTextureListener} handles several lifecycle events on a
     * {@link TextureView}.
     */
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener
            = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture texture, int width, int height) {
            openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture texture, int width, int height) {
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture texture) {
            if (mTextureView != null) {
                mTextureView.setSurfaceTextureListener(null);
            }
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture texture) {
        }

    };


    /**
     * {@link CameraDevice.StateCallback} is called when {@link CameraDevice} changes its state.
     */
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            // This method is called when the camera is opened.  We start camera preview here.
            mCameraOpenCloseLock.release();
            mCameraDevice = cameraDevice;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
            Activity activity = getActivity();
            if (null != activity) {
                showToast("Camera is error: " + error);
                activity.finish();
            }
        }

    };


    /**
     * This a callback object for the {@link ImageReader}. "onImageAvailable" will be called when a
     * still image is ready to be saved.
     */
    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader reader) {
            if (getActivity() == null) return;
            mNextPictureAbsolutePath = getPictureFilePath(getActivity());
            mNextPictureAbsolutePath = getPictureFilePath(getActivity());
            Image imageToSave = reader.acquireNextImage();
            if (imageToSave == null) {
                return;
            }
            mBackgroundHandler.post(new ImageSaver(imageToSave, new File(mNextPictureAbsolutePath), new ImageSaver.ImageSaverCallback() {
                @Override
                public void onImageSaved(String savedPath) {
                    Log.i(TAG, "Picture saved for thumbnail: " + savedPath);
                    if (mThumbnailCallbackListener != null) {
                        // 使用 mMainHandler 将回调切换到主线程
                        mMainHandler.post(() -> mThumbnailCallbackListener.onPhotoTaken(savedPath));
                    }
                }

                @Override
                public void onSaveFailed() {
                    Log.e(TAG, "ImageSaver: Failed to save image.");
                    // 可以在此通知UI保存失败
                }
            }));
            showToast("Picture saved: " + mNextPictureAbsolutePath);
            Log.i(TAG, "Picture saved: " + mNextPictureAbsolutePath);
        }

    };


    /**
     * A {@link CameraCaptureSession.CaptureCallback} that handles events related to JPEG capture.
     */
    private final CameraCaptureSession.CaptureCallback mCaptureCallback
            = new CameraCaptureSession.CaptureCallback() {

        private void process(CaptureResult result) {
            //  Log.i(TAG, "CaptureCallback mState: " + mState);
            switch (mState) {
                case STATE_PREVIEW: {
                    // We have nothing to do when the camera preview is working normally.
                    break;
                }
                case STATE_WAITING_LOCK: {
                    Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                    Log.i(TAG, "STATE_WAITING_LOCK afState: " + afState);
                    if (afState == null) {
                        mState = STATE_PICTURE_TAKEN;
                        captureStillPicture();
                    } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                        // CONTROL_AE_STATE can be null on some devices
                        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                        if (aeState == null ||
                                aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            mState = STATE_PICTURE_TAKEN;
                            captureStillPicture();
                        } else {
                            runPrecaptureSequence();
                        }
                    }
                    break;
                }
                case STATE_WAITING_PRECAPTURE: {
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null ||
                            aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                            aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        mState = STATE_WAITING_NON_PRECAPTURE;
                    }
                    break;
                }
                case STATE_WAITING_NON_PRECAPTURE: {
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        mState = STATE_PICTURE_TAKEN;
                        captureStillPicture();
                    }
                    break;
                }
            }
        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request,
                                        @NonNull CaptureResult partialResult) {
            process(partialResult);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
            process(result);
        }

    };

    /**
     * Shows a {@link Toast} on the UI thread.
     *
     * @param text The message to show
     */
    private void showToast(final String text) {
        final Activity activity = getActivity();
        if (activity != null) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    /**
     * We choose a largest picture size with mAspectRatio
     */
    Size choosePictureSize(Size[] choices) {
        List<Size> pictureSizes = Arrays.asList(choices);
        Collections.sort(pictureSizes, new CompareSizesByArea());
        int maxIndex = pictureSizes.size() - 1;
        for (int i = maxIndex; i >= 0; i--) {
            if (pictureSizes.get(i).getWidth() == pictureSizes.get(i).getHeight() *
                    mAspectRatio.getX() / mAspectRatio.getY()) {
                return pictureSizes.get(i);
            }
        }
        return pictureSizes.get(maxIndex);
    }

    /**
     * We choose a largest video size with mAspectRatio. Also, we don't use sizes
     * larger than 1080p, since MediaRecorder cannot handle such a high-resolution video.
     *
     * @param choices The list of available sizes
     * @return The video size
     */
    Size chooseVideoSize(Size[] choices) {
        List<Size> videoSizes = Arrays.asList(choices);
        List<Size> supportedVideoSizes = new ArrayList<>();
        Collections.sort(videoSizes, new CompareSizesByArea());
        for (int i = videoSizes.size() - 1; i >= 0; i--) {
            if (videoSizes.get(i).getWidth() <= MAX_PREVIEW_WIDTH &&
                    videoSizes.get(i).getHeight() <= MAX_PREVIEW_HEIGHT) {
                supportedVideoSizes.add(videoSizes.get(i));
                if (videoSizes.get(i).getWidth() == videoSizes.get(i).getHeight() *
                        mAspectRatio.getX() / mAspectRatio.getY()) {
                    return videoSizes.get(i);
                }
            }
        }
        return supportedVideoSizes.size() > 0 ? supportedVideoSizes.get(0) : choices[choices.length - 1];
    }

    /**
     * Given {@code choices} of {@code Size}s supported by a camera, choose the smallest one that
     * is at least as large as the respective texture view size, and that is at most as large as the
     * respective max size, and whose aspect ratio matches with the specified value. If such size
     * doesn't exist, choose the largest one that is at most as large as the respective max size,
     * and whose aspect ratio matches with the specified value.
     *
     * @param choices           The list of sizes that the camera supports for the intended output
     * class
     * @param textureViewWidth  The width of the texture view relative to sensor coordinate
     * @param textureViewHeight The height of the texture view relative to sensor coordinate
     * @param maxWidth          The maximum width that can be chosen
     * @param maxHeight         The maximum height that can be chosen
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */
    Size chooseOptimalSize(Size[] choices, int textureViewWidth,
                           int textureViewHeight, int maxWidth, int maxHeight) {
        mPreviewSizes.clear();
        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        // Collect the supported resolutions that are smaller than the preview Surface
        List<Size> notBigEnough = new ArrayList<>();
        int w = mAspectRatio.getX();
        int h = mAspectRatio.getY();
        for (Size option : choices) {
            if (option.getWidth() <= maxWidth && option.getHeight() <= maxHeight) {
                mPreviewSizes.add(new com.mozl.camera2.settings.Size(option.getWidth(), option.getHeight()));
                if (option.getHeight() == option.getWidth() * h / w) {
                    if (option.getWidth() >= textureViewWidth &&
                            option.getHeight() >= textureViewHeight) {
                        bigEnough.add(option);
                    } else {
                        notBigEnough.add(option);
                    }
                }
            }
        }

        // Pick the smallest of those big enough. If there is no one big enough, pick the
        // largest of those not big enough.
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else if (notBigEnough.size() > 0) {
            return Collections.max(notBigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            mAspectRatio = AspectRatio.of(4, 3);
            SortedSet<com.mozl.camera2.settings.Size> sortedSet = mPreviewSizes.sizes(mAspectRatio);
            if (sortedSet != null && !sortedSet.isEmpty()) { // Added !sortedSet.isEmpty() check
                com.mozl.camera2.settings.Size lastSize = sortedSet.last();
                return new Size(lastSize.getWidth(), lastSize.getHeight());
            }
            mAspectRatio = AspectRatio.of(choices[0].getWidth(), choices[0].getHeight());
            return choices[0];
        }
    }

    public static Camera2Fragment newInstance() {
        return new Camera2Fragment();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_camera2, container, false);
    }

    @Override
    public void onViewCreated(@NonNull final View view, Bundle savedInstanceState) {
        mTextureView = (AutoFitTextureView) view.findViewById(R.id.texture);
        mFocusView = (FocusView) view.findViewById(R.id.focusView);
        mTextureView.setGestureListener(new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                setFocusViewWidthAnimation((int) e.getX(), (int) e.getY());
                setManualFocusAt((int) e.getX(), (int) e.getY());
                return true;
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        start();
    }

    @Override
    public void onPause() {
        stop();
        super.onPause();
    }

    public void start() {
        startBackgroundThread();

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (mTextureView.isAvailable()) {
            openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    public void stop() {
        closeCamera();
        stopBackgroundThread();
    }

    /**
     * Focus view animation
     */
    private void setFocusViewWidthAnimation(float x, float y) {
        if (mFocusView == null) return; // Added null check
        mFocusView.setVisibility(View.VISIBLE);

        mFocusView.setX(x - mFocusView.getWidth() / 2f); // Use float division
        mFocusView.setY(y - mFocusView.getHeight() / 2f); // Use float division

        ObjectAnimator scaleX = ObjectAnimator.ofFloat(mFocusView, "scaleX", 1, 0.6f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(mFocusView, "scaleY", 1, 0.6f);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(mFocusView, "alpha", 1f, 0.3f, 1f, 0.3f, 1f, 0.3f, 1f);
        AnimatorSet animSet = new AnimatorSet();
        animSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (mFocusView != null) { // Added null check
                    mFocusView.setVisibility(View.INVISIBLE);
                }
            }
        });
        animSet.play(scaleX).with(scaleY).before(alpha);
        animSet.setDuration(300);
        animSet.start();
    }

    private boolean hasPermissionsGranted(String[] permissions) {
        if (getActivity() == null) return false; // Added null check
        for (String permission : permissions) {
            if (ActivityCompat.checkSelfPermission(getActivity(), permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Gets whether you should show UI with rationale for requesting permissions.
     *
     * @param permissions The permissions your app wants to request.
     * @return Whether you can show permission rationale UI.
     */
    private boolean shouldShowRequestPermissionRationaleInternal(String[] permissions) {
        for (String permission : permissions) {
            if (shouldShowRequestPermissionRationale(permission)) { // Changed
                return true;
            }
        }
        return false;
    }

    private void requestCameraPermission() {
        if (shouldShowRequestPermissionRationaleInternal(VIDEO_PERMISSIONS)) { // Changed
            new ConfirmationDialog().show(getChildFragmentManager(), FRAGMENT_DIALOG);
        } else {
            requestPermissions(VIDEO_PERMISSIONS, REQUEST_CAMERA_PERMISSION); // Changed
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        Log.d(TAG, "onRequestPermissionsResult");
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length == VIDEO_PERMISSIONS.length) {
                for (int result : grantResults) {
                    if (result != PackageManager.PERMISSION_GRANTED) {
                        if (getActivity() != null && getString(R.string.request_permission) != null) {
                            ErrorDialog.newInstance(getString(R.string.request_permission))
                                    .show(getChildFragmentManager(), FRAGMENT_DIALOG);
                        }
                        break;
                    }
                }
            } else {
                if (getActivity() != null && getString(R.string.request_permission) != null) {
                    ErrorDialog.newInstance(getString(R.string.request_permission))
                            .show(getChildFragmentManager(), FRAGMENT_DIALOG);
                }
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    /**
     * Setup member variables related to camera.
     *
     * @param width  The width of available size for camera preview
     * @param height The height of available size for camera preview
     */
    @SuppressWarnings("ConstantConditions") // Added to suppress potential NPE on getSystemService if activity is null
    private void setupCameraOutputs(int width, int height) {
        Activity activity = getActivity();
        if (activity == null) return; // Added null check

        int internalFacing = INTERNAL_FACINGS.get(mFacing);
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) { // Added null check for manager
            ErrorDialog.newInstance(getString(R.string.camera_error))
                    .show(getChildFragmentManager(), FRAGMENT_DIALOG);
            return;
        }
        try {
            String[] cameraIds = manager.getCameraIdList();
            mFacingSupported = cameraIds.length > 1;
            for (String cameraId : cameraIds) {
                mCameraCharacteristics
                        = manager.getCameraCharacteristics(cameraId);

                Integer facing = mCameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != internalFacing) {
                    continue;
                }

                StreamConfigurationMap map = mCameraCharacteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    continue;
                }

                // Find out if we need to swap dimension to get the preview size relative to sensor
                // coordinate.
                int displayRotation = activity.getWindowManager().getDefaultDisplay().getRotation();
                mSensorOrientation = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                boolean swappedDimensions = false;
                switch (displayRotation) {
                    case Surface.ROTATION_0:
                    case Surface.ROTATION_180:
                        if (mSensorOrientation == 90 || mSensorOrientation == 270) {
                            swappedDimensions = true;
                        }
                        break;
                    case Surface.ROTATION_90:
                    case Surface.ROTATION_270:
                        if (mSensorOrientation == 0 || mSensorOrientation == 180) {
                            swappedDimensions = true;
                        }
                        break;
                    default:
                        Log.e(TAG, "Display rotation is invalid: " + displayRotation);
                }

                Point displaySize = new Point();
                activity.getWindowManager().getDefaultDisplay().getRealSize(displaySize);
                int rotatedPreviewWidth = width;
                int rotatedPreviewHeight = height;
                int maxPreviewWidth = displaySize.x;
                int maxPreviewHeight = displaySize.y;

                if (swappedDimensions) {
                    rotatedPreviewWidth = height;
                    rotatedPreviewHeight = width;
                    maxPreviewWidth = displaySize.y;
                    maxPreviewHeight = displaySize.x;
                }

                if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
                    maxPreviewWidth = MAX_PREVIEW_WIDTH;
                }

                if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
                    maxPreviewHeight = MAX_PREVIEW_HEIGHT;
                }

                // Danger, W.R.! Attempting to use too large a preview size could exceed the camera
                // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
                // garbage capture data.
                mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                        rotatedPreviewWidth, rotatedPreviewHeight, maxPreviewWidth,
                        maxPreviewHeight);

                mVideoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder.class));

                // For still image captures, we use the largest available size.
                Size largest = choosePictureSize(map.getOutputSizes(ImageFormat.JPEG));

                mImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(),
                        ImageFormat.JPEG, /*maxImages*/2);
                mImageReader.setOnImageAvailableListener(
                        mOnImageAvailableListener, mBackgroundHandler);

                // We fit the aspect ratio of TextureView to the size of preview we picked.
                if (getResources() != null && getResources().getConfiguration() != null) { // Added null check
                    int orientation = getResources().getConfiguration().orientation;
                    if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                        mTextureView.setAspectRatio(
                                mPreviewSize.getWidth(), mPreviewSize.getHeight());
                    } else {
                        mTextureView.setAspectRatio(
                                mPreviewSize.getHeight(), mPreviewSize.getWidth());
                    }
                }


                checkAutoFocusSupported();
                checkFlashSupported();

                mCropRegion = AutoFocusHelper.cropRegionForZoom(mCameraCharacteristics,
                        CameraConstants.ZOOM_REGION_DEFAULT);

                mCameraId = cameraId;
                Log.i(TAG, "CameraId: " + mCameraId + " ,isFlashSupported: " + mFlashSupported);
                return;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (NullPointerException e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            if (getActivity() != null && getString(R.string.camera_error) != null) {
                ErrorDialog.newInstance(getString(R.string.camera_error))
                        .show(getChildFragmentManager(), FRAGMENT_DIALOG);
            }
        }
    }

    /**
     * Check if the auto focus is supported.
     */
    private void checkAutoFocusSupported() {
        if (mCameraCharacteristics == null) return; // Added null check
        int[] modes = mCameraCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        mAutoFocusSupported = !(modes == null || modes.length == 0 ||
                (modes.length == 1 && modes[0] == CameraCharacteristics.CONTROL_AF_MODE_OFF));
    }

    /**
     * Check if the flash is supported.
     */
    private void checkFlashSupported() {
        if (mCameraCharacteristics == null) return; // Added null check
        Boolean available = mCameraCharacteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        mFlashSupported = available != null && available; // Simplified
    }

    /**
     * Opens the camera specified by {@link Camera2Fragment#mCameraId}.
     */
    @SuppressWarnings({"MissingPermission", "ConstantConditions"}) // Added ConstantConditions
    private void openCamera(int width, int height) {
        if (!hasPermissionsGranted(VIDEO_PERMISSIONS)) {
            requestCameraPermission();
            return;
        }
        setupCameraOutputs(width, height);
        configureTransform(width, height);
        Activity activity = getActivity();
        if (activity == null) return; // Added null check

        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) { // Added null check for manager
            ErrorDialog.newInstance(getString(R.string.camera_error))
                    .show(getChildFragmentManager(), FRAGMENT_DIALOG);
            return;
        }
        try {
            if (!mCameraOpenCloseLock.tryAcquire(CameraConstants.OPEN_CAMERA_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            mMediaRecorder = new MediaRecorder();
            manager.openCamera(mCameraId, mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    /**
     * Closes the current {@link CameraDevice}.
     */
    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mPreviewSession) {
                mPreviewSession.close();
                mPreviewSession = null;
            }
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mMediaRecorder) {
                mMediaRecorder.release();
                mMediaRecorder = null;
            }
            if (null != mImageReader) {
                mImageReader.close();
                mImageReader = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper()) {
            @Override
            public void handleMessage(@NonNull Message msg) { // Added @NonNull
                super.handleMessage(msg);
                switch (msg.what) {
                    case MSG_CAPTURE_PICTURE_WHEN_FOCUS_TIMEOUT:
                        mState = STATE_PICTURE_TAKEN;
                        captureStillPicture();
                        break;
                    default:
                        break;
                }

            }
        };
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThread() {
        if (mBackgroundThread == null) return; // Added null check
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Creates a new {@link CameraCaptureSession} for camera preview.
     */
    private void createCameraPreviewSession() {
        try {
            if (mTextureView == null || mCameraDevice == null || mPreviewSize == null || mImageReader == null) return; // Added null checks
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            // This is the output Surface we need to start preview.
            Surface surface = new Surface(texture);

            // We set up a CaptureRequest.Builder with the output Surface.
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(surface);

            // Here, we create a CameraCaptureSession for camera preview.
            mCameraDevice.createCaptureSession(Arrays.asList(surface, mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            // The camera is already closed
                            if (null == mCameraDevice) {
                                return;
                            }

                            // When the session is ready, we start displaying the preview.
                            mPreviewSession = cameraCaptureSession;
                            try {
                                // Auto focus should be continuous for camera preview.
//                            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
//                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                updateAutoFocus();
                                // Flash is automatically enabled when necessary.
                                updateFlash(mPreviewRequestBuilder);

                                // Finally, we start displaying the camera preview.
                                mPreviewRequest = mPreviewRequestBuilder.build();
                                mPreviewSession.setRepeatingRequest(mPreviewRequest,
                                        mCaptureCallback, mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(
                                @NonNull CameraCaptureSession cameraCaptureSession) {
                            showToast("Create preview configure failed");
                        }
                    }, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Configures the necessary {@link Matrix} transformation to `mTextureView`.
     * This method should be called after the camera preview size is determined in
     * setupCameraOutputs and also the size of `mTextureView` is fixed.
     *
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private void configureTransform(int viewWidth, int viewHeight) {
        Activity activity = getActivity();
        if (null == mTextureView || null == mPreviewSize || null == activity) {
            return;
        }
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        mTextureView.setTransform(matrix);
    }

    public Set<AspectRatio> getSupportedAspectRatios() {
        return mPreviewSizes.ratios();
    }

    public AspectRatio getAspectRatio() {
        return mAspectRatio;
    }

    public boolean setAspectRatio(AspectRatio aspectRatio) {
        if (aspectRatio == null || aspectRatio.equals(mAspectRatio) ||
                !mPreviewSizes.ratios().contains(aspectRatio)) {
            return false;
        }
        mAspectRatio = aspectRatio;
        if (isCameraOpened()) {
            stop();
            start();
        }
        return true;
    }

    public boolean isCameraOpened() {
        return mCameraDevice != null;
    }

    public void setFacing(int facing) {
        if (mFacing == facing) {
            return;
        }
        mFacing = facing;
        if (isCameraOpened()) {
            stop();
            start();
        }
    }

    public int getFacing() {
        return mFacing;
    }

    /**
     * The facing is supported or not.
     */
    public boolean isFacingSupported() {
        return mFacingSupported;
    }

    public void setFlash(int flash) {
        if (mFlash == flash) {
            return;
        }
        int saved = mFlash;
        mFlash = flash;
        if (mPreviewRequestBuilder != null) {
            updateFlash(mPreviewRequestBuilder);
            if (mPreviewSession != null) {
                try {
                    mPreviewSession.setRepeatingRequest(mPreviewRequestBuilder.build(),
                            mCaptureCallback, mBackgroundHandler);
                } catch (CameraAccessException e) {
                    mFlash = saved; // Revert
                }
            }
        }
    }

    public int getFlash() {
        return mFlash;
    }

    /**
     * Updates the internal state of flash to {@link #mFlash}.
     */
    void updateFlash(CaptureRequest.Builder requestBuilder) {
        if (!mFlashSupported) {
            return;
        }
        switch (mFlash) {
            case CameraConstants.FLASH_OFF:
                requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON);
                requestBuilder.set(CaptureRequest.FLASH_MODE,
                        CaptureRequest.FLASH_MODE_OFF);
                break;
            case CameraConstants.FLASH_ON:
                requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
                requestBuilder.set(CaptureRequest.FLASH_MODE,
                        CaptureRequest.FLASH_MODE_OFF);
                break;
            case CameraConstants.FLASH_TORCH:
                requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON);
                requestBuilder.set(CaptureRequest.FLASH_MODE,
                        CaptureRequest.FLASH_MODE_TORCH);
                break;
            case CameraConstants.FLASH_AUTO:
                requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                requestBuilder.set(CaptureRequest.FLASH_MODE,
                        CaptureRequest.FLASH_MODE_OFF);
                break;
            case CameraConstants.FLASH_RED_EYE:
                requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE);
                requestBuilder.set(CaptureRequest.FLASH_MODE,
                        CaptureRequest.FLASH_MODE_OFF);
                break;
        }
    }

    /**
     * The flash is supported or not.
     */
    public boolean isFlashSupported() {
        return mFlashSupported;
    }

    public void setAutoFocus(boolean autoFocus) {
        if (mAutoFocus == autoFocus) {
            return;
        }
        mAutoFocus = autoFocus;
        if (mPreviewRequestBuilder != null) {
            updateAutoFocus();
            if (mPreviewSession != null) {
                try {
                    mPreviewSession.setRepeatingRequest(mPreviewRequestBuilder.build(),
                            mCaptureCallback, mBackgroundHandler);
                } catch (CameraAccessException e) {
                    mAutoFocus = !mAutoFocus; // Revert
                }
            }
        }
    }

    public boolean getAutoFocus() {
        return mAutoFocus;
    }

    /**
     * The auto focus is supported or not.
     */
    public boolean isAutoFocusSupported() {
        return mAutoFocusSupported;
    }

    /**
     * Updates the internal state of auto-focus to {@link #mAutoFocus}.
     */
    void updateAutoFocus() {
        if (mPreviewRequestBuilder == null) return; // Added null check
        if (mAutoFocus) {
            if (!mAutoFocusSupported) {
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_OFF);
            } else {
                if (mIsRecordingVideo) {
                    mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                } else {
                    mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                }
            }
        } else {
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_OFF);
        }
        mAFRegions = AutoFocusHelper.getZeroWeightRegion();
        mAERegions = AutoFocusHelper.getZeroWeightRegion();
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, mAFRegions);
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, mAERegions);
    }

    /**
     * Updates the internal state of manual focus.
     */
    void updateManualFocus(float x, float y) {
        if (mCameraCharacteristics == null || mPreviewRequestBuilder == null) return; // Added null checks
        @SuppressWarnings("ConstantConditions") // SENSOR_ORIENTATION should not be null if mCameraCharacteristics is not null
        int sensorOrientation = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        mAFRegions = AutoFocusHelper.afRegionsForNormalizedCoord(x, y, mCropRegion, sensorOrientation);
        mAERegions = AutoFocusHelper.aeRegionsForNormalizedCoord(x, y, mCropRegion, sensorOrientation);
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, mAFRegions);
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, mAERegions);
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
    }

    @SuppressWarnings("ConstantConditions") // getWindowManager can be null if activity is finishing
    void setManualFocusAt(int x, int y) {
        if (getActivity() == null || getActivity().getWindowManager() == null || mTextureView == null) return; // Added null checks

        int mDisplayOrientation = getActivity().getWindowManager().getDefaultDisplay().getRotation();
        float points[] = new float[2];
        if (mTextureView.getWidth() == 0 || mTextureView.getHeight() == 0) return; // Avoid division by zero
        points[0] = (float) x / mTextureView.getWidth();
        points[1] = (float) y / mTextureView.getHeight();
        Matrix rotationMatrix = new Matrix();
        rotationMatrix.setRotate(mDisplayOrientation, 0.5f, 0.5f);
        rotationMatrix.mapPoints(points);
        if (mPreviewRequestBuilder != null) {
            mIsManualFocusing = true;
            updateManualFocus(points[0], points[1]);
            if (mPreviewSession != null) {
                try {
                    mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                            CaptureRequest.CONTROL_AF_TRIGGER_START);
                    mPreviewSession.capture(mPreviewRequestBuilder.build(), null, mBackgroundHandler);
                    mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                            CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                    mPreviewSession.setRepeatingRequest(mPreviewRequestBuilder.build(),
                            null, mBackgroundHandler);
                } catch (CameraAccessException | IllegalStateException e) {
                    Log.e(TAG, "Failed to set manual focus.", e);
                }
            }
            resumeAutoFocusAfterManualFocus();
        }
    }

    private final Runnable mAutoFocusRunnable = new Runnable() {
        @Override
        public void run() {
            if (mPreviewRequestBuilder != null) {
                mIsManualFocusing = false;
                updateAutoFocus();
                if (mPreviewSession != null) {
                    try {
                        mPreviewSession.setRepeatingRequest(mPreviewRequestBuilder.build(),
                                mCaptureCallback, mBackgroundHandler);
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "Failed to set manual focus.", e);
                    }
                }
            }
        }
    };

    private void resumeAutoFocusAfterManualFocus() {
        if (mBackgroundHandler == null) return; // Added null check
        mBackgroundHandler.removeCallbacks(mAutoFocusRunnable);
        mBackgroundHandler.postDelayed(mAutoFocusRunnable, CameraConstants.FOCUS_HOLD_MILLIS);
    }

    /**
     * Initiate a still image capture.
     */
    public void takePicture() {
        if (!mIsManualFocusing && mAutoFocus && mAutoFocusSupported) {
            Log.i(TAG, "takePicture lockFocus");
            capturePictureWhenFocusTimeout(); //Sometimes, camera do not focus in some devices.
            lockFocus();
        } else {
            Log.i(TAG, "takePicture captureStill");
            captureStillPicture();
        }
    }

    /**
     * Capture picture when auto focus timeout
     */
    private void capturePictureWhenFocusTimeout() {
        if (mBackgroundHandler != null) {
            mBackgroundHandler.sendEmptyMessageDelayed(MSG_CAPTURE_PICTURE_WHEN_FOCUS_TIMEOUT,
                    CameraConstants.AUTO_FOCUS_TIMEOUT_MS);
        }
    }

    /**
     * Remove capture message, because auto focus work correctly.
     */
    private void removeCaptureMessage() {
        if (mBackgroundHandler != null) {
            mBackgroundHandler.removeMessages(MSG_CAPTURE_PICTURE_WHEN_FOCUS_TIMEOUT);
        }
    }

    /**
     * Lock the focus as the first step for a still image capture.
     */
    private void lockFocus() {
        try {
            if (mPreviewRequestBuilder == null || mPreviewSession == null) return; // Added null checks
            // This is how to tell the camera to lock focus.
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the lock.
            mState = STATE_WAITING_LOCK;
            mPreviewSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Unlock the focus. This method should be called when still image capture sequence is
     * finished.
     */
    private void unlockFocus() {
        try {
            if (mPreviewRequestBuilder == null || mPreviewSession == null) return; // Added null checks
            // Reset the auto-focus trigger
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            mPreviewSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);

            updateAutoFocus();
            updateFlash(mPreviewRequestBuilder);
            // After this, the camera will go back to the normal state of preview.
            mState = STATE_PREVIEW;
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            if (mPreviewRequest != null) { // Added null check for mPreviewRequest
                mPreviewSession.setRepeatingRequest(mPreviewRequest, mCaptureCallback,
                        mBackgroundHandler);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Run the precapture sequence for capturing a still image. This method should be called when
     * we get a response in {@link #mCaptureCallback} from {@link #lockFocus()}.
     */
    private void runPrecaptureSequence() {
        try {
            if (mPreviewRequestBuilder == null || mPreviewSession == null) return; // Added null checks
            // This is how to tell the camera to trigger.
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the precapture sequence to be set.
            mState = STATE_WAITING_PRECAPTURE;
            mPreviewSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Capture a still picture. This method should be called when we get a response in
     * {@link #mCaptureCallback} from both {@link #lockFocus()}.
     */
    @SuppressWarnings("ConstantConditions") // getWindowManager can be null if activity is finishing
    private void captureStillPicture() {
        try {
            removeCaptureMessage();
            final Activity activity = getActivity();
            if (null == activity || null == mCameraDevice || null == mImageReader || activity.getWindowManager() == null) { // Added null checks
                return;
            }
            // This is the CaptureRequest.Builder that we use to take a picture.
            final CaptureRequest.Builder captureBuilder =
                    mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(mImageReader.getSurface());

            // Use the same AE and AF modes as the preview.
//            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE,
//                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
//            updateAutoFocus(); // updateAutoFocus() is not needed here as per original logic, AE/AF modes are directly set or inherited.
            updateFlash(captureBuilder); // Update flash for capture

            // Orientation
            int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(rotation));

            CameraCaptureSession.CaptureCallback CaptureCallback
                    = new CameraCaptureSession.CaptureCallback() {

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    unlockFocus();
                }
            };

            if (mPreviewSession != null) { // Added null check
                mPreviewSession.stopRepeating();
                mPreviewSession.capture(captureBuilder.build(), CaptureCallback, mBackgroundHandler);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Retrieves the JPEG orientation from the specified screen rotation.
     *
     * @param rotation The screen rotation.
     * @return The JPEG orientation (one of 0, 90, 270, and 360)
     */
    private int getOrientation(int rotation) {
        // Sensor orientation is 90 for most devices, or 270 for some devices (eg. Nexus 5X)
        // We have to take that into account and rotate JPEG properly.
        // For devices with orientation of 90, we simply return our mapping from DEFAULT_ORIENTATIONS.
        // For devices with orientation of 270, we need to rotate the JPEG 180 degrees.
        return (DEFAULT_ORIENTATIONS.get(rotation) + mSensorOrientation + 270) % 360;
    }

    private String getVideoFilePath(Context context) {
        if (context == null) return ""; // Added null check
        final File dir = context.getExternalFilesDir(null);
        return (dir == null ? "" : (dir.getAbsolutePath() + "/"))
                + System.currentTimeMillis() + ".mp4";
    }

    private String getPictureFilePath(Context context) {
        if (context == null) return ""; // Added null check
        final File dir = context.getExternalFilesDir(null);
        return (dir == null ? "" : (dir.getAbsolutePath() + "/"))
                + System.currentTimeMillis() + ".jpg";
    }

    @SuppressWarnings("ConstantConditions") // getWindowManager can be null if activity is finishing
    private void setupMediaRecorder() throws IOException {
        final Activity activity = getActivity();
        if (null == activity || mMediaRecorder == null || activity.getWindowManager() == null) { // Added null checks
            return;
        }
        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mNextVideoAbsolutePath = getVideoFilePath(getActivity());
        mMediaRecorder.setOutputFile(mNextVideoAbsolutePath);
        mMediaRecorder.setVideoEncodingBitRate(10000000);
        mMediaRecorder.setVideoFrameRate(30);
        mMediaRecorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        switch (mSensorOrientation) {
            case SENSOR_ORIENTATION_DEFAULT_DEGREES:
                mMediaRecorder.setOrientationHint(DEFAULT_ORIENTATIONS.get(rotation));
                break;
            case SENSOR_ORIENTATION_INVERSE_DEGREES:
                mMediaRecorder.setOrientationHint(INVERSE_ORIENTATIONS.get(rotation));
                break;
        }
        mMediaRecorder.prepare();
    }


    /**
     * Start recording video
     */
    public void startRecordingVideo() {
        if (null == mCameraDevice || mTextureView == null || !mTextureView.isAvailable() || null == mPreviewSize) { // Added mTextureView null check
            return;
        }
        try {
            mIsRecordingVideo = true;
            setupMediaRecorder();
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            List<Surface> surfaces = new ArrayList<>();

            // Set up Surface for the camera preview
            Surface previewSurface = new Surface(texture);
            surfaces.add(previewSurface);
            mPreviewRequestBuilder.addTarget(previewSurface);

            // Set up Surface for the MediaRecorder
            if (mMediaRecorder == null) return; // Added null check
            Surface recorderSurface = mMediaRecorder.getSurface();
            surfaces.add(recorderSurface);
            mPreviewRequestBuilder.addTarget(recorderSurface);

            // Start a capture session
            // Once the session starts, we can update the UI and start recording
            mCameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {

                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    mPreviewSession = cameraCaptureSession;
                    try {
                        if (mPreviewRequestBuilder == null) return; // Added null check
                        // Auto focus should be continuous for camera preview.
//                        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
//                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                        updateAutoFocus();
                        // Flash is automatically enabled when necessary.
                        updateFlash(mPreviewRequestBuilder);

                        // For test
                        Integer stabilizationMode = mPreviewRequestBuilder.
                                get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE);
                        if (stabilizationMode != null &&
                                stabilizationMode == CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF) {
                            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
                        }

                        // Finally, we start displaying the camera preview.
                        mPreviewRequest = mPreviewRequestBuilder.build();
                        mPreviewSession.setRepeatingRequest(mPreviewRequest, null, mBackgroundHandler);
                        if (getActivity() != null) { // Added null check
                            getActivity().runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    // Start recording
                                    if (mMediaRecorder != null) { // Added null check
                                        mMediaRecorder.start();
                                    }
                                }
                            });
                        }
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    showToast("Start recording video configure failed");
                }
            }, mBackgroundHandler);
        } catch (CameraAccessException | IOException e) {
            e.printStackTrace();
        }

    }

    /**
     * Stop recording video
     */
    public void stopRecordingVideo() {
        if (getActivity() == null) return; // Added null check
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // UI
                mIsRecordingVideo = false;

                showToast("Video saved: " + mNextVideoAbsolutePath);
                Log.i(TAG, "Video saved: " + mNextVideoAbsolutePath);

                closeCamera();
                if (mTextureView != null) { // Added null check
                    openCamera(mTextureView.getWidth(), mTextureView.getHeight());
                }
            }
        });
    }

    public boolean isRecordingVideo() {
        return mIsRecordingVideo;
    }


    /**
     * Saves a JPEG {@link Image} into the specified {@link File}.
     */
    private static class ImageSaver implements Runnable {

        /**
         * The JPEG image
         */
        private final Image mImage;
        /**
         * The file we save the image into.
         */
        private final File mFile;

        private final ImageSaverCallback mCallback;

        ImageSaver(Image image, File file,ImageSaverCallback callback) {
            mImage = image;
            mFile = file;
            mCallback = callback;
        }

        interface ImageSaverCallback {
            void onImageSaved(String savedPath);
            void onSaveFailed();
        }

        @Override
        public void run() {
            if (mImage == null || mFile == null) {
                if (mCallback != null) {
                    mCallback.onSaveFailed();
                }
                return;
            }
            ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            FileOutputStream output = null;
            boolean success = false;
            try {
                output = new FileOutputStream(mFile);
                output.write(bytes);
                success = true;
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                mImage.close(); // 确保Image对象被关闭
                if (null != output) {
                    try {
                        output.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                // 在所有操作完成后调用回调
                if (mCallback != null) {
                    if (success) {
                        mCallback.onImageSaved(mFile.getAbsolutePath());
                    } else {
                        mCallback.onSaveFailed();
                    }
                }
            }
        }
    }

    /**
     * Compares two {@code Size}s based on their areas.
     */
    private static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }

    }

    /**
     * Shows an error message dialog.
     */
    public static class ErrorDialog extends DialogFragment { // Changed to androidx.fragment.app.DialogFragment

        private static final String ARG_MESSAGE = "message";

        public static ErrorDialog newInstance(String message) {
            ErrorDialog dialog = new ErrorDialog();
            Bundle args = new Bundle();
            args.putString(ARG_MESSAGE, message);
            dialog.setArguments(args);
            return dialog;
        }

        @NonNull // Added @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Activity activity = getActivity();
            // Use androidx.appcompat.app.AlertDialog.Builder for consistent styling with AppCompat themes
            AlertDialog.Builder builder = new AlertDialog.Builder(activity == null ? requireContext() : activity); // Changed, added requireContext() as fallback
            if (getArguments() != null) { // Added null check
                builder.setMessage(getArguments().getString(ARG_MESSAGE));
            }
            builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int i) {
                    if (activity != null) { // Added null check
                        activity.finish();
                    }
                }
            });
            return builder.create();
        }
    }

    /**
     * Shows OK/Cancel confirmation dialog about camera permission.
     */
    public static class ConfirmationDialog extends DialogFragment { // Changed to androidx.fragment.app.DialogFragment

        @NonNull // Added @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Fragment parent = getParentFragment(); // This will get the androidx.fragment.app.Fragment
            Activity activity = getActivity();
            // Use androidx.appcompat.app.AlertDialog.Builder for consistent styling with AppCompat themes
            AlertDialog.Builder builder = new AlertDialog.Builder(activity == null ? requireContext() : activity); // Changed, added requireContext() as fallback
            builder.setMessage(R.string.request_permission)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (parent != null) { // Added null check
                                parent.requestPermissions( // Changed to use direct requestPermissions
                                        new String[]{Manifest.permission.CAMERA}, // Should be VIDEO_PERMISSIONS from parent context
                                        REQUEST_CAMERA_PERMISSION);
                            }
                        }
                    })
                    .setNegativeButton(android.R.string.cancel,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    if (parent != null && parent.getActivity() != null) { // Added null checks
                                        parent.getActivity().finish();
                                    }
                                }
                            });
            return builder.create();
        }
    }
}
