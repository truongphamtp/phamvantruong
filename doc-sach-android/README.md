# Đọc Sách — ứng dụng đọc sách cho Android

Ứng dụng đọc sách điện tử chạy hoàn toàn trên máy (không cần mạng). Trang đọc theo phong cách máy đọc sách
Kindle; giao diện theo phong cách **"Liquid Glass" của HyperOS 4** (kính mờ, viền sáng, bo góc lớn, nền xanh
loang); tối ưu cho màn hình gập **Xiaomi Mix Fold 2**; thanh công cụ nằm **bên phải** cho tay phải dễ thao tác.

## Tính năng

- Đọc **EPUB**, **PDF**, **TXT** (TXT tự nhận bảng mã và tự tách chương "Chương 1", "Hồi thứ hai"...).
- **Tương thích EPUB tự làm để gửi sang Kindle** (xuất từ Google Docs, Word, LibreOffice, Calibre, Sigil...):
  chữ đậm/nghiêng, căn giữa/căn phải, thụt đầu dòng khai báo bằng CSS; tiêu đề/phụ đề Google Docs;
  ngắt trang (`page-break`, `<mbp:pagebreak/>`); mục lục; điểm "Bắt đầu đọc" (guide/landmarks) — mở lần đầu
  sẽ nhảy tới phần nội dung chính như Kindle.
- **Màn hình gập**: mở máy hiện 2 trang như sách giấy (rãnh giữa trùng bản lề), gập máy hiện 1 trang;
  gập/mở giữa chừng vẫn giữ đúng chỗ đang đọc.
- **Thanh công cụ dọc bên phải** (có thể chuyển sang trái): Thư viện, Mục lục, Tìm, Hiển thị, Dấu trang, Đọc to.
- Lật trang bằng chạm, vuốt hoặc **phím âm lượng**; hiệu ứng trượt có thể tắt.
- Giao diện kính mờ kiểu HyperOS 4: thanh công cụ, bảng cài đặt, hộp thoại đều là "tấm kính" nổi làm mờ nội dung
  phía sau (Android 12 trở lên), công tắc/thanh trượt/nút chọn kiểu HyperOS, chế độ sáng và tối theo hệ thống.
- Tuỳ chỉnh: 4 màu nền (Trắng, Giấy ngà, Xanh dịu, Đen), cỡ chữ, phông có chân/không chân, giãn dòng, lề,
  căn đều, độ sáng riêng khi đọc.
- Thư viện có ảnh bìa (tự tạo bìa nếu sách không có), "Đọc tiếp", sắp xếp, tìm theo tên/tác giả.
- Mục lục, dấu trang, **tìm kiếm không dấu** ("tieng viet" tìm được "tiếng Việt").
- **Đọc to** bằng giọng tiếng Việt của Android, tô sáng câu đang đọc, tự lật trang.
- Tự nhớ trang đang đọc; mở tệp trực tiếp từ Quản lý tệp, Zalo, Google Drive...
- Có sẵn cuốn "Hướng dẫn sử dụng" trong thư viện khi mở lần đầu.

## Cài vào điện thoại

Mỗi lần mã nguồn thay đổi, GitHub Actions tự đóng gói tệp cài đặt `DocSach.apk`:

1. Trên điện thoại, mở trang
   <https://github.com/truongphamtp/phamvantruong/releases/tag/doc-sach-moi-nhat>
   và bấm vào **DocSach.apk** để tải về.
   (Hoặc vào tab **Actions** của kho → lần chạy mới nhất → mục *Artifacts* → `DocSach-apk`.)
2. Mở tệp vừa tải. Nếu máy hỏi, cho phép trình duyệt/Quản lý tệp **cài ứng dụng không rõ nguồn gốc**.
3. Bản mới cài đè lên bản cũ, giữ nguyên thư viện và chỗ đang đọc.

Yêu cầu Android 8.0 trở lên.

## Đọc file EPUB bạn tự làm

- Google Docs: *Tệp → Tải xuống → EPUB Publication (.epub)*.
- Word: *Tệp → Lưu thành → EPUB* (hoặc dùng Calibre để chuyển `.docx` sang `.epub`).
- Dùng kiểu **Heading 1** cho tên chương để có mục lục, chèn **ngắt trang** trước mỗi chương.
- Gửi tệp sang điện thoại rồi bấm vào tệp, chọn **Đọc Sách**.
- Tệp MOBI/AZW (định dạng riêng của Kindle) chưa đọc được — hãy dùng EPUB.

## Tự đóng gói

- **GitHub Actions**: quy trình `.github/workflows/doc-sach-android.yml` chạy kiểm thử và đóng gói APK.
- **Android Studio**: mở thư mục `doc-sach-android`, chọn *Run*.
- Dòng lệnh (cần Android SDK, JDK 17): `./gradlew assembleRelease` → `app/build/outputs/apk/release/`.

Khoá ký `app/doc-sach-dev.keystore` chỉ dùng để cài thử (giữ cùng chữ ký giữa các bản). Nếu phát hành lên
Google Play, hãy tạo khoá riêng và giữ bí mật.

## Cấu trúc mã nguồn

| Thư mục (`app/src/main/java/io/github/truongphamtp/docsach/`) | Nội dung |
| --- | --- |
| `format/` | Đọc EPUB (kể cả CSS kiểu Kindle), TXT, dàn trang, tìm kiếm không dấu, tách câu |
| `reader/` | Màn hình đọc EPUB/TXT và PDF, mục lục, tìm kiếm, cài đặt hiển thị |
| `library/` | Màn hình thư viện |
| `ui/` | Phong cách HyperOS 4 (kính mờ, công tắc, thanh trượt...), thanh công cụ bên phải, bảng trượt, cử chỉ lật trang |
| `data/` | Lưu thư viện, cài đặt, nhập sách |
| `platform/` | Phần riêng của Android: PDF, giọng đọc, ảnh, bản lề màn hình gập |

Kiểm thử: `./gradlew testDebugUnitTest` (đọc EPUB/TXT, EPUB kiểu Google Docs/Calibre, dàn trang, tìm kiếm).
