# Android Mini Server & AI Hub
**Powered By NhutCoder Team**

## Giới thiệu
Android Mini Server là một ứng dụng di động mạnh mẽ cho phép bạn chạy một máy chủ thu nhỏ trực tiếp trên thiết bị Android của mình. Phiên bản hiện tại tích hợp cả Terminal mô phỏng nâng cao (cài đặt Pip, kịch bản Python) cùng với **Bộ Não AI** kết nối qua REST API tới nhiều mô hình thông minh (Gemini, OpenAI, Anthropic, DeepSeek).

## 🚀 Tính năng nổi bật - Phiên bản v1.0.0
- **Máy chủ HTTP Cục bộ**: Cấu hình port, token trực tiếp qua giao diện.
- **Python & Terminal Shell**: Giao diện Termux giả lập hoạt động mượt mà, hỗ trợ lệnh sh, cài đặt `pip install`, và cảnh báo chạy script Python cục bộ.
- **AI Hub - Bộ Não AI**: Kết nối đa nền tảng trí thông minh nhân tạo, với Stream Response mô phỏng và hỗ trợ Tokenize Logging tiên tiến.
- **Auto Update qua GitHub Releases**: (MỚI IN v1.0.0)
  - Ứng dụng tự động kiểm tra mỗi khi khởi chạy để phát hiện phiên bản mới từ GitHub Releases.
  - Tích hợp thông báo UI hiển thị log (Release Notes) của phiên bản mới.
  - Hỗ trợ tải xuống và tự động cài đặt `app-release.apk` một cách liền mạch!

## 🔧 Workflow Cập Nhật Nội Bộ (Dành cho Developer)
Ứng dụng đã được tích hợp GitHub Actions `.github/workflows/build-release.yml`.
Để phát hành bản cập nhật Fix lỗi hoặc Thêm tính năng mới, hãy làm theo quy trình:

1. **Commit code**: Với mô tả rõ ràng (VD: `Fix: Sửa lỗi hiển thị UI` hoặc `Feat: Thêm tính năng Auto Update`).
2. **Đánh Tag phiên bản mới**: 
   - HÃY NHỚ RẰNG MỖI LẦN CÓ BẢN FIX HAY THÊM TÍNH NĂNG MỚI HÃY GHI VERSION NHA!
   - Sử dụng tag với định dạng `v*.*.*` (Ví dụ: `git tag v1.0.1`).
   - Push tag lên repo: `git push origin v1.0.1`.
3. **GitHub Actions Tự Động Hóa**:
   - Hệ thống sẽ tự động bắt sự kiện Push Tag.
   - Biên dịch ứng dụng (`assembleRelease`).
   - Ký ứng dụng bằng Keystore base64 (Signed APK).
   - Đẩy trực tiếp `app-release.apk` lên phần **Releases** với nội dung trích xuất từ tag!

*📝 Lưu ý cho người dùng ứng dụng: Khi ứng dụng hiện thông báo "Cập nhật ngay", mọi thông tin lỗi được Fix hay Tính năng mới đều sẽ được hiển thị trên Dialog.*

**Đội Ngũ:** NhutCoder Team & Google AI Studio Agent.
