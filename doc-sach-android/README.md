# Đọc Sách — ứng dụng đọc sách cho Android

Ứng dụng đọc sách điện tử chạy hoàn toàn trên máy (không cần mạng; đồng bộ giữa điện thoại và máy tính bảng
qua Wi‑Fi, không cần tài khoản). Trang đọc theo phong cách máy đọc sách Kindle; giao diện theo phong cách **"Liquid Glass"
của HyperOS 4** (kính mờ, viền sáng, bo góc lớn, nền xanh loang); tối ưu cho màn hình gập **Xiaomi Mix Fold 2** và
**máy tính bảng 11,5"**; thanh công cụ nằm **bên phải** cho tay phải dễ thao tác.

## Tính năng

- Đọc **EPUB**, **PDF**, **TXT** (TXT tự nhận bảng mã và tự tách chương "Chương 1", "Hồi thứ hai"...).
- **Tương thích EPUB tự làm để gửi sang Kindle** (xuất từ Google Docs, Word, LibreOffice, Calibre, Sigil...):
  chữ đậm/nghiêng, căn giữa/căn phải, thụt đầu dòng khai báo bằng CSS; tiêu đề/phụ đề Google Docs;
  ngắt trang (`page-break`, `<mbp:pagebreak/>`); mục lục; điểm "Bắt đầu đọc" (guide/landmarks) — mở lần đầu
  sẽ nhảy tới phần nội dung chính như Kindle.
- **Màn hình gập**: mở máy hiện 2 trang như sách giấy (rãnh giữa trùng bản lề), gập máy hiện 1 trang;
  gập/mở giữa chừng vẫn giữ đúng chỗ đang đọc.
- **Thanh công cụ dọc bên phải** (có thể chuyển sang trái): Thư viện, Mục lục, Tìm, Hiển thị, Dấu trang, Đọc to.
- Lật trang bằng chạm, vuốt hoặc **phím âm lượng**. Khi vuốt, trang **đi theo ngón tay** và nhả tay thì trượt nốt
  hoặc bật về (vuốt nhanh là lật); hiệu ứng có thể tắt.
- Chuyển động mềm kiểu HyperOS: nút co nhẹ khi chạm và nảy về, bảng/kính trượt ra bằng lò xo, sách trong thư viện
  trượt mượt khi sắp xếp lại, mở sách "phóng ra" từ thư viện.
- **Máy tính bảng** (vd. 11,5" 2456×1600): nằm ngang hiện 2 trang, dựng đứng hiện 1 trang; xoay máy vẫn giữ chỗ
  đang đọc. Thư viện, sổ ghi chú tự chia cột theo bề rộng màn hình.
- **Đồng bộ qua Wi‑Fi** giữa điện thoại và máy tính bảng — không cần tài khoản: danh sách sách, tệp sách, vị trí
  đọc, dấu trang, ghi chú (xem mục bên dưới). Có thêm đồng bộ qua OneDrive (nâng cao).
- Giao diện kính mờ kiểu HyperOS 4: thanh công cụ, bảng cài đặt, hộp thoại đều là "tấm kính" nổi làm mờ nội dung
  phía sau (Android 12 trở lên), công tắc/thanh trượt/nút chọn kiểu HyperOS, chế độ sáng và tối theo hệ thống.
- Tuỳ chỉnh: 4 màu nền (Trắng, Giấy ngà, Xanh dịu, Đen), cỡ chữ, phông có chân/không chân, giãn dòng, lề,
  căn đều, độ sáng riêng khi đọc.
- Thư viện có ảnh bìa (tự tạo bìa nếu sách không có), "Đọc tiếp", sắp xếp, tìm theo tên/tác giả.
- Mục lục, dấu trang, **tìm kiếm không dấu** ("tieng viet" tìm được "tiếng Việt").
- **Ghi chú và đánh dấu** kiểu Kindle: nhấn giữ một chữ rồi kéo để chọn đoạn → chọn màu tô (vàng, xanh lá, xanh
  dương, hồng), viết ghi chú, sao chép; chạm vào đoạn đã tô để sửa/xoá. **Sổ ghi chú** (nút "Ghi chú" ở thư viện)
  gom ghi chú của mọi cuốn sách — bấm vào ghi chú là mở đúng đoạn đó. PDF: nhấn giữ trên trang để ghi chú cả trang.
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

## Đồng bộ qua Wi‑Fi (không cần tài khoản)

**Ghép đôi một lần:**

1. Trên cả hai máy: ở **Thư viện**, bấm nút **Đồng bộ** trên thanh công cụ → **Ghép đôi máy mới**.
   Mỗi máy hiện một mã 6 số.
2. Trên một máy, chọn tên máy kia trong danh sách *"Hoặc nhập mã của máy kia vào đây"* rồi nhập mã đang hiện
   trên máy kia. Xong!

Nếu danh sách không hiện máy kia (một số mạng ở trường, quán cà phê chặn các máy nói chuyện với nhau): bật
**điểm phát Wi‑Fi** trên điện thoại cho máy tính bảng kết nối vào, hoặc bấm *"Không thấy máy kia? Nhập địa chỉ"*
và gõ địa chỉ hiện trên máy kia (dạng `192.168.1.5:47123`).

**Sau đó:** mở ứng dụng trên cả hai máy, dùng chung Wi‑Fi là tự đồng bộ — ngay khi thấy nhau, vài giây sau khi
thêm ghi chú/dấu trang, và khi lật trang. Đồng bộ danh sách sách, **tệp sách**, vị trí đang đọc, dấu trang, ghi chú,
sách đã xoá. Đang mở một cuốn mà máy kia đã đọc xa hơn thì ứng dụng hỏi *"Đọc tiếp từ máy khác?"*.

- Hai máy cần **mở ứng dụng cùng lúc** (không cần mở cùng cuốn sách). Đọc trên điện thoại ở ngoài rồi về nhà đọc
  trên máy tính bảng: mở ứng dụng trên cả hai máy một lát là tiến độ khớp nhau.
- Dữ liệu đi thẳng giữa hai máy trong mạng nhà bạn, không qua Internet. Lúc ghép đôi, hai máy tạo một khoá
  chung (ECDH + mã 6 số); mọi yêu cầu sau đó đều được ký, dữ liệu thư viện được mã hoá AES‑GCM. Máy chưa ghép
  đôi không đọc được gì.
- Cài đặt hiển thị (cỡ chữ, màu nền…) giữ riêng từng máy.

## Đồng bộ OneDrive (nâng cao)

Ứng dụng lưu dữ liệu đồng bộ trong **OneDrive của chính bạn**, ở thư mục riêng `Ứng dụng › <tên ứng dụng>`
(ứng dụng chỉ có quyền với thư mục này, không đọc được các tệp khác). Microsoft yêu cầu mỗi ứng dụng phải có
**mã ứng dụng (Client ID)** — bạn tự tạo một lần, miễn phí, khoảng 5 phút.

### Bước 1 — Tạo mã ứng dụng trên trang của Microsoft

1. Mở <https://entra.microsoft.com> (hoặc <https://portal.azure.com>) và đăng nhập bằng tài khoản Microsoft dùng
   OneDrive.
   - Gặp thông báo *"Tài khoản người dùng được chọn không tồn tại trong đối tượng thuê 'Microsoft Services'…"*
     (mã lỗi AADSTS50020)? Nghĩa là tài khoản Microsoft cá nhân chưa có **thư mục (tenant)** — từ giữa năm 2024
     Microsoft buộc mọi ứng dụng phải được đăng ký trong một thư mục. Chọn một trong các cách:
     - **Tài khoản Microsoft 365 của trường/cơ quan** (ví dụ `…@….edu.vn`): mở cửa sổ ẩn danh, đăng nhập
       <https://entra.microsoft.com> bằng tài khoản đó rồi làm tiếp bước 2 (vẫn chọn loại tài khoản có
       *personal Microsoft accounts* để sau này đăng nhập OneDrive cá nhân). Nếu bị báo không có quyền đăng ký
       ứng dụng, nhờ quản trị viên của trường hoặc dùng cách dưới.
     - **Đăng ký Azure miễn phí** bằng chính tài khoản cá nhân tại <https://azure.microsoft.com/free> (cần số điện
       thoại và thẻ Visa/Mastercard để xác minh; đăng ký ứng dụng không mất phí). Microsoft tự tạo thư mục; sau đó
       vào <https://portal.azure.com>, gõ tìm **App registrations**.
     - **Nhờ người có tài khoản Azure** tạo giúp rồi gửi mã ứng dụng cho bạn. Mã này không phải mật khẩu; dữ liệu
       vẫn nằm trong OneDrive của bạn và người tạo không xem được.
2. **App registrations** (Đăng ký ứng dụng) → **New registration**:
   - **Name**: `Đọc Sách` — đây cũng là tên thư mục trong OneDrive (`Ứng dụng › Đọc Sách`).
   - **Supported account types**: *Accounts in any organizational directory and personal Microsoft accounts*.
   - **Redirect URI**: chọn *Public client/native (mobile & desktop)* và nhập
     `https://login.microsoftonline.com/common/oauth2/nativeclient`.
   - Bấm **Register**.
3. Ở trang **Overview**, sao chép **Application (client) ID** (dạng `1a2b3c4d-…`).
4. (Nên làm) **Authentication** → *Advanced settings* → **Allow public client flows** = *Yes* → **Save**.

Quyền ứng dụng xin khi đăng nhập: `Files.ReadWrite.AppFolder` (chỉ thư mục riêng), `User.Read` (tên tài khoản),
`offline_access` (giữ đăng nhập). Không cần thêm quyền nào khác.

### Bước 2 — Đăng nhập trong ứng dụng

1. Ở **Thư viện**, bấm nút **Đồng bộ** (hình đám mây) trên thanh công cụ.
2. Dán mã ứng dụng → **Lưu mã ứng dụng** → **Đăng nhập tài khoản Microsoft**, rồi bấm *Chấp nhận* khi Microsoft hỏi.
3. Làm giống vậy trên máy còn lại (cùng mã ứng dụng, cùng tài khoản).

Không muốn dán mã trên từng máy? Thêm biến `ONEDRIVE_CLIENT_ID` (giá trị là mã ứng dụng) ở GitHub: *Settings →
Secrets and variables → Actions → Variables*; các bản APK đóng gói sau đó có sẵn mã.

### Đồng bộ hoạt động thế nào

- Tự đồng bộ khi mở ứng dụng, khi rời ứng dụng, khi đóng sách, vài giây sau khi thêm/sửa ghi chú hay dấu trang,
  và vài phút một lần trong lúc đọc. Bấm **Đồng bộ ngay** nếu cần.
- **Vị trí đọc**: lấy theo lần lật trang gần nhất. Đang mở sách mà máy khác đã đọc xa hơn thì ứng dụng hỏi
  *"Đọc tiếp từ máy khác?"*.
- **Ghi chú, dấu trang**: gộp từ mọi máy; sửa ở hai nơi thì giữ bản sửa sau cùng; xoá ở máy này thì máy kia cũng xoá.
- **Sách**: thêm ở máy này thì máy kia tự tải về; xoá ở máy này thì máy kia cũng xoá (kể cả tệp trên OneDrive).
- **Tệp sách** mặc định chỉ chuyển khi dùng Wi‑Fi (tắt được trong bảng Đồng bộ). Vị trí đọc và ghi chú rất nhẹ nên
  luôn đồng bộ.
- **Không đồng bộ** cài đặt hiển thị (cỡ chữ, màu nền…) để mỗi máy hợp với màn hình của nó.
- Trên OneDrive: `thu-vien.json` (danh sách, vị trí, ghi chú) và thư mục `sach/` (tệp sách). OneDrive miễn phí có 5 GB.
- Mã đăng nhập (token) được mã hoá bằng khoá Android Keystore, không rời khỏi máy; ứng dụng không bao giờ thấy mật khẩu.

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
| `sync/` | Đồng bộ: lõi gộp dữ liệu giữa các máy; `sync/lan/` qua Wi‑Fi (ghép đôi, máy chủ nhỏ, mDNS); OneDrive (OAuth + PKCE, Microsoft Graph) |
| `platform/` | Phần riêng của Android: PDF, giọng đọc, ảnh, bản lề màn hình gập |

Kiểm thử: `./gradlew testDebugUnitTest` (đọc EPUB/TXT, EPUB kiểu Google Docs/Calibre, dàn trang, tìm kiếm;
gộp dữ liệu đồng bộ; hai "máy" đồng bộ qua Wi‑Fi và qua OneDrive giả lập; gọi Microsoft Graph với máy chủ giả lập;
lật trang theo từng khung hình).
