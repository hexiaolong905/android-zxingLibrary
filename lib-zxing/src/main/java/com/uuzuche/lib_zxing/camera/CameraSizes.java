package com.uuzuche.lib_zxing.camera;

import android.graphics.Point;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Size;
import android.view.Display;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class CameraSizes {
    /**
     * 1920 is Max preview width that is guaranteed by Camera2 API
     * 1080 is Max preview height that is guaranteed by Camera2 API
     */
    private static final SmartSize SIZE_1080P = new SmartSize(1920,1080);
    public static SmartSize getDisplaySmartSize(Display display){
        Point outPoint = new Point();
        display.getRealSize(outPoint);
        return new SmartSize(outPoint.x,outPoint.y);
    }
    public static Size getPreviewOutputSize(@NonNull Display display, @NonNull CameraCharacteristics characteristics, @NonNull Class targetClass, @Nullable Integer format){
        Size previewSize = new Size(0,0);
        SmartSize screenSize = getDisplaySmartSize(display);
        boolean hdScreen = screenSize.sLong >= SIZE_1080P.sLong || screenSize.sLong >= SIZE_1080P.sLong;
        SmartSize maxSize = hdScreen? SIZE_1080P : screenSize;
        StreamConfigurationMap config = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] allSizes = format == null ? config.getOutputSizes(targetClass):config.getOutputSizes(format);
        if(allSizes != null && allSizes.length >0){
            List<Size> sizeList= Arrays.asList(allSizes);
            Collections.sort(sizeList,new CompareSizesByArea());
            Collections.reverse(sizeList);
            ArrayList<SmartSize> arrayList = new ArrayList();
            for (Size size:
                    sizeList) {
                SmartSize smartSize = new SmartSize(size.getWidth(),size.getHeight());
                arrayList.add(smartSize);
            }
            for (SmartSize it:
                    arrayList) {
                if(it.sLong <= maxSize.sLong && it.sSort <= maxSize.sSort){
                    previewSize = it.size;
                    break;
                }
            }
        }
        return previewSize;
    }
}
class SmartSize{
    public int sLong,sSort;
    public Size size;
    public SmartSize(int width, int height) {
        this.size = new Size(width,height);
        this.sLong = Math.max(size.getWidth(),size.getHeight());
        this.sSort = Math.min(size.getWidth(),size.getHeight());
    }
    @Override
    public String toString() {
        return "SmartSize{" +
                "sLong=" + sLong +
                ", sSort=" + sSort +
                '}';
    }
}

/**
 * Compares two {@code Size}s based on their areas.
 */
class CompareSizesByArea implements Comparator<Size> {

    @Override
    public int compare(Size lhs, Size rhs) {
        // We cast here to ensure the multiplications won't overflow
        return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                (long) rhs.getWidth() * rhs.getHeight());
    }
}
