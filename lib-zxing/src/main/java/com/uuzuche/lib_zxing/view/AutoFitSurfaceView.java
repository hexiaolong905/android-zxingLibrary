package com.uuzuche.lib_zxing.view;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceView;

public class AutoFitSurfaceView extends SurfaceView {
    private static final String TAG = "AutoFitSurfaceView";
    private float aspectRatio = 0f;
    public AutoFitSurfaceView(Context context) {
        this(context,null);
    }

    public AutoFitSurfaceView(Context context, AttributeSet attrs) {
        this(context,attrs,0);
    }

    public AutoFitSurfaceView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setAspectRatio(int width, int height) {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("Size cannot be negative.");
        }
        aspectRatio = (float)width / (float)height;
        this.getHolder().setFixedSize(width,height);
        this.requestLayout();
    }
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {

        super.onMeasure(widthMeasureSpec, heightMeasureSpec); //不执行？
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        if (aspectRatio == 0.0F) {
            this.setMeasuredDimension(width, height);
        } else {
            // Performs center-crop transformation of the camera frames
            int newWidth,newHeight;
            float actualRatio;
            if (width > height)
                actualRatio = aspectRatio;
            else
                actualRatio =1.0F / aspectRatio;
            if (width < height * actualRatio) {
                newHeight = height;
                newWidth =Math.round ((float)height * actualRatio);
            } else {
                newWidth = width;
                newHeight =Math.round( (float)width / actualRatio);
            }
            this.setMeasuredDimension(newWidth, newHeight);
        }
    }
}
