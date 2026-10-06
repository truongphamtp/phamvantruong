# 📒 Sổ ghi chép công việc

Ứng dụng web quản lý công việc cá nhân theo kiểu sổ ghi chép, đồng bộ hai chiều với **Google Calendar**.
Chạy hoàn toàn trên trình duyệt (HTML/CSS/JavaScript thuần), không cần máy chủ, có thể đăng lên GitHub Pages.

## Tính năng

- **Ghi chép công việc**: tiêu đề, nội dung ghi chú (trang giấy kẻ dòng), ngày, giờ bắt đầu/kết thúc,
  trạng thái (Cần làm / Đang làm / Hoàn thành), mức ưu tiên, nhãn, nhắc nhở.
- **Thêm nhanh bằng tiếng Việt tự nhiên**, ví dụ:
  | Bạn gõ | Kết quả |
  |---|---|
  | `Họp nhóm ngày mai 9h-10h30 #công-việc !` | Ngày mai, 09:00–10:30, nhãn *công-việc*, ưu tiên cao |
  | `Nộp báo cáo 15/10 #học-tập` | Ngày 15/10, cả ngày |
  | `Gọi điện cho mẹ thứ 7 8h tối` | Thứ Bảy gần nhất, 20:00 |
  | `Đi chợ 2h chiều` | Hôm nay, 14:00 |

  Hỗ trợ: `hôm nay`, `ngày mai`, `ngày kia`, `thứ 2`…`thứ 7`, `chủ nhật`, `15/10`, `15/10/2026`,
  `9h`, `9h30`, `14:00`, `9h-11h`, `sáng/trưa/chiều/tối`, `#nhãn`, `!` (ưu tiên cao).
- **Các mục xem**: Hôm nay (kèm việc quá hạn), 7 ngày tới, Tất cả, Ghi chú không hẹn ngày, Đã hoàn thành, lọc theo nhãn.
- **Tìm kiếm không dấu**: gõ `bao cao` vẫn tìm thấy “Báo cáo”.
- **Đồng bộ Google Calendar**:
  - Mỗi công việc có ngày → một sự kiện trên Google Calendar (có giờ → sự kiện theo giờ; không giờ → sự kiện cả ngày).
  - Việc đã xong hiện dấu `✓` và màu xám; ưu tiên cao màu đỏ.
  - Sửa/xoá trên ứng dụng → cập nhật lên Google; sửa/xoá trên Google Calendar (web, điện thoại) → cập nhật về sổ.
  - Trạng thái, ưu tiên, nhãn được lưu kèm trong sự kiện nên mở ứng dụng ở máy khác vẫn thấy đầy đủ.
  - Tự đồng bộ khi có thay đổi và mỗi 5 phút.
- **Mục “Lịch Google”**: xem lịch trình 14 ngày tới từ các lịch của bạn, bấm “Thêm vào sổ” để ghi chép thêm cho một sự kiện.
- **Dùng được khi chưa kết nối / mất mạng**: dữ liệu luôn lưu trên trình duyệt trước, đồng bộ sau.
- **Sao lưu**: xuất/nhập file `.json`.
- Giao diện sáng/tối tự động, dùng tốt trên điện thoại. Phím tắt: `N` thêm việc, `/` tìm kiếm.

## Cài đặt kết nối Google Calendar (làm một lần)

Ứng dụng cần một **OAuth Client ID** của riêng bạn để được phép đọc/ghi Google Calendar.

1. **Tạo dự án**: vào <https://console.cloud.google.com/> → chọn *Select a project* → *New project* → đặt tên (vd. `So ghi chep`) → *Create*.
2. **Bật Google Calendar API**: menu *APIs & Services* → *Library* → tìm **Google Calendar API** → *Enable*.
3. **Màn hình đồng ý OAuth** (*Google Auth Platform* / *OAuth consent screen*):
   - *Get started* → nhập tên ứng dụng, email hỗ trợ → *Audience*: chọn **External** → hoàn tất.
   - Trong mục *Audience* → *Test users* → **Add users** → thêm địa chỉ Gmail của bạn.
     (Ở chế độ *Testing* chỉ những tài khoản trong danh sách này đăng nhập được — đủ cho dùng cá nhân.)
4. **Tạo Client ID**: mục *Clients* (hoặc *Credentials* → *Create credentials* → *OAuth client ID*):
   - *Application type*: **Web application**.
   - *Authorized JavaScript origins*: thêm
     - `https://truongphamtp.github.io` (khi dùng trên GitHub Pages)
     - `http://localhost:8000` (khi chạy thử trên máy)
   - Không cần *Authorized redirect URIs*. Bấm *Create* và sao chép **Client ID** (dạng `xxxx.apps.googleusercontent.com`).
5. **Nhập Client ID vào ứng dụng**: mở ứng dụng → ⚙ **Cài đặt** → dán vào ô *Google OAuth Client ID*.
   (Hoặc ghi sẵn vào `config.js`: `export const GOOGLE_CLIENT_ID = '...'` — Client ID không phải thông tin bí mật.)
6. Bấm **Kết nối Google Calendar** → chọn tài khoản → Google sẽ cảnh báo *“Google chưa xác minh ứng dụng này”*
   (bình thường với ứng dụng cá nhân) → *Tiếp tục* → đánh dấu cho phép truy cập lịch → *Tiếp tục*.

Trong ⚙ Cài đặt, bạn có thể chọn lịch để lưu công việc (mặc định là lịch chính) hoặc bấm
**“Tạo lịch riêng ‘Sổ ghi chép’”** để tách công việc khỏi các sự kiện khác. Nếu dùng trên nhiều máy, hãy chọn
cùng một lịch trên tất cả các máy.

> Vì ứng dụng chạy hoàn toàn trên trình duyệt, Google chỉ cấp quyền truy cập trong khoảng **1 giờ** mỗi lần đăng nhập.
> Khi hết hạn, bấm **“Kết nối lại Google”** (không phải nhập lại gì). Trong lúc đó bạn vẫn ghi chép bình thường,
> thay đổi sẽ được đồng bộ khi kết nối lại.

## Đăng lên GitHub Pages

1. Gộp nhánh chứa ứng dụng vào nhánh `main`.
2. Trên GitHub: *Settings* → *Pages* → *Build and deployment* → *Source*: **Deploy from a branch**,
   *Branch*: `main`, thư mục `/ (root)` → *Save*.
3. Sau 1–2 phút, ứng dụng có tại: `https://truongphamtp.github.io/phamvantruong/so-ghi-chep/`

## Chạy thử trên máy

```bash
cd so-ghi-chep
python3 -m http.server 8000
```

Mở <http://localhost:8000>. (Không mở trực tiếp file `index.html` bằng `file://` vì trình duyệt chặn ES module và
đăng nhập Google.)

## Cách đồng bộ hoạt động

- Mỗi lần đồng bộ: **kéo** các sự kiện do ứng dụng tạo từ Google về trước, rồi **đẩy** thay đổi trên máy lên.
- Sự kiện do ứng dụng tạo được đánh dấu bằng thuộc tính riêng `sotay=1` (không hiển thị trên Google Calendar),
  nên các sự kiện khác của bạn không bị đụng tới.
- Nếu cùng một việc bị sửa ở cả hai nơi, **bản sửa sau cùng** được giữ lại.
- Khi cập nhật, ứng dụng giữ nguyên các thông tin bạn thêm trên Google Calendar như địa điểm, khách mời.
- Ghi chú **không có ngày**, hoặc bỏ chọn “Đồng bộ lên Google Calendar”, chỉ lưu trên máy (biểu tượng 💾).
  ☁️ = đã có trên Google, ⏳ = đang chờ đồng bộ.
- Việc thêm từ mục “Lịch Google” mặc định **không** đồng bộ ngược lên để tránh tạo sự kiện trùng.

## Quyền riêng tư

Không có máy chủ trung gian: dữ liệu chỉ nằm trong trình duyệt của bạn (`localStorage`) và trong Google Calendar
của chính bạn. Quyền đăng nhập Google được lưu tạm trong phiên trình duyệt và có thể thu hồi bằng nút
**Ngắt kết nối** hoặc tại <https://myaccount.google.com/permissions>.

## Cấu trúc mã nguồn

```
so-ghi-chep/
├── index.html      Giao diện
├── styles.css      Kiểu dáng (sáng/tối, điện thoại)
├── config.js       Google OAuth Client ID (tuỳ chọn)
└── js/
    ├── app.js      Điều khiển giao diện
    ├── store.js    Lưu trữ trên trình duyệt (localStorage)
    ├── gcal.js     Đăng nhập Google & gọi Google Calendar API
    ├── sync.js     Đồng bộ hai chiều
    └── utils.js    Ngày giờ, tìm kiếm không dấu, phân tích “thêm nhanh”
```
