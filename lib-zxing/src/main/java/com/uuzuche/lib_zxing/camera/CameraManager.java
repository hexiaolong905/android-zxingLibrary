/*
 * Copyright (C) 2008 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.uuzuche.lib_zxing.camera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;


import androidx.annotation.NonNull;

import com.uuzuche.lib_zxing.view.AutoFitSurfaceView;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * This object wraps the Camera service object and expects to be the only one talking to it. The
 * implementation encapsulates the steps needed to take preview-sized images, which are used for
 * both preview and decoding.
 */
public final class CameraManager {

    private static final String TAG = CameraManager.class.getSimpleName();

    public static int FRAME_WIDTH = -1;
    public static int FRAME_HEIGHT = -1;
    public static int FRAME_MARGINTOP = -1;

    private static CameraManager cameraManager;

    static final int SDK_INT; // Later we can use Build.VERSION.SDK_INT

    static {
        int sdkInt;
        try {
            sdkInt = Build.VERSION.SDK_INT;
        } catch (NumberFormatException nfe) {
            // Just to be safe
            sdkInt = 10000;
        }
        SDK_INT = sdkInt;
    }

    private final Context context;
    private final CameraConfigurationManager configManager;
    private Rect framingRect;
    private Rect framingRectInPreview;
    private boolean initialized;

    private int mCameraId = CameraCharacteristics.LENS_FACING_FRONT;//当前Camera的ID，后摄像头
    private CameraDevice mCameraDevice;//标识打开的CameraDevice
    private CaptureRequest.Builder mPreviewRequestBuilder;//相机预览请求的构造器
    private CameraCaptureSession mCaptureSession;
    private CaptureRequest mPreviewRequest;//预览请求
    private android.hardware.camera2.CameraManager mCameraManager;//相机管理者
    public CameraCharacteristics mCameraCharacteristics;//相机属性
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;
    private ImageReader mImageReader;//预览回调的接收者，可以间接地获取预览帧数据，类似Camera的AutoFocusCallback:
    private boolean mFlashSupported;//当前摄像头是否支持闪关灯
    private AutoFitSurfaceView mSurfaceView;
    /**
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     * 用来防止程序在关闭摄像头之前退出
     */
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);

    /**
     * Initializes this static object with the Context of the calling Activity.
     *
     * @param context The Activity which wants to use the camera.
     */
    public static void init(Context context) {
        if (cameraManager == null) {
            cameraManager = new CameraManager(context);
        }
    }

    /**
     * Gets the CameraManager singleton instance.
     *
     * @return A reference to the CameraManager singleton.
     */
    public static CameraManager get() {
        return cameraManager;
    }

    private CameraManager(Context context) {
        this.context = context;
        this.configManager = new CameraConfigurationManager(context);
        mCameraManager = (android.hardware.camera2.CameraManager)context.getSystemService(Context.CAMERA_SERVICE);
        try {
            mCameraCharacteristics = mCameraManager.getCameraCharacteristics(Integer.toString(mCameraId));
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * 设置相机相关的变量
     */
    private void setUpCameraOutputs(){
        if(!initialized){
            initialized = true;
            configManager.initFromCameraParameters(mCameraCharacteristics);
        }
        Point cameraResolution = configManager.getCameraResolution();
        mImageReader = ImageReader.newInstance(cameraResolution.x,cameraResolution.y,
                configManager.getPreviewFormat(),1);//ImageFormat.YUV_420_888
        mImageReader.setOnImageAvailableListener(
                mOnImageAvailableListener, mBackgroundHandler);
        //检查是否支持闪光灯
        Boolean available = mCameraCharacteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        mFlashSupported = available == null ? false : available;
    }

    /**
     * Opens the camera driver and initializes the hardware parameters.
     *
     */
    @SuppressLint("MissingPermission")
    public void openDriver(AutoFitSurfaceView surfaceView) {
        startBackgroundThread();
        mSurfaceView = surfaceView;
        setUpCameraOutputs();
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            mCameraManager.openCamera(Integer.toString(mCameraId),mStateCallback,mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    /**
     *Camera状态回调
     */
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            //初始化cameraDevice
            mCameraDevice = cameraDevice;
            //创建预览会话
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int i) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }
    };

    /**
     * 开启相机预览
     */
    private void createCameraPreviewSession() {
        try {
            //创建一个预览的请求
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            //设置预览输出的Surface
            mPreviewRequestBuilder.addTarget(mSurfaceView.getHolder().getSurface());
            //设置预览回调的Surface,在mOnImageAvailableListener中对帧数据进行处理
            mPreviewRequestBuilder.addTarget(mImageReader.getSurface());
            //outputs： 输出的Surface集合，每个CaptureRequest的输出Surface都应该是outputs的一个子元素。
            //stateCallback：创建会话的回调
            //handler：指定回调执行的线程，传 null 时默认使用当前线程的 Looper。
            mCameraDevice.createCaptureSession(Arrays.asList(mSurfaceView.getHolder().getSurface(),mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            if (null == mCameraDevice) {
                                return;
                            }
                            //获取CameraCaptureSession 实例
                            mCaptureSession = cameraCaptureSession;
                            try {

                                // 设置连续自动对焦
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                if (mFlashSupported) {
                                    mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                                            CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                                }
                                mPreviewRequest = mPreviewRequestBuilder.build();
                                //通过调用 setRepeatingRequest方法，请求不断重复捕获图像，即实现预览
                                mCaptureSession.setRepeatingRequest(mPreviewRequest,
                                        null, mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(
                                @NonNull CameraCaptureSession cameraCaptureSession) {
                            Log.i(TAG, "onConfigureFailed: Failed");
                        }
                    }, null
            );
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * 当有图像流数据可用时会回调onImageAvailable方法，它的参数就是预览帧数据，可以对这帧数据进行处理
     * 类似于Camera1中的PreviewCallback接口
     */
    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader imageReader) {
            //接收预览的帧数据
            Image image = imageReader.acquireNextImage();
            if(image == null){
                return;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer;
            byte[] bytes = null;
            if (image.getFormat()==ImageFormat.YUV_420_888) {
                buffer = planes[0].getBuffer();
                if (bytes == null) {
                    bytes = new byte[buffer.capacity() * 3 / 2];
                }
                int len = buffer.capacity();
                buffer.get(bytes, 0, len);
                buffer = planes[2].getBuffer();//plane[0] + plane[2] =NV21;; plane[0] + plane[1] =NV12
                buffer.get(bytes, len, buffer.capacity());
            }
            image.close();
            if(previewHandler != null){
                if(bytes != null){
                    Message message = previewHandler.obtainMessage(previewMessage, width,
                            height, bytes);
                    message.sendToTarget();
                    previewHandler = null;
                }
            }else {
                Log.d(TAG, "Got preview callback, but no handler for it");
            }
        }
    };

    /**
     * 关闭相机
     */
    public void closeDriver() {
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mCaptureSession) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mImageReader) {
                mImageReader.close();
                mImageReader = null;
            }
            stopBackgroundThread();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    /**
     * 开启闪光灯
     */
    public void openFlash(){
        if(mFlashSupported){
            mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE,CaptureRequest.FLASH_MODE_TORCH);
            mPreviewRequest = mPreviewRequestBuilder.build();
            try {
                mCaptureSession.setRepeatingRequest(mPreviewRequest,
                        null, mBackgroundHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 关闭闪光灯
     */
    public void closeFlash(){
        if(mFlashSupported){
            mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE,CaptureRequest.FLASH_MODE_OFF);
            mPreviewRequest = mPreviewRequestBuilder.build();
            try {
                mCaptureSession.setRepeatingRequest(mPreviewRequest,
                        null, mBackgroundHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private Handler previewHandler;
    private int previewMessage;
    /**
     * 解码请求
     *
     * @param handler The handler to send the message to.
     * @param message The what field of the message to be sent.
     */
    public void requestDecode(Handler handler, int message) {
        this.previewHandler = handler;
        this.previewMessage = message;
    }

    /**
     * Calculates the framing rect which the UI should draw to show the user where to place the
     * barcode. This target helps with alignment as well as forces the user to hold the device
     * far enough away to ensure the image will be in focus.
     *
     * @return The rectangle to draw on screen in window coordinates.
     */
    public Rect getFramingRect() {
        try {
            Point screenResolution = configManager.getScreenResolution();

            int leftOffset = (screenResolution.x - FRAME_WIDTH) / 2;

            int topOffset;
            if (FRAME_MARGINTOP != -1) {
                topOffset = FRAME_MARGINTOP;
            } else {
                topOffset = (screenResolution.y - FRAME_HEIGHT) / 2;
            }
            framingRect = new Rect(leftOffset, topOffset, leftOffset + FRAME_WIDTH, topOffset + FRAME_HEIGHT);
            return framingRect;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Like {@link #getFramingRect} but coordinates are in terms of the preview frame,
     * not UI / screen.
     */
    public Rect getFramingRectInPreview() {
        if (framingRectInPreview == null) {
            Rect rect = new Rect(getFramingRect());
            Point cameraResolution = configManager.getCameraResolution();
            Point screenResolution = configManager.getScreenResolution();
            //modify here
//      rect.left = rect.left * cameraResolution.x / screenResolution.x;
//      rect.right = rect.right * cameraResolution.x / screenResolution.x;
//      rect.top = rect.top * cameraResolution.y / screenResolution.y;
//      rect.bottom = rect.bottom * cameraResolution.y / screenResolution.y;
            rect.left = rect.left * cameraResolution.y / screenResolution.x;
            rect.right = rect.right * cameraResolution.y / screenResolution.x;
            rect.top = rect.top * cameraResolution.x / screenResolution.y;
            rect.bottom = rect.bottom * cameraResolution.x / screenResolution.y;
            framingRectInPreview = rect;
        }
        return framingRectInPreview;
    }

    /**
     * A factory method to build the appropriate LuminanceSource object based on the format
     * of the preview buffers, as described by Camera.Parameters.
     *
     * @param data   A preview frame.
     * @param width  The width of the image.
     * @param height The height of the image.
     * @return A PlanarYUVLuminanceSource instance.
     */
    public PlanarYUVLuminanceSource buildLuminanceSource(byte[] data, int width, int height) {
        Rect rect = getFramingRectInPreview();
        int previewFormat = configManager.getPreviewFormat();
        switch (previewFormat) {
            // This is the standard Android format which all devices are REQUIRED to support.
            // In theory, it's the only one we should ever care about.
            case PixelFormat.YCbCr_420_SP:
                // This format has never been seen in the wild, but is compatible as we only care
                // about the Y channel, so allow it.
            case PixelFormat.YCbCr_422_SP:
            case ImageFormat.YUV_420_888:
            default:
                // The Samsung Moment incorrectly uses this variant instead of the 'sp' version.
                // Fortunately, it too has all the Y data up front, so we can read it.
                    return new PlanarYUVLuminanceSource(data, width, height, rect.left, rect.top,
                            rect.width(), rect.height());
        }
    }

    public Context getContext() {
        return context;
    }
}
