package com.tho493.antscreen.projection;

public class TouchCoordinateMapper {
    private int mCarWidth = 1;
    private int mCarHeight = 1;
    private int mPhoneWidth = 1;
    private int mPhoneHeight = 1;

    public void updateDimensions(int carW, int carH, int virtualW, int virtualH) {
        if (carW > 0) this.mCarWidth = carW;
        if (carH > 0) this.mCarHeight = carH;
        if (virtualW > 0) this.mPhoneWidth = virtualW;
        if (virtualH > 0) this.mPhoneHeight = virtualH;
    }

    public float mapX(float carX) {
        if (mCarWidth <= 0 || mPhoneWidth <= 0 || mCarWidth == mPhoneWidth) {
            return carX;
        }
        return (carX / (float) mCarWidth) * (float) mPhoneWidth;
    }

    public float mapY(float carY) {
        if (mCarHeight <= 0 || mPhoneHeight <= 0 || mCarHeight == mPhoneHeight) {
            return carY;
        }
        return (carY / (float) mCarHeight) * (float) mPhoneHeight;
    }
}
