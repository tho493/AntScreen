# AntScreen - Android Auto Independent Mirroring Engine

> ⚠️ **Tuyên bố quan trọng / Disclaimer:**  
> **AntScreen** là một dự án **nghiên cứu kỹ thuật & học thuật (Proof of Concept - PoC)** nhằm tìm hiểu và thử nghiệm cơ chế hoạt động nội tại của hệ điều hành Android (Zygote fork, System Server, VirtualDisplay, Binder IPC) và giao thức trình chiếu Android Auto (Gearhead).  
> Dự án **KHÔNG** nhằm mục đích thương mại và **KHÔNG** khuyến khích người dùng thao tác, xem video hoặc tương tác với màn hình trong quá trình điều khiển phương tiện tham gia giao thông để đảm bảo an toàn tuyệt đối. Tác giả không chịu trách nhiệm đối với bất kỳ rủi ro kỹ thuật, lỗi hệ thống hay vấn đề an toàn nào phát sinh từ việc sử dụng dự án này.

---

## 1. Giới thiệu tổng quan

**AntScreen** là giải pháp phản chiếu màn hình Android lên màn hình Android Auto (ô tô) một cách độc lập:
* **Hiển thị độc lập (Independent VirtualDisplay):** Sử dụng `VirtualDisplay` với `displayGroupId` tách biệt khỏi màn hình chính điện thoại. Cho phép điện thoại tắt màn hình hoặc chạy tác vụ khác trong khi xe vẫn hiển thị và hoạt động bình thường.
* **Tương tác cảm ứng 2 chiều:** Nhận sự kiện chạm cảm ứng từ màn hình xe hơi và inject trực tiếp vào hệ thống Android thông qua Binder IPC.
* **Thanh điều hướng ảo (Injected Virtual Nav-bar):** Tích hợp 4 nút điều hướng nhanh (Home, Back, Recents, Apps Drawer) trực tiếp trên giao diện xe.
* **Khởi chạy ứng dụng trực tiếp:** Mở và điều khiển các ứng dụng đã cài đặt trên điện thoại ngay từ màn hình xe.
* **Ngôn ngữ phát triển:** Viết hoàn toàn bằng Java thuần (Android SDK 35, Java 17) kết hợp native C++ cho tầng Zygisk hook.

---

## 2. Thiết bị & Phiên bản nghiên cứu hỗ trợ

Hệ thống được thiết kế và kiểm thử trên các môi trường thiết bị với thông số chi tiết như sau:

| Thành phần | Yêu cầu / Phiên bản nghiên cứu | Ghi chú |
| :--- | :--- | :--- |
| **Quyền quản trị** | **Root** (Magisk 26.0+ / KernelSU / APatch) | Bắt buộc bật **Zygisk** |
| **Kiến trúc CPU** | `arm64-v8a` (chính), `x86_64` (hỗ trợ giả lập) | Đã biên dịch sẵn binary native |
| **Hệ điều hành Android** | **Android 12L (API 32) đến Android 15 (API 35)** | Đã chuẩn bị tương thích thử nghiệm tới Android 17 |
| **Target SDK / Min SDK** | `minSdk: 32` - `compileSdk: 35` - `targetSdk: 35` | JDK 17 |
| **Android Auto (Gearhead)** | **Phiên bản 16.7 đến 17.6+** | Kiểm tra tự động tính tương thích trong app |
| **Google Play Services (GMS)** | `com.google.android.gms` phiên bản mới nhất | Hỗ trợ Car Service Projection |

---

## 3. Kiến trúc kỹ thuật 3 tầng

1. **Zygisk Native Module (`magisk-module/`):**
   * Can thiệp Zygote ngay khi fork tiến trình bằng thư viện C++ native (`arm64-v8a.so`, `x86_64.so`) và engine `libpine.so`.
   * Tích hợp kịch bản tự phục hồi an toàn chống bootloop (`post-fs-data.sh`, `service.sh`).
2. **System Server Hook (`system_server`):**
   * Can thiệp vào tiến trình `system_server` để khởi tạo Binder Service `antscreen_mirror` (`IMirrorManager`).
   * Quản lý tạo `VirtualDisplay` riêng biệt, cho phép giải phóng màn hình vật lý của điện thoại.
   * Xử lý inject phím bấm (`KeyEvent`) và thao tác cảm ứng (`MotionEvent`).
3. **Android Auto App & Hook (`com.tho493.antscreen` & `gearhead`):**
   * Can thiệp Android Auto (`gearhead`): Vượt qua cơ chế kiểm tra chữ ký và nguồn cài đặt (package installer check).
   * `AaActivityService` & `ProjectionFragment`: Đón nhận Surface từ giao thức ô tô và xử lý luồng render.

---

## 4. Hướng dẫn cài đặt & Triển khai

### Điều kiện tiên quyết:
* Điện thoại đã Root, cài đặt **Magisk** và đã **bật Zygisk** trong cài đặt Magisk.
* Điện thoại đã cài đặt ứng dụng Android Auto tương thích (v16.7 - v17.6+).

### Cách 1: Triển khai trực tiếp qua ứng dụng (Khuyến nghị)
1. Cài đặt file APK **AntScreen** vào điện thoại.
2. Mở ứng dụng **AntScreen** và cấp quyền **Superuser (Root)** khi được yêu cầu.
3. Trên giao diện ứng dụng, kiểm tra trạng thái tại mục **Điều kiện hệ thống**:
   * *System Version:* Đảm bảo Android >= 12L (API 32).
   * *Google Play Services:* Đã sẵn sàng.
   * *Android Auto:* Đã cài đặt phiên bản tương thích.
4. Chọn mục **Mirror Engine** -> Nhấn **Deploy Mirror Engine** (hoặc **Quick Deploy**). Ứng dụng sẽ tự động giải nén module và nạp vào `/data/adb/modules/antscreen`.
5. **Khởi động lại điện thoại (Reboot)** để Magisk & Zygisk kích hoạt module nạp vào `system_server`.
6. Sau khi khởi động lại, mở lại AntScreen kiểm tra mục **Mirror Engine** chuyển sang trạng thái **Đang hoạt động (Active / Online)**.

### Cách 2: Cài đặt qua file Zip trong Magisk Manager
1. Đóng gói toàn bộ thư mục `magisk-module/` thành định dạng `.zip` (hoặc lấy file zip module được xuất từ app).
2. Mở ứng dụng **Magisk** -> Chọn tab **Modules** -> Chọn **Install from storage**.
3. Chọn file zip vừa tạo và tiến hành flash.
4. Cài đặt file APK `AntScreen`.
5. Khởi động lại thiết bị.

---

## 5. Hướng dẫn kết nối và sử dụng

1. Kết nối điện thoại với màn hình xe hơi qua dây cáp USB (hoặc Android Auto Wireless).
2. Khi giao diện Android Auto trên xe khởi động, tìm và chọn biểu tượng ứng dụng **AntScreen**.
3. Màn hình điện thoại sẽ được phản chiếu lên xe:
   * Thao tác chạm, vuốt trên màn hình xe tương tác trực tiếp với điện thoại.
   * Sử dụng thanh 4 nút điều hướng ảo trên xe để Back, Home, mở Recents hoặc danh sách ứng dụng.
   * Màn hình điện thoại có thể tắt/khoá mà không làm gián đoạn hiển thị trên xe.

---

## 6. Xây dựng từ mã nguồn (Build from Source)

* **Yêu cầu môi trường:**
  * Android Studio Koala / Ladybug hoặc mới hơn.
  * JDK 17.
  * Android SDK API 35 và NDK r25+ (nếu chỉnh sửa mã nguồn native Zygisk C++ trong `jni/`).
* **Lệnh build APK:**
  ```bash
  ./gradlew assembleRelease
  ```
  File APK đầu ra nằm tại: `app/build/outputs/apk/release/`

---

## 7. Giấy phép & Tác giả

* **Tác giả:** `tho493`
* **Mục đích:** Nghiên cứu và chia sẻ học thuật kỹ thuật Android Internals.
