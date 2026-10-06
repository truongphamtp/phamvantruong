// Giao diện chính của Sổ ghi chép.

import { store } from './store.js';
import * as gcal from './gcal.js';
import { runSync, shouldSync, needsPush, pendingCount, eventToFields } from './sync.js';
import { GOOGLE_CLIENT_ID } from '../config.js';
import {
  addDays,
  dateLabel,
  debounce,
  escapeHtml,
  normalize,
  parseQuickAdd,
  parseTags,
  shortDate,
  toDateStr,
  toTimeStr,
  todayStr,
} from './utils.js';

const README_URL = 'https://github.com/truongphamtp/phamvantruong/blob/main/so-ghi-chep/README.md';
const SETTINGS_KEY = 'sotay.settings.v1';
const AUTO_SYNC_MS = 5 * 60_000;

// ---------------------------------------------------------------- Trạng thái

function readJson(key) {
  try {
    return JSON.parse(localStorage.getItem(key) || '{}') || {};
  } catch {
    return {};
  }
}

const settings = {
  clientId: '',
  calendarId: '',
  account: '',
  autoSync: true,
  defaultSync: true,
  hideDone: false,
  sort: 'date',
  wasConnected: false,
  ...readJson(SETTINGS_KEY),
};
settings.clientId ||= GOOGLE_CLIENT_ID;

function saveSettings() {
  try {
    localStorage.setItem(SETTINGS_KEY, JSON.stringify(settings));
  } catch {
    /* bỏ qua */
  }
}

const state = {
  view: 'today',
  tag: '',
  query: '',
  syncing: false,
  syncError: '',
  lastSync: 0,
  calendars: [],
  events: null,
  eventsLoading: false,
  eventsError: '',
  day: todayStr(),
};

const VIEW_TITLES = {
  today: 'Hôm nay',
  upcoming: '7 ngày tới',
  all: 'Tất cả công việc',
  nodate: 'Ghi chú không hẹn ngày',
  done: 'Đã hoàn thành',
  gcal: 'Lịch Google – 14 ngày tới',
};
const PRIO_ORDER = { high: 0, normal: 1, low: 2 };

// ---------------------------------------------------------------- DOM

const $ = (sel) => document.querySelector(sel);
const el = {
  menuBtn: $('#menuBtn'),
  sidebar: $('#sidebar'),
  backdrop: $('#backdrop'),
  tagList: $('#tagList'),
  syncStatus: $('#syncStatus'),
  syncBtn: $('#syncBtn'),
  connectBtn: $('#connectBtn'),
  settingsBtn: $('#settingsBtn'),
  viewTitle: $('#viewTitle'),
  search: $('#search'),
  sort: $('#sort'),
  hideDone: $('#hideDone'),
  hideDoneLabel: $('#hideDoneLabel'),
  quickAdd: $('#quickAdd'),
  quickInput: $('#quickInput'),
  newBtn: $('#newBtn'),
  list: $('#list'),
  editor: $('#editor'),
  editorForm: $('#editorForm'),
  editorHeading: $('#editorHeading'),
  editorMeta: $('#editorMeta'),
  syncHint: $('#syncHint'),
  deleteBtn: $('#deleteBtn'),
  settings: $('#settings'),
  settingsForm: $('#settingsForm'),
  originHint: $('#originHint'),
  readmeLink: $('#readmeLink'),
  accountInfo: $('#accountInfo'),
  createCalBtn: $('#createCalBtn'),
  disconnectBtn: $('#disconnectBtn'),
  exportBtn: $('#exportBtn'),
  importFile: $('#importFile'),
  toast: $('#toast'),
};

let toastTimer;
function toast(message, { error = false } = {}) {
  el.toast.textContent = message;
  el.toast.classList.toggle('error', error);
  el.toast.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (el.toast.hidden = true), error ? 6000 : 3500);
}

// ---------------------------------------------------------------- Lọc & sắp xếp

function viewTasks(view) {
  const today = todayStr();
  const tasks = store.tasks;
  switch (view) {
    case 'today':
      return tasks.filter((t) => t.date && (t.date === today || (t.date < today && t.status !== 'done')));
    case 'upcoming':
      return tasks.filter((t) => t.date > today && t.date <= addDays(today, 7));
    case 'all':
      return tasks.slice();
    case 'nodate':
      return tasks.filter((t) => !t.date);
    case 'done':
      return tasks.filter((t) => t.status === 'done');
    default:
      return [];
  }
}

function comparator(sort) {
  const today = todayStr();
  const rank = (t) => (t.date && t.date < today && t.status !== 'done' ? 0 : t.date ? 1 : 2);
  const isDone = (t) => (t.status === 'done' ? 1 : 0);
  const byDate = (a, b) =>
    rank(a) - rank(b) ||
    a.date.localeCompare(b.date) ||
    isDone(a) - isDone(b) ||
    a.start.localeCompare(b.start) ||
    PRIO_ORDER[a.priority] - PRIO_ORDER[b.priority] ||
    a.createdAt - b.createdAt;
  if (sort === 'priority') return (a, b) => isDone(a) - isDone(b) || PRIO_ORDER[a.priority] - PRIO_ORDER[b.priority] || byDate(a, b);
  if (sort === 'updated') return (a, b) => b.updatedAt - a.updatedAt;
  return byDate;
}

function currentTasks() {
  let list;
  if (state.query) {
    const q = normalize(state.query);
    list = store.tasks.filter((t) => normalize(`${t.title}\n${t.content}\n${t.tags.join(' ')}`).includes(q));
  } else if (state.tag) {
    list = store.tasks.filter((t) => t.tags.includes(state.tag));
  } else {
    list = viewTasks(state.view);
  }
  const showingDoneView = state.view === 'done' && !state.query && !state.tag;
  if (settings.hideDone && !showingDoneView) list = list.filter((t) => t.status !== 'done');
  return list.sort(comparator(settings.sort));
}

function groupLabel(t) {
  if (!t.date) return 'Không hẹn ngày';
  if (t.date < todayStr() && t.status !== 'done') return 'Quá hạn';
  return dateLabel(t.date);
}

function defaultDateForView() {
  if (state.view === 'today') return todayStr();
  if (state.view === 'upcoming') return addDays(todayStr(), 1);
  return '';
}

// ---------------------------------------------------------------- Hiển thị

function render() {
  state.day = todayStr();
  renderHeader();
  renderSidebar();
  renderMain();
}

function renderHeader() {
  const connected = gcal.hasValidToken();
  el.connectBtn.hidden = connected;
  el.connectBtn.textContent = settings.wasConnected ? 'Kết nối lại Google' : 'Kết nối Google Calendar';
  el.syncBtn.hidden = !connected;
  el.syncBtn.disabled = state.syncing;

  const pending = pendingCount(settings.calendarId);
  let text;
  if (state.syncing) text = 'Đang đồng bộ…';
  else if (!connected) text = settings.wasConnected ? 'Phiên Google đã hết hạn' : 'Đang lưu trên máy này';
  else if (state.syncError) text = '⚠ Lỗi đồng bộ';
  else if (pending) text = `${pending} thay đổi chờ đồng bộ`;
  else if (state.lastSync) text = `Đã đồng bộ lúc ${toTimeStr(new Date(state.lastSync))}`;
  else text = 'Đã kết nối';
  el.syncStatus.textContent = text;
  el.syncStatus.title = state.syncError || (settings.account ? `Tài khoản: ${settings.account}` : '');
}

function renderSidebar() {
  for (const btn of el.sidebar.querySelectorAll('[data-view]')) {
    btn.classList.toggle('active', !state.tag && !state.query && btn.dataset.view === state.view);
  }
  for (const span of el.sidebar.querySelectorAll('[data-count]')) {
    const view = span.dataset.count;
    const tasks = viewTasks(view);
    const n = view === 'done' ? tasks.length : tasks.filter((t) => t.status !== 'done').length;
    span.textContent = n || '';
  }
  const counts = new Map();
  for (const t of store.tasks) {
    for (const tag of t.tags) counts.set(tag, (counts.get(tag) || 0) + (t.status === 'done' ? 0 : 1));
  }
  const tags = [...counts.keys()].sort((a, b) => a.localeCompare(b, 'vi'));
  el.tagList.innerHTML = tags.length
    ? tags
        .map(
          (tag) =>
            `<li><button type="button" data-tag="${escapeHtml(tag)}" class="${tag === state.tag ? 'active' : ''}">#${escapeHtml(tag)}<span class="count">${counts.get(tag) || ''}</span></button></li>`,
        )
        .join('')
    : '<li class="empty-tags">Gõ #nhãn khi thêm việc để phân loại.</li>';
}

function renderMain() {
  el.viewTitle.textContent = state.query
    ? `Kết quả tìm “${state.query}”`
    : state.tag
      ? `Nhãn #${state.tag}`
      : VIEW_TITLES[state.view];
  const showEvents = state.view === 'gcal' && !state.query && !state.tag;
  el.sort.hidden = showEvents;
  el.hideDoneLabel.hidden = showEvents;
  if (showEvents) {
    renderEvents();
    return;
  }
  const tasks = currentTasks();
  if (!tasks.length) {
    el.list.innerHTML = `<div class="empty">${emptyMessage()}</div>`;
    return;
  }
  let html = '';
  let lastGroup = null;
  for (const t of tasks) {
    if (settings.sort === 'date') {
      const g = groupLabel(t);
      if (g !== lastGroup) {
        html += `<h3 class="group${g === 'Quá hạn' ? ' overdue' : ''}">${escapeHtml(g)}</h3>`;
        lastGroup = g;
      }
    }
    html += taskHtml(t);
  }
  el.list.innerHTML = html;
}

function emptyMessage() {
  if (state.query) return 'Không tìm thấy công việc nào phù hợp.';
  if (state.view === 'today') return 'Hôm nay chưa có việc nào. Thêm việc ở ô phía trên nhé! ✍️';
  if (state.view === 'done') return 'Chưa có việc nào hoàn thành.';
  return 'Chưa có công việc nào ở đây.';
}

function syncIcon(t) {
  if (shouldSync(t)) {
    return t.gcal && !needsPush(t, settings.calendarId)
      ? '<span class="sync-icon" title="Đã lưu trên Google Calendar">☁️</span>'
      : '<span class="sync-icon" title="Chờ đồng bộ lên Google Calendar">⏳</span>';
  }
  const why = t.sync ? 'chưa có ngày nên chỉ lưu trên máy này' : 'chỉ lưu trên máy này';
  return `<span class="sync-icon" title="Ghi chú ${why}">💾</span>`;
}

function taskHtml(t) {
  const overdue = t.date && t.date < todayStr() && t.status !== 'done';
  const time = t.start ? (t.end ? `${t.start}–${t.end}` : t.start) : '';
  const chips = [];
  if (t.date) {
    chips.push(`<span class="chip${overdue ? ' overdue' : ''}">📅 ${shortDate(t.date)}${time ? ` · ${time}` : ''}</span>`);
  }
  if (t.status === 'doing') chips.push('<span class="chip doing">▶ Đang làm</span>');
  if (t.priority === 'high') chips.push('<span class="chip high">! Ưu tiên cao</span>');
  if (t.reminder !== null) chips.push('<span class="chip" title="Có nhắc nhở">🔔</span>');
  for (const tag of t.tags) chips.push(`<button type="button" class="tag" data-tag="${escapeHtml(tag)}">#${escapeHtml(tag)}</button>`);
  chips.push(syncIcon(t));
  return `<article class="task prio-${t.priority}${t.status === 'done' ? ' is-done' : ''}" data-id="${escapeHtml(t.id)}" tabindex="0">
    <input type="checkbox" class="toggle" ${t.status === 'done' ? 'checked' : ''} aria-label="Đánh dấu hoàn thành: ${escapeHtml(t.title)}" />
    <div class="task-body">
      <div class="task-title">${escapeHtml(t.title)}</div>
      ${t.content ? `<div class="task-snippet">${escapeHtml(t.content)}</div>` : ''}
      <div class="task-meta">${chips.join('')}</div>
    </div>
  </article>`;
}

// ---------------------------------------------------------------- Lịch Google

function eventDate(ev) {
  return ev.start?.date || toDateStr(new Date(ev.start?.dateTime));
}

function eventTime(ev) {
  if (ev.start?.date) return 'Cả ngày';
  const s = toTimeStr(new Date(ev.start.dateTime));
  const e = ev.end?.dateTime ? toTimeStr(new Date(ev.end.dateTime)) : '';
  return e ? `${s}–${e}` : s;
}

async function loadEvents() {
  if (!gcal.hasValidToken()) return;
  state.eventsLoading = true;
  state.eventsError = '';
  renderMain();
  try {
    if (!state.calendars.length) state.calendars = await gcal.listCalendars();
    const from = new Date();
    from.setHours(0, 0, 0, 0);
    const to = new Date(from);
    to.setDate(to.getDate() + 14);
    const cals = state.calendars
      .filter((c) => c.selected || c.primary || c.id === settings.calendarId)
      .filter((c) => c.accessRole !== 'freeBusyReader')
      .slice(0, 15);
    const results = await Promise.allSettled(
      cals.map((c) =>
        gcal.listEvents(c.id, from.toISOString(), to.toISOString()).then((items) => items.map((ev) => ({ ev, cal: c }))),
      ),
    );
    const auth = results.find((r) => r.status === 'rejected' && r.reason instanceof gcal.AuthError);
    if (auth) throw auth.reason;
    const seen = new Set();
    state.events = results
      .flatMap((r) => (r.status === 'fulfilled' ? r.value : []))
      .filter(({ ev }) => ev.status !== 'cancelled' && !seen.has(ev.id) && seen.add(ev.id))
      .sort((a, b) => sortKey(a.ev).localeCompare(sortKey(b.ev)));
  } catch (err) {
    state.eventsError = err.message;
    handleError(err, { quiet: true });
  } finally {
    state.eventsLoading = false;
    renderMain();
  }
}

// Sự kiện cả ngày đứng trước, sau đó theo giờ bắt đầu.
const sortKey = (ev) => (ev.start?.date ? `${ev.start.date} 0` : `${eventDate(ev)} 1 ${toTimeStr(new Date(ev.start.dateTime))}`);

function safeColor(c) {
  return /^#[0-9a-f]{3,8}$/i.test(c || '') ? c : '';
}

function renderEvents() {
  if (!gcal.hasValidToken()) {
    el.list.innerHTML = `<div class="empty"><p>Kết nối Google Calendar để xem lịch trình của bạn ngay trong sổ.</p>
      <button type="button" class="btn primary" data-action="connect">Kết nối Google Calendar</button></div>`;
    return;
  }
  if (state.eventsError && !state.events) {
    el.list.innerHTML = `<div class="empty"><p>Không tải được lịch: ${escapeHtml(state.eventsError)}</p>
      <button type="button" class="btn" data-action="reload-events">Thử lại</button></div>`;
    return;
  }
  if (!state.events) {
    el.list.innerHTML = '<div class="empty">Đang tải lịch…</div>';
    if (!state.eventsLoading) loadEvents();
    return;
  }
  if (!state.events.length) {
    el.list.innerHTML = '<div class="empty">Không có sự kiện nào trong 14 ngày tới.</div>';
    return;
  }
  const byEvent = new Map(store.tasks.filter((t) => t.gcal).map((t) => [t.gcal.eventId, t]));
  let html = '';
  let lastDate = '';
  state.events.forEach(({ ev, cal }, i) => {
    const d = eventDate(ev);
    if (d !== lastDate) {
      html += `<h3 class="group">${escapeHtml(dateLabel(d))}</h3>`;
      lastDate = d;
    }
    const task = byEvent.get(ev.id);
    const color = safeColor(cal.backgroundColor);
    const action = task
      ? `<button type="button" class="btn" data-open="${escapeHtml(task.id)}">📒 Mở trong sổ</button>`
      : `<button type="button" class="btn" data-import="${i}">＋ Thêm vào sổ</button>`;
    html += `<article class="event"${color ? ` style="--cal:${color}"` : ''}>
      <div class="ev-time">${escapeHtml(eventTime(ev))}</div>
      <div class="ev-body">
        <div class="ev-title">${escapeHtml(ev.summary || '(Không có tiêu đề)')}</div>
        <div class="ev-cal">${escapeHtml(cal.summaryOverride || cal.summary || '')}${ev.location ? ` · ${escapeHtml(ev.location)}` : ''}</div>
      </div>
      ${action}
    </article>`;
  });
  el.list.innerHTML = html;
}

// ---------------------------------------------------------------- Soạn công việc

let editingId = null;

function openEditor(task, prefill = {}) {
  editingId = task?.id || null;
  const t = task || {
    title: '',
    content: '',
    status: 'todo',
    priority: 'normal',
    tags: state.tag ? [state.tag] : [],
    date: defaultDateForView(),
    start: '',
    end: '',
    reminder: null,
    sync: settings.defaultSync,
    ...prefill,
  };
  const f = el.editorForm.elements;
  f.title.value = t.title;
  f.content.value = t.content;
  f.date.value = t.date;
  f.start.value = t.start;
  f.end.value = t.end;
  f.status.value = t.status;
  f.priority.value = t.priority;
  const reminder = t.reminder === null ? '' : String(t.reminder);
  if (![...f.reminder.options].some((o) => o.value === reminder)) {
    f.reminder.add(new Option(`${reminder} phút`, reminder));
  }
  f.reminder.value = reminder;
  f.tags.value = t.tags.join(', ');
  f.sync.checked = t.sync;
  el.editorHeading.textContent = task ? 'Sửa công việc' : 'Công việc mới';
  el.deleteBtn.hidden = !task;
  el.editorMeta.textContent = task ? editorMeta(task) : '';
  updateSyncHint();
  el.editor.showModal();
  if (!task) f.title.focus();
}

function editorMeta(t) {
  const fmt = (ms) => new Date(ms).toLocaleString('vi-VN', { dateStyle: 'short', timeStyle: 'short' });
  return `Tạo lúc ${fmt(t.createdAt)} · Sửa lần cuối ${fmt(t.updatedAt)}`;
}

function updateSyncHint() {
  const f = el.editorForm.elements;
  let hint = '';
  if (f.sync.checked && !f.date.value && !f.start.value) {
    hint = 'Ghi chú chưa có ngày sẽ chỉ lưu trên máy này cho đến khi bạn chọn ngày.';
  } else if (f.sync.checked && !gcal.hasValidToken()) {
    hint = 'Sẽ được đưa lên Google Calendar khi bạn kết nối tài khoản Google.';
  } else if (!f.sync.checked) {
    hint = 'Công việc chỉ lưu trên máy này.';
  }
  el.syncHint.textContent = hint;
}

function readEditor() {
  const f = el.editorForm.elements;
  let date = f.date.value;
  const start = f.start.value;
  if (start && !date) date = todayStr();
  return {
    title: f.title.value.trim(),
    content: f.content.value.replace(/\s+$/, ''),
    date,
    start,
    end: start ? f.end.value : '',
    status: f.status.value,
    priority: f.priority.value,
    reminder: f.reminder.value === '' ? null : Number(f.reminder.value),
    tags: parseTags(f.tags.value),
    sync: f.sync.checked,
  };
}

// ---------------------------------------------------------------- Google

async function connectGoogle() {
  if (!settings.clientId) {
    openSettings();
    el.settingsForm.elements.clientId.focus();
    toast('Hãy nhập Google OAuth Client ID trước khi kết nối.');
    return;
  }
  try {
    await gcal.connect(settings.clientId);
    await afterConnect();
    toast('Đã kết nối Google Calendar.');
  } catch (err) {
    handleError(err);
  }
}

async function afterConnect() {
  settings.wasConnected = true;
  state.syncError = '';
  state.calendars = await gcal.listCalendars();
  const primary = state.calendars.find((c) => c.primary);
  settings.account = primary?.id || settings.account;
  if (!settings.calendarId || !state.calendars.some((c) => c.id === settings.calendarId)) {
    settings.calendarId = primary?.id || 'primary';
  }
  saveSettings();
  renderSettingsGoogle();
  render();
  await syncNow({ quiet: true });
}

async function syncNow({ quiet = false } = {}) {
  if (!gcal.hasValidToken()) {
    if (!quiet) connectGoogle();
    return;
  }
  state.syncing = true;
  renderHeader();
  try {
    const stats = await runSync(settings.calendarId);
    state.syncError = '';
    state.lastSync = Date.now();
    if (!quiet) {
      const parts = [];
      if (stats.pushed) parts.push(`gửi ${stats.pushed}`);
      if (stats.pulled) parts.push(`nhận ${stats.pulled}`);
      if (stats.removed + stats.removedRemote) parts.push(`xoá ${stats.removed + stats.removedRemote}`);
      toast(parts.length ? `Đồng bộ xong: ${parts.join(', ')} công việc.` : 'Mọi thứ đã được đồng bộ.');
    }
    if (state.view === 'gcal') loadEvents();
  } catch (err) {
    state.syncError = err.message;
    handleError(err, { quiet });
  } finally {
    state.syncing = false;
    render();
  }
}

let authToastShown = false;
function handleError(err, { quiet = false } = {}) {
  if (err instanceof gcal.AuthError) {
    console.warn(err.message);
    renderHeader();
    if (!quiet || !authToastShown) toast(err.message, { error: true });
    authToastShown = true;
    return;
  }
  console.error(err);
  if (!quiet) toast(err.message || 'Đã có lỗi xảy ra.', { error: true });
}

const scheduleSync = debounce(() => {
  if (settings.autoSync && gcal.hasValidToken()) syncNow({ quiet: true });
}, 1500);

// ---------------------------------------------------------------- Cài đặt

function openSettings() {
  const f = el.settingsForm.elements;
  f.clientId.value = settings.clientId;
  f.autoSync.checked = settings.autoSync;
  f.defaultSync.checked = settings.defaultSync;
  renderSettingsGoogle();
  if (!el.settings.open) el.settings.showModal();
}

function renderSettingsGoogle() {
  const f = el.settingsForm.elements;
  const connected = gcal.hasValidToken();
  el.accountInfo.innerHTML = connected
    ? `✅ Đã kết nối${settings.account ? ` với <b>${escapeHtml(settings.account)}</b>` : ''}`
    : settings.wasConnected
      ? '⚠ Phiên đăng nhập đã hết hạn. Bấm “Kết nối lại Google” ở thanh trên cùng.'
      : 'Chưa kết nối.';
  const writable = state.calendars.filter((c) => c.accessRole === 'owner' || c.accessRole === 'writer');
  const options = writable.length
    ? writable.map((c) => [c.id, (c.summaryOverride || c.summary) + (c.primary ? ' (lịch chính)' : '')])
    : [[settings.calendarId, settings.calendarId || '(Kết nối để chọn lịch)']];
  f.calendarId.innerHTML = options
    .map(([id, name]) => `<option value="${escapeHtml(id)}">${escapeHtml(name)}</option>`)
    .join('');
  f.calendarId.value = settings.calendarId;
  f.calendarId.disabled = !connected || !writable.length;
  el.createCalBtn.disabled = !connected;
  el.disconnectBtn.hidden = !connected && !settings.wasConnected;
}

// ---------------------------------------------------------------- Sự kiện giao diện

function setView(view) {
  state.view = view;
  state.tag = '';
  state.query = '';
  el.search.value = '';
  document.body.classList.remove('nav-open');
  el.backdrop.hidden = true;
  if (view === 'gcal' && gcal.hasValidToken()) state.events = null; // tải lại mỗi lần mở
  render();
}

function selectTag(tag) {
  state.tag = state.tag === tag ? '' : tag;
  state.query = '';
  el.search.value = '';
  document.body.classList.remove('nav-open');
  el.backdrop.hidden = true;
  render();
}

el.sidebar.addEventListener('click', (e) => {
  const viewBtn = e.target.closest('[data-view]');
  if (viewBtn) return setView(viewBtn.dataset.view);
  const tagBtn = e.target.closest('[data-tag]');
  if (tagBtn) selectTag(tagBtn.dataset.tag);
});

el.menuBtn.addEventListener('click', () => {
  const open = document.body.classList.toggle('nav-open');
  el.backdrop.hidden = !open;
});
el.backdrop.addEventListener('click', () => {
  document.body.classList.remove('nav-open');
  el.backdrop.hidden = true;
});

el.list.addEventListener('click', (e) => {
  const action = e.target.closest('[data-action]')?.dataset.action;
  if (action === 'connect') return connectGoogle();
  if (action === 'reload-events') {
    state.events = null;
    return loadEvents();
  }
  const tagBtn = e.target.closest('[data-tag]');
  if (tagBtn) return selectTag(tagBtn.dataset.tag);
  const openBtn = e.target.closest('[data-open]');
  if (openBtn) return openEditor(store.get(openBtn.dataset.open));
  const importBtn = e.target.closest('[data-import]');
  if (importBtn) {
    const item = state.events?.[Number(importBtn.dataset.import)];
    if (item) {
      const fields = eventToFields(item.ev);
      openEditor(null, { ...fields, status: 'todo', priority: 'normal', tags: [], reminder: null, sync: false });
    }
    return;
  }
  const card = e.target.closest('.task');
  if (!card) return;
  if (e.target.classList.contains('toggle')) {
    store.update(card.dataset.id, { status: e.target.checked ? 'done' : 'todo' });
    return;
  }
  openEditor(store.get(card.dataset.id));
});

el.list.addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && e.target.classList.contains('task')) openEditor(store.get(e.target.dataset.id));
});

el.search.addEventListener(
  'input',
  debounce(() => {
    state.query = el.search.value.trim();
    render();
  }, 150),
);

el.sort.addEventListener('change', () => {
  settings.sort = el.sort.value;
  saveSettings();
  renderMain();
});

el.hideDone.addEventListener('change', () => {
  settings.hideDone = el.hideDone.checked;
  saveSettings();
  renderMain();
});

el.quickAdd.addEventListener('submit', (e) => {
  e.preventDefault();
  const text = el.quickInput.value.trim();
  if (!text) return;
  const parsed = parseQuickAdd(text);
  if (!parsed.date) parsed.date = defaultDateForView();
  if (state.tag && !parsed.tags.includes(state.tag)) parsed.tags.push(state.tag);
  const task = store.create({ ...parsed, sync: settings.defaultSync });
  el.quickInput.value = '';
  const when = task.date ? ` – ${dateLabel(task.date)}${task.start ? ` ${task.start}` : ''}` : '';
  toast(`Đã thêm “${task.title}”${when}`);
});

el.newBtn.addEventListener('click', () => {
  const text = el.quickInput.value.trim();
  if (!text) return openEditor(null);
  const parsed = parseQuickAdd(text);
  el.quickInput.value = '';
  openEditor(null, { ...parsed, date: parsed.date || defaultDateForView() });
});

el.editorForm.addEventListener('submit', (e) => {
  if (e.submitter?.value !== 'save') return;
  const data = readEditor();
  if (editingId && store.get(editingId)) {
    store.update(editingId, data);
  } else {
    const task = store.create(data);
    toast(`Đã thêm “${task.title}”`);
  }
});

for (const name of ['date', 'start', 'sync']) {
  el.editorForm.elements[name].addEventListener('input', updateSyncHint);
}

$('#cancelBtn').addEventListener('click', () => el.editor.close('cancel'));

el.deleteBtn.addEventListener('click', () => {
  const task = editingId && store.get(editingId);
  if (!task) return;
  const extra = task.gcal ? '\nSự kiện tương ứng trên Google Calendar cũng sẽ bị xoá.' : '';
  if (!confirm(`Xoá “${task.title}”?${extra}`)) return;
  store.remove(task.id);
  el.editor.close('deleted');
  toast('Đã xoá công việc.');
});

el.connectBtn.addEventListener('click', connectGoogle);
el.syncBtn.addEventListener('click', () => syncNow());
el.settingsBtn.addEventListener('click', openSettings);

const settingsFields = el.settingsForm.elements;
settingsFields.clientId.addEventListener('change', () => {
  settings.clientId = settingsFields.clientId.value.trim();
  saveSettings();
  if (settings.clientId) gcal.prepareAuth(settings.clientId).catch(() => {});
  toast('Đã lưu Client ID. Bấm “Kết nối Google Calendar” để đăng nhập.');
});
settingsFields.autoSync.addEventListener('change', () => {
  settings.autoSync = settingsFields.autoSync.checked;
  saveSettings();
});
settingsFields.defaultSync.addEventListener('change', () => {
  settings.defaultSync = settingsFields.defaultSync.checked;
  saveSettings();
});
settingsFields.calendarId.addEventListener('change', () => {
  settings.calendarId = settingsFields.calendarId.value;
  saveSettings();
  state.events = null;
  toast('Đã đổi lịch. Các công việc đã đồng bộ sẽ được chuyển sang lịch mới.');
  syncNow({ quiet: true });
});

el.createCalBtn.addEventListener('click', async () => {
  el.createCalBtn.disabled = true;
  try {
    const created = await gcal.createCalendar('Sổ ghi chép');
    state.calendars = await gcal.listCalendars();
    if (!state.calendars.some((c) => c.id === created.id)) {
      state.calendars.push({ id: created.id, summary: created.summary, accessRole: 'owner' });
    }
    settings.calendarId = created.id;
    saveSettings();
    renderSettingsGoogle();
    toast('Đã tạo lịch “Sổ ghi chép” và chuyển công việc sang lịch này.');
    syncNow({ quiet: true });
  } catch (err) {
    handleError(err);
  } finally {
    el.createCalBtn.disabled = !gcal.hasValidToken();
  }
});

el.disconnectBtn.addEventListener('click', () => {
  if (!confirm('Ngắt kết nối Google Calendar? Dữ liệu trên máy và trên Google vẫn được giữ nguyên.')) return;
  gcal.disconnect();
  settings.wasConnected = false;
  saveSettings();
  state.calendars = [];
  state.events = null;
  renderSettingsGoogle();
  render();
  toast('Đã ngắt kết nối Google Calendar.');
});

el.exportBtn.addEventListener('click', () => {
  const blob = new Blob([JSON.stringify(store.exportData(), null, 2)], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = `so-ghi-chep-${todayStr()}.json`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 1000);
});

el.importFile.addEventListener('change', async () => {
  const file = el.importFile.files[0];
  el.importFile.value = '';
  if (!file) return;
  try {
    const data = JSON.parse(await file.text());
    const list = Array.isArray(data) ? data : data?.tasks;
    if (!Array.isArray(list)) throw new Error('File không đúng định dạng sao lưu của Sổ ghi chép.');
    const { added, updated } = store.importTasks(list);
    toast(`Đã nhập ${added} công việc mới, cập nhật ${updated} công việc.`);
  } catch (err) {
    toast(err instanceof SyntaxError ? 'File không phải JSON hợp lệ.' : err.message, { error: true });
  }
});

// Phím tắt: N = thêm việc, / = tìm kiếm
document.addEventListener('keydown', (e) => {
  if (e.ctrlKey || e.metaKey || e.altKey || e.target.closest('input, textarea, select, [contenteditable]')) return;
  if (document.querySelector('dialog[open]')) return;
  if (e.key === 'n' || e.key === 'N') {
    e.preventDefault();
    openEditor(null);
  } else if (e.key === '/') {
    e.preventDefault();
    el.search.focus();
  }
});

// ---------------------------------------------------------------- Khởi động

store.load();
store.onChange((source) => {
  render();
  if (source === 'local') scheduleSync();
});

el.sort.value = settings.sort;
el.hideDone.checked = settings.hideDone;
el.originHint.textContent = location.origin;
el.readmeLink.href = README_URL;
render();

if (settings.clientId) gcal.prepareAuth(settings.clientId).catch(() => {});
if (gcal.hasValidToken()) afterConnect().catch((err) => handleError(err, { quiet: true }));

setInterval(() => {
  if (settings.autoSync && gcal.hasValidToken() && document.visibilityState === 'visible') syncNow({ quiet: true });
}, AUTO_SYNC_MS);

// Cập nhật đồng hồ / sang ngày mới / token hết hạn.
setInterval(() => {
  if (state.day !== todayStr()) render();
  else renderHeader();
}, 30_000);

document.addEventListener('visibilitychange', () => {
  if (document.visibilityState !== 'visible') return;
  render();
  if (settings.autoSync && gcal.hasValidToken() && Date.now() - state.lastSync > 60_000) syncNow({ quiet: true });
});
