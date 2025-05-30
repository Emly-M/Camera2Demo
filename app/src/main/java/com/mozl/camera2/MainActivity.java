package com.mozl.camera2;

import android.graphics.Color;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.FragmentManager; // Already androidx

import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Toast;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;

import com.mozl.camera2.core.Camera2Fragment;
import com.mozl.camera2.settings.AspectRatio;
import com.mozl.camera2.settings.AspectRatioFragment;
import com.mozl.camera2.settings.CameraConstants;

import java.io.File;
import java.util.Set;

public class MainActivity extends AppCompatActivity
        implements View.OnClickListener, AspectRatioFragment.AspectRatioListener, Camera2Fragment.ThumbnailCallback{

    private static final String FRAGMENT_DIALOG = "aspect_dialog";

    private static final int[] FLASH_OPTIONS = {
            CameraConstants.FLASH_AUTO,
            CameraConstants.FLASH_OFF,
            CameraConstants.FLASH_ON,
    };

    private static final int[] FLASH_ICONS = {
            R.drawable.ic_flash_auto,
            R.drawable.ic_flash_off,
            R.drawable.ic_flash_on,
    };

    private static final int[] FLASH_TITLES = {
            R.string.flash_auto,
            R.string.flash_off,
            R.string.flash_on,
    };

    private int mCurrentFlashIndex = 0; // Initialize to a default valid index


    private Camera2Fragment mCamera2Fragment;

    /**
     * The button of record video
     */
    private Button mRecordButton;

    /**
     * The button of take picture
     */
    private Button mPictureButton;

    //mozl
    private ImageView mThumbnailPreview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setFullScreen();
        setContentView(R.layout.activity_main);
        mThumbnailPreview = findViewById(R.id.thumbnail_preview);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        toolbar.setTitleTextColor(Color.WHITE);
        toolbar.setTitle(R.string.app_name);
        setSupportActionBar(toolbar);
        if (null == savedInstanceState) {
            mCamera2Fragment = Camera2Fragment.newInstance();
            mCamera2Fragment.setThumbnailCallbackListener(this);
            // Use getSupportFragmentManager() for androidx.fragment.app.Fragment
            getSupportFragmentManager().beginTransaction() // Changed
                    .replace(R.id.container, mCamera2Fragment)
                    .commit();
        } else {
            // Use getSupportFragmentManager() for androidx.fragment.app.Fragment
            mCamera2Fragment = (Camera2Fragment) getSupportFragmentManager().findFragmentById(R.id.container); // Changed
            if (mCamera2Fragment != null) {
                mCamera2Fragment.setThumbnailCallbackListener(this); // 设备旋转后恢复时也要设置回调
            }
        }

        mRecordButton = (Button) findViewById(R.id.video);
        mRecordButton.setOnClickListener(this);
        mPictureButton = (Button) findViewById(R.id.picture);
        mPictureButton.setOnClickListener(this);

        if (getResources() != null && getResources().getDisplayMetrics() != null) { // Added null check
            int height = getResources().getDisplayMetrics().heightPixels / 4;
            LinearLayout controlLayout = (LinearLayout) findViewById(R.id.control);
            RelativeLayout.LayoutParams layoutParams = (RelativeLayout.LayoutParams) controlLayout.getLayoutParams();
            layoutParams.height = height;
            controlLayout.setLayoutParams(layoutParams);
        }
    }

    private void setFullScreen() {
        if (getWindow() != null) { // Added null check
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION |
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Ensure mCamera2Fragment is not null before accessing its methods
        if (mCamera2Fragment == null) {
            return super.onOptionsItemSelected(item);
        }

        int itemId = item.getItemId();
        if (itemId == R.id.aspect_ratio) {
            FragmentManager fragmentManager = getSupportFragmentManager();
            if (fragmentManager.findFragmentByTag(FRAGMENT_DIALOG) == null) {
                final Set<AspectRatio> ratios = mCamera2Fragment.getSupportedAspectRatios();
                final AspectRatio currentRatio = mCamera2Fragment.getAspectRatio();
                // Assuming AspectRatioFragment is an androidx.fragment.app.DialogFragment
                AspectRatioFragment.newInstance(ratios, currentRatio)
                        .show(fragmentManager, FRAGMENT_DIALOG);
            }
            return true;
        } else if (itemId == R.id.switch_flash) {
            mCurrentFlashIndex = (mCurrentFlashIndex + 1) % FLASH_OPTIONS.length;
            item.setTitle(FLASH_TITLES[mCurrentFlashIndex]);
            item.setIcon(FLASH_ICONS[mCurrentFlashIndex]);
            mCamera2Fragment.setFlash(FLASH_OPTIONS[mCurrentFlashIndex]);
            return true;
        } else if (itemId == R.id.switch_camera) {
            int facing = mCamera2Fragment.getFacing();
            mCamera2Fragment.setFacing(facing == CameraConstants.FACING_FRONT ?
                    CameraConstants.FACING_BACK : CameraConstants.FACING_FRONT);
            invalidateOptionsMenu(); // Call this to trigger onPrepareOptionsMenu
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        // Ensure mCamera2Fragment is not null
        if (mCamera2Fragment != null && menu != null) { // Added null check for menu
            if (mCamera2Fragment.isRecordingVideo()) {
                menu.findItem(R.id.aspect_ratio).setVisible(false);
                menu.findItem(R.id.switch_camera).setVisible(false);
                menu.findItem(R.id.switch_flash).setVisible(false);
            } else {
                menu.findItem(R.id.aspect_ratio).setVisible(true);
                menu.findItem(R.id.switch_camera)
                        .setVisible(mCamera2Fragment.isFacingSupported());
                menu.findItem(R.id.switch_flash)
                        .setTitle(FLASH_TITLES[mCurrentFlashIndex])
                        .setIcon(FLASH_ICONS[mCurrentFlashIndex])
                        .setVisible(mCamera2Fragment.isFlashSupported());
            }
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public void onClick(View view) {
        // Ensure mCamera2Fragment is not null
        if (mCamera2Fragment == null) {
            return;
        }

        int viewId = view.getId();
        if (viewId == R.id.picture) {
            mCamera2Fragment.takePicture();
        } else if (viewId == R.id.video) {
            if (mCamera2Fragment.isRecordingVideo()) {
                mCamera2Fragment.stopRecordingVideo();
                mRecordButton.setText(R.string.start_record_video);
                mPictureButton.setEnabled(true);
            } else {
                mPictureButton.setEnabled(false);
                mCamera2Fragment.startRecordingVideo();
                mRecordButton.setText(R.string.stop_record_video);
            }
            invalidateOptionsMenu(); // Call this to trigger onPrepareOptionsMenu
        }
    }

    @Override
    public void onAspectRatioSelected(@NonNull AspectRatio ratio) {
        Toast.makeText(this, ratio.toString(), Toast.LENGTH_SHORT).show();
        if (mCamera2Fragment != null) { // Added null check
            mCamera2Fragment.setAspectRatio(ratio);
        }
    }

    //实现ThumbnailCallback接口的方法
    @Override
    public void onPhotoTaken(String photoPath) {
        if (photoPath == null || mThumbnailPreview == null) {
            return;
        }
        File imgFile = new File(photoPath);
        if (imgFile.exists()) {
            // BitmapFactory.Options 用于高效加载Bitmap，避免OOM
            BitmapFactory.Options options = new BitmapFactory.Options();
            // 设置inSampleSize可以在不完全解码的情况下获取图片尺寸，然后计算合适的缩放比例
            // 这里我们简单地直接解码，对于缩略图，如果源图不大，通常没问题
            // 但对于非常大的图片，建议先用inJustDecodeBounds=true获取尺寸，再计算inSampleSize
            options.inSampleSize = 4; //  可以根据需要调整采样率，数值越大图片越小，加载越快
            Bitmap myBitmap = BitmapFactory.decodeFile(imgFile.getAbsolutePath(), options);

            if (myBitmap != null) {
                mThumbnailPreview.setImageBitmap(myBitmap);
            } else {
                // 加载失败，可以设置一个默认的失败图标
                // mThumbnailPreview.setImageResource(R.drawable.ic_thumbnail_load_failed);
            }
        }
    }
}