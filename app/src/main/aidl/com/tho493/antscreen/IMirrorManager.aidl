package com.tho493.antscreen;

/**
 * Giao tiếp IPC giữa ứng dụng AntScreen và system_server.
 */
interface IMirrorManager {
    /**
     * Bắn sự kiện cảm ứng trực tiếp vào màn hình chiếu.
     */
    void injectTouch(int action, float x, float y, int displayId);

    /**
     * Điều hướng về màn hình chính (Home).
     */
    void handleHome();

    /**
     * Quay lại (Back).
     */
    void handleBack();

    /**
     * Mở danh sách ứng dụng gần đây (Recents).
     */
    void toggleRecents();

    /**
     * Khởi chạy ứng dụng trực tiếp vào VirtualDisplay trên xe.
     */
    void launchAppOnDisplay(String packageName, int displayId);

    /**
     * Cập nhật ID của VirtualDisplay hiện tại đang hiển thị trên xe.
     */
    void setCurrentDisplayId(int displayId);

    /**
     * Lấy số lượng tác vụ ứng dụng bên thứ ba đang chạy trên VirtualDisplay.
     */
    int getRunningTaskCount(int displayId);
}
