// Các hàm tiện ích dùng chung: ngày giờ, chuỗi, phân tích "thêm nhanh".

export const pad = (n) => String(n).padStart(2, '0');

export function toDateStr(d) {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

export function toTimeStr(d) {
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function parseDateStr(s) {
  const [y, m, d] = s.split('-').map(Number);
  return new Date(y, m - 1, d);
}

export const todayStr = () => toDateStr(new Date());

export function addDays(s, n) {
  const d = parseDateStr(s);
  d.setDate(d.getDate() + n);
  return toDateStr(d);
}

// Chuỗi RFC 3339 theo giờ địa phương, kèm độ lệch múi giờ (vd 2026-10-06T09:00:00+07:00).
export function toRfc3339(d) {
  const off = -d.getTimezoneOffset();
  const a = Math.abs(off);
  return `${toDateStr(d)}T${toTimeStr(d)}:00${off >= 0 ? '+' : '-'}${pad(Math.floor(a / 60))}:${pad(a % 60)}`;
}

const WEEKDAYS = ['Chủ nhật', 'Thứ Hai', 'Thứ Ba', 'Thứ Tư', 'Thứ Năm', 'Thứ Sáu', 'Thứ Bảy'];

export function shortDate(s) {
  const d = parseDateStr(s);
  const sameYear = d.getFullYear() === new Date().getFullYear();
  return `${pad(d.getDate())}/${pad(d.getMonth() + 1)}${sameYear ? '' : '/' + d.getFullYear()}`;
}

export function dateLabel(s) {
  const t = todayStr();
  if (s === t) return 'Hôm nay';
  if (s === addDays(t, 1)) return 'Ngày mai';
  if (s === addDays(t, -1)) return 'Hôm qua';
  return `${WEEKDAYS[parseDateStr(s).getDay()]}, ${shortDate(s)}`;
}

export function escapeHtml(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}

// Bỏ dấu tiếng Việt để tìm kiếm không phân biệt dấu / hoa thường.
export function normalize(s) {
  return String(s ?? '')
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/đ/g, 'd')
    .replace(/Đ/g, 'D')
    .toLowerCase();
}

export function uid() {
  if (globalThis.crypto?.randomUUID) return crypto.randomUUID();
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 10);
}

export function debounce(fn, ms) {
  let timer;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), ms);
  };
}

export function parseTags(text) {
  const tags = String(text || '')
    .split(/[,\n]/)
    .map((t) => t.trim().replace(/^#+/, '').trim())
    .filter(Boolean);
  return [...new Set(tags)];
}

// ---------------------------------------------------------------------------
// Thêm nhanh bằng câu tự nhiên, ví dụ:
//   "Họp nhóm ngày mai 9h-10h30 #công-việc !"
//   "Nộp báo cáo 15/10 #học-tập"
//   "Gọi điện cho mẹ thứ 7 8h tối"
// ---------------------------------------------------------------------------

const WEEKDAY_WORDS = [
  [/chủ nhật|cn/, 0],
  [/thứ (?:2|hai)|t2/, 1],
  [/thứ (?:3|ba)|t3/, 2],
  [/thứ (?:4|tư)|t4/, 3],
  [/thứ (?:5|năm)|t5/, 4],
  [/thứ (?:6|sáu)|t6/, 5],
  [/thứ (?:7|bảy)|t7/, 6],
];

function validDate(y, m, d) {
  const dt = new Date(y, m - 1, d);
  return dt.getFullYear() === y && dt.getMonth() === m - 1 && dt.getDate() === d ? toDateStr(dt) : '';
}

function to24h(hour, period) {
  if (!period) return hour;
  if ((period === 'chiều' || period === 'tối') && hour < 12) return hour + 12;
  if (period === 'trưa' && hour < 11) return hour + 12;
  if (period === 'đêm' && hour >= 9 && hour < 12) return hour + 12;
  if (period === 'sáng' && hour === 12) return 0;
  return hour;
}

export function parseQuickAdd(text, now = new Date()) {
  const today = toDateStr(now);
  const out = { title: '', tags: [], priority: 'normal', date: '', start: '', end: '' };
  // Đệm khoảng trắng hai đầu để dùng (?<=\s) / (?=\s) thay cho \b (\b không hiểu chữ có dấu).
  let s = ` ${String(text).trim()} `;
  // Cắt phần khớp khỏi tiêu đề; nếu fn trả về false thì giữ nguyên chữ đó.
  const cut = (re, fn) => {
    s = s.replace(re, (...m) => (fn(m) === false ? m[0] : ' '));
  };

  cut(/(?<=\s)#([^\s#]+)(?=\s)/g, (m) => {
    out.tags.push(m[1]);
  });
  cut(/(?<=\s)!{1,3}(?=\s)/g, () => {
    out.priority = 'high';
  });

  // Giờ: 9h, 9h30, 09:30, 9g, 9h-11h, 9:00 - 10:30, kèm "sáng/chiều/tối" tuỳ chọn.
  const time = '(\\d{1,2})(?:[hg](\\d{2})?|:(\\d{2}))';
  const timeRe = new RegExp(
    `(?<=\\s)(?:lúc\\s+)?${time}(?:\\s*-\\s*${time})?(?:\\s+(sáng|trưa|chiều|tối|đêm))?(?=\\s)`,
    'iu',
  );
  cut(timeRe, (m) => {
    const period = m[7]?.toLowerCase();
    const h1 = to24h(Number(m[1]), period);
    const min1 = Number(m[2] ?? m[3] ?? 0);
    if (h1 > 23 || min1 > 59) return false;
    out.start = `${pad(h1)}:${pad(min1)}`;
    if (m[4] !== undefined) {
      let h2 = Number(m[4]);
      if (period) h2 = to24h(h2, period);
      else if (h2 < h1 && h2 + 12 <= 23) h2 += 12; // "9h-2h" => 14h
      const min2 = Number(m[5] ?? m[6] ?? 0);
      if (h2 <= 23 && min2 <= 59) out.end = `${pad(h2)}:${pad(min2)}`;
    }
  });

  // Ngày cụ thể: 15/10, 15-10, 15/10/2026, có thể có chữ "ngày" phía trước.
  cut(/(?<=\s)(?:ngày\s+)?(\d{1,2})[/-](\d{1,2})(?:[/-](\d{2}|\d{4}))?(?=\s)/iu, (m) => {
    let y = m[3] ? Number(m[3]) : now.getFullYear();
    if (y < 100) y += 2000;
    const date = validDate(y, Number(m[2]), Number(m[1]));
    if (!date) return false;
    out.date = date;
  });

  // Ngày tương đối.
  const relative = [
    [/(?<=\s)hôm nay(?=\s)/iu, 0],
    [/(?<=\s)ngày mai(?=\s)/iu, 1],
    [/(?<=\s)(?:ngày kia|ngày mốt)(?=\s)/iu, 2],
  ];
  for (const [re, n] of relative) {
    if (!out.date) {
      cut(re, () => {
        out.date = addDays(today, n);
      });
    }
  }

  // Thứ trong tuần: lấy ngày gần nhất kể từ hôm nay.
  if (!out.date) {
    for (const [word, dow] of WEEKDAY_WORDS) {
      const re = new RegExp(`(?<=\\s)(?:${word.source})(?=\\s)`, 'iu');
      if (re.test(s)) {
        cut(re, () => {
          out.date = addDays(today, (dow - now.getDay() + 7) % 7);
        });
        break;
      }
    }
  }

  if (out.start && !out.date) out.date = today;
  out.title = s.replace(/\s+/g, ' ').trim() || String(text).trim();
  return out;
}
