// Kho dữ liệu cục bộ (localStorage). Mọi công việc luôn được lưu trên máy trước,
// sau đó mới đồng bộ lên Google Calendar, nên ứng dụng vẫn dùng được khi mất mạng.

import { uid } from './utils.js';

const KEY = 'sotay.data.v1';

const STATUSES = ['todo', 'doing', 'done'];
const PRIORITIES = ['low', 'normal', 'high'];
// Các trường người dùng chỉnh sửa (dùng để phát hiện thay đổi thật sự).
const EDITABLE = ['title', 'content', 'status', 'priority', 'tags', 'date', 'start', 'end', 'reminder', 'sync'];

export function normalizeTask(raw) {
  const now = Date.now();
  const t = {
    title: '',
    content: '',
    status: 'todo',
    priority: 'normal',
    tags: [],
    date: '',
    start: '',
    end: '',
    reminder: null,
    sync: true,
    createdAt: now,
    updatedAt: now,
    dirty: true,
    gcal: null, // { eventId, calendarId, updated } khi đã liên kết với một sự kiện Google
    ...raw,
  };
  t.id = String(t.id || uid());
  t.title = String(t.title ?? '');
  t.content = String(t.content ?? '');
  t.tags = Array.isArray(t.tags) ? t.tags.map(String).filter(Boolean) : [];
  if (!STATUSES.includes(t.status)) t.status = 'todo';
  if (!PRIORITIES.includes(t.priority)) t.priority = 'normal';
  if (!/^\d{4}-\d{2}-\d{2}$/.test(t.date)) t.date = '';
  if (!/^\d{2}:\d{2}$/.test(t.start)) t.start = '';
  if (!t.start || !/^\d{2}:\d{2}$/.test(t.end)) t.end = '';
  t.reminder = t.reminder === null || t.reminder === '' || !Number.isFinite(Number(t.reminder)) ? null : Number(t.reminder);
  t.sync = t.sync !== false;
  return t;
}

const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

class Store {
  tasks = [];
  // Sự kiện Google cần xoá ở lần đồng bộ tới (do công việc đã bị xoá trên máy).
  tombstones = [];
  #listeners = new Set();

  load() {
    try {
      const raw = JSON.parse(localStorage.getItem(KEY) || 'null');
      if (raw) {
        this.tasks = (raw.tasks || []).map(normalizeTask);
        this.tombstones = Array.isArray(raw.tombstones) ? raw.tombstones : [];
      }
    } catch (err) {
      console.error('Không đọc được dữ liệu đã lưu', err);
    }
  }

  onChange(fn) {
    this.#listeners.add(fn);
    return () => this.#listeners.delete(fn);
  }

  // source: 'local' (người dùng sửa) hoặc 'sync' (thay đổi do đồng bộ)
  #emit(source) {
    try {
      localStorage.setItem(KEY, JSON.stringify({ version: 1, tasks: this.tasks, tombstones: this.tombstones }));
    } catch (err) {
      console.error('Không lưu được dữ liệu', err);
    }
    for (const fn of this.#listeners) fn(source);
  }

  get(id) {
    return this.tasks.find((t) => t.id === id) || null;
  }

  create(fields) {
    const now = Date.now();
    const task = normalizeTask({ ...fields, id: uid(), createdAt: now, updatedAt: now, dirty: true, gcal: null });
    this.tasks.push(task);
    this.#emit('local');
    return task;
  }

  update(id, patch) {
    const task = this.get(id);
    if (!task) return null;
    const next = normalizeTask({ ...task, ...patch });
    if (EDITABLE.every((k) => same(task[k], next[k]))) return task;
    Object.assign(task, next, { updatedAt: Math.max(Date.now(), task.updatedAt + 1), dirty: true });
    this.#emit('local');
    return task;
  }

  remove(id) {
    const task = this.get(id);
    if (!task) return;
    this.tasks = this.tasks.filter((t) => t.id !== id);
    if (task.gcal?.eventId) this.tombstones.push({ eventId: task.gcal.eventId, calendarId: task.gcal.calendarId });
    this.#emit('local');
  }

  // Dùng cho bộ đồng bộ: thay đổi không bị coi là chỉnh sửa của người dùng.
  applySync(mutator) {
    mutator(this);
    this.#emit('sync');
  }

  // Gộp dữ liệu từ file sao lưu: bản nào cập nhật sau thì giữ.
  importTasks(list) {
    let added = 0;
    let updated = 0;
    for (const raw of list) {
      if (!raw || typeof raw !== 'object' || !raw.id) continue;
      const incoming = normalizeTask({ ...raw, gcal: null, dirty: true });
      const current = this.get(incoming.id);
      if (!current) {
        this.tasks.push(incoming);
        added++;
      } else if (incoming.updatedAt > current.updatedAt) {
        Object.assign(current, incoming, { gcal: current.gcal });
        updated++;
      }
    }
    if (added || updated) this.#emit('local');
    return { added, updated };
  }

  exportData() {
    return {
      app: 'so-ghi-chep',
      version: 1,
      exportedAt: new Date().toISOString(),
      tasks: this.tasks.map(({ dirty, gcal, ...t }) => t),
    };
  }
}

export const store = new Store();
