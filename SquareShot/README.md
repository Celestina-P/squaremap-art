# SquareShot

Plugin Paper: ghép các tile của **squaremap** thành 1 ảnh, rồi gửi lên Discord bằng **webhook**
và **sửa lại cùng 1 tin nhắn** mỗi vài phút (không spam kênh). Không cần token bot.

## 1. Build ra file .jar (không cần cài gì trên máy)
1. Tạo tài khoản GitHub, tạo repo mới (Private cũng được).
2. Upload **toàn bộ nội dung** thư mục này lên repo (giữ nguyên cấu trúc, nhớ cả thư mục `.github`).
3. Vào tab **Actions**, chờ job "Build SquareShot" chạy xong (dấu tích xanh, ~1-2 phút).
4. Bấm vào lần chạy đó, kéo xuống **Artifacts**, tải `SquareShot` về, giải nén ra `SquareShot.jar`.

Nếu Actions báo đỏ, mở log và gửi cho mình đoạn lỗi.

## 2. Cài
1. Bỏ `SquareShot.jar` vào `plugins/` của server, restart. Plugin sẽ tạo `plugins/SquareShot/config.yml`.
2. Tạo webhook: Discord > chuột phải kênh muốn đăng bản đồ > Chỉnh sửa kênh > Tích hợp > Webhook > Webhook mới > Sao chép URL.
3. Dán URL vào `webhook-url` trong config, lưu, rồi gõ `/squareshot reload` (hoặc restart).
4. Gõ `/squareshot now` để gửi ngay. Sau đó plugin tự cập nhật theo `interval-minutes`.

Lệnh (cần quyền op hoặc `squareshot.admin`): `/squareshot now`, `/squareshot reload`.

## 3. Yêu cầu
- Đã cài squaremap và đã render bản đồ (`squaremap fullrender world`). Plugin chỉ đọc các file tile squaremap đã tạo sẵn.
- Java 21 để build, server chạy Paper 26.2 (Java 25) đều được.
- **URL webhook là bí mật**, ai có nó là đăng được lên kênh. Đừng đăng công khai.

## 4. Cách hoạt động
- Đọc `plugins/squaremap/web/tiles/<thế giới>/<zoom>/<x>_<z>.png`.
- Tự chọn thế giới có chữ "overworld" (hoặc theo `world` trong config) và lớp zoom chi tiết nhất mà vẫn vừa `max-image-size`.
- Ghép, thu nhỏ nếu cần, gửi PNG lên webhook. Lần đầu gửi tin mới, các lần sau sửa lại tin đó (id tin nhắn lưu trong `state.properties`). Nếu bạn xóa tin nhắn, plugin tự gửi tin mới.
- Chạy trên luồng riêng nên không làm lag server, và vẫn chạy khi server ở trạng thái pause-when-empty.

## 5. Giới hạn
- Chưa có marker người chơi trên ảnh (cần biết chính xác tỉ lệ block/pixel của squaremap).
- Ảnh là bản đồ tĩnh, cập nhật theo chu kỳ, không phải thời gian thực.
