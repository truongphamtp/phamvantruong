// Đồng bộ hai chiều giữa sổ ghi chép (localStorage) và Google Calendar.
//
// - Mỗi công việc có ngày (và bật "Đồng bộ") tương ứng với một sự kiện Google.
// - Trạng thái, ưu tiên, nhãn được lưu trong extendedProperties.private của sự kiện,
//   nên mở ứng dụng ở máy khác cũng lấy lại được đầy đủ dữ liệu.
// - Thứ tự: kéo thay đổi từ Google về trước, rồi đẩy thay đổi trên máy lên.
// - Khi cả hai bên cùng sửa, bản sửa sau cùng được giữ.

import { store, normalizeTask } from './store.js';
import * as gcal from './gcal.js';
import { addDays, toDateStr, toTimeStr, toRfc3339 } from './utils.js';

const DONE_MARK = '✓';
// Mã màu sự kiện Google: 8 = xám (xong), 11 = đỏ, 7 = xanh lam, 2 = xanh lá.
const COLORS = { done: '8', high: '11', normal: '7', low: '2' };

export const shouldSync = (t) => !!(t.sync && t.date);

export function needsPush(t, calendarId) {
  if (!shouldSync(t)) return !!t.gcal;
  return t.dirty || !t.gcal || t.gcal.calendarId !== calendarId;
}

export const pendingCount = (calendarId) =>
  store.tasks.filter((t) => needsPush(t, calendarId)).length + store.tombstones.length;

// ------------------------------------------------------------ Chuyển đổi dữ liệu

export function taskToEvent(t) {
  let start;
  let end;
  if (!t.start) {
    start = { date: t.date };
    end = { date: addDays(t.date, 1) };
  } else {
    const s = new Date(`${t.date}T${t.start}:00`);
    let e = t.end ? new Date(`${t.date}T${t.end}:00`) : new Date(s.getTime() + 60 * 60_000);
    if (e <= s) e = new Date(e.getTime() + 24 * 60 * 60_000); // kết thúc sau nửa đêm
    // Giờ kèm độ lệch múi giờ của máy (vd +07:00) nên không cần trường timeZone.
    start = { dateTime: toRfc3339(s) };
    end = { dateTime: toRfc3339(e) };
  }
  return {
    summary: (t.status === 'done' ? `${DONE_MARK} ` : '') + t.title,
    description: t.content,
    start,
    end,
    colorId: t.status === 'done' ? COLORS.done : COLORS[t.priority],
    reminders:
      t.reminder === null ? { useDefault: true } : { useDefault: false, overrides: [{ method: 'popup', minutes: t.reminder }] },
    extendedProperties: {
      private: {
        sotay: '1',
        localId: t.id,
        status: t.status,
        priority: t.priority,
        tags: t.tags.join(',').slice(0, 1000),
      },
    },
  };
}

// Mô tả sửa trên giao diện web của Google Calendar có thể ở dạng HTML.
function htmlToText(s) {
  if (!/<[a-z][\s\S]*>/i.test(s)) return s;
  const html = s.replace(/<br\s*\/?>/gi, '\n').replace(/<\/(p|div|li|h\d)>/gi, '\n');
  const text = new DOMParser().parseFromString(html, 'text/html').body.textContent || '';
  return text.replace(/\n{3,}/g, '\n\n').trim();
}

export function eventToFields(ev) {
  const p = ev.extendedProperties?.private || {};
  let title = (ev.summary || '').trim();
  if (title.startsWith(DONE_MARK)) title = title.slice(DONE_MARK.length).trim();
  const fields = {
    title: title || '(Không có tiêu đề)',
    content: htmlToText(ev.description || ''),
    status: p.status || 'todo',
    priority: p.priority || 'normal',
    tags: p.tags ? p.tags.split(',').filter(Boolean) : [],
    date: '',
    start: '',
    end: '',
    reminder: null,
  };
  if (ev.start?.date) {
    fields.date = ev.start.date;
  } else if (ev.start?.dateTime) {
    const s = new Date(ev.start.dateTime);
    const e = new Date(ev.end?.dateTime || ev.start.dateTime);
    fields.date = toDateStr(s);
    fields.start = toTimeStr(s);
    fields.end = e > s ? toTimeStr(e) : '';
  }
  const r = ev.reminders;
  if (r && !r.useDefault && r.overrides?.length) fields.reminder = r.overrides[0].minutes;
  return fields;
}

// ------------------------------------------------------------ Đồng bộ

async function pull(calendarId, stats) {
  const remote = await gcal.listAppEvents(calendarId);
  store.applySync((s) => {
    const byEvent = new Map(s.tasks.filter((t) => t.gcal?.eventId).map((t) => [t.gcal.eventId, t]));
    const tombstoned = new Set(s.tombstones.map((x) => x.eventId));
    const seen = new Set();

    for (const ev of remote) {
      if (ev.status === 'cancelled') continue; // xử lý như "không còn trên Google" ở dưới
      seen.add(ev.id);
      if (tombstoned.has(ev.id)) continue; // đã xoá trên máy, sẽ xoá trên Google khi đẩy lên
      const remoteUpdated = Date.parse(ev.updated) || Date.now();
      const localId = ev.extendedProperties?.private?.localId;
      let task = byEvent.get(ev.id);
      if (!task && localId) {
        const candidate = s.get(localId);
        if (candidate && !candidate.gcal) task = candidate; // liên kết lại (vd. khôi phục từ file sao lưu)
      }

      if (!task) {
        // Sự kiện tạo từ máy khác: thêm vào sổ.
        s.tasks.push(
          normalizeTask({
            ...eventToFields(ev),
            id: localId && !s.get(localId) ? localId : undefined,
            sync: true,
            createdAt: Date.parse(ev.created) || remoteUpdated,
            updatedAt: remoteUpdated,
            dirty: false,
            gcal: { eventId: ev.id, calendarId, updated: remoteUpdated },
          }),
        );
        stats.pulled++;
        continue;
      }

      task.gcal = { eventId: ev.id, calendarId, updated: task.gcal?.updated || 0 };
      if (remoteUpdated <= task.gcal.updated) continue; // Google không có gì mới
      if (task.dirty && task.updatedAt > remoteUpdated) continue; // bản trên máy mới hơn
      Object.assign(task, normalizeTask({ ...task, ...eventToFields(ev) }), {
        sync: true,
        updatedAt: remoteUpdated,
        dirty: false,
      });
      task.gcal.updated = remoteUpdated;
      stats.pulled++;
    }

    // Sự kiện đã bị xoá trên Google Calendar.
    for (const task of [...s.tasks]) {
      const link = task.gcal;
      if (!link || link.calendarId !== calendarId || seen.has(link.eventId)) continue;
      if (task.dirty && shouldSync(task)) {
        task.gcal = null; // trên máy vừa sửa: tạo lại sự kiện mới
      } else if (task.dirty) {
        task.gcal = null;
        task.dirty = false;
      } else {
        s.tasks = s.tasks.filter((t) => t !== task);
        stats.removed++;
      }
    }
  });
}

async function pushTask(id, calendarId, stats) {
  const task = store.get(id);
  if (!task) return;
  const version = task.updatedAt;

  if (!shouldSync(task)) {
    // Tắt đồng bộ hoặc bỏ ngày: gỡ sự kiện khỏi Google Calendar.
    await gcal.deleteEvent(task.gcal.calendarId, task.gcal.eventId);
    store.applySync((s) => {
      const t = s.get(id);
      if (t) t.gcal = null;
    });
    stats.pushed++;
    return;
  }

  const body = taskToEvent(task);
  let link = task.gcal;
  let saved = null;

  if (link && link.calendarId !== calendarId) {
    const moved = await gcal.moveEvent(link.calendarId, link.eventId, calendarId);
    link = moved ? { ...link, calendarId } : null;
  }
  if (link) {
    // Đọc bản hiện tại rồi ghi đè các trường của sổ, giữ nguyên phần khác
    // (địa điểm, khách mời...) mà người dùng có thể đã thêm trên Google Calendar.
    const current = await gcal.getEvent(calendarId, link.eventId);
    if (current && current.status !== 'cancelled') {
      const extendedProperties = {
        ...current.extendedProperties,
        private: { ...current.extendedProperties?.private, ...body.extendedProperties.private },
      };
      saved = await gcal.updateEvent(calendarId, link.eventId, { ...current, ...body, extendedProperties });
    }
  }
  if (!saved) saved = await gcal.insertEvent(calendarId, body);

  const updated = Date.parse(saved.updated) || Date.now();
  store.applySync((s) => {
    const t = s.get(id);
    if (!t) {
      // Bị xoá trong lúc đang đồng bộ.
      s.tombstones.push({ eventId: saved.id, calendarId });
      return;
    }
    t.gcal = { eventId: saved.id, calendarId, updated };
    t.dirty = t.updatedAt !== version; // còn sửa tiếp trong lúc gửi thì đồng bộ lại lần sau
  });
  stats.pushed++;
}

async function push(calendarId, stats) {
  for (const tomb of [...store.tombstones]) {
    await gcal.deleteEvent(tomb.calendarId, tomb.eventId);
    store.applySync((s) => {
      s.tombstones = s.tombstones.filter((x) => x.eventId !== tomb.eventId);
    });
    stats.removedRemote++;
  }
  const ids = store.tasks.filter((t) => needsPush(t, calendarId)).map((t) => t.id);
  for (const id of ids) await pushTask(id, calendarId, stats);
}

let running = null;
let again = false;

// Chạy đồng bộ; nếu đang chạy thì xếp thêm một lượt nữa sau khi xong.
export function runSync(calendarId) {
  if (!calendarId) return Promise.reject(new Error('Chưa chọn lịch Google để lưu công việc.'));
  if (running) {
    again = true;
    return running;
  }
  running = (async () => {
    const stats = { pulled: 0, pushed: 0, removed: 0, removedRemote: 0 };
    try {
      do {
        again = false;
        await pull(calendarId, stats);
        await push(calendarId, stats);
      } while (again);
      return stats;
    } finally {
      running = null;
    }
  })();
  return running;
}
