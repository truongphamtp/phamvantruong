// Kết nối Google Calendar API trực tiếp từ trình duyệt.
// Đăng nhập bằng Google Identity Services (OAuth 2.0, mô hình access token),
// không cần máy chủ riêng. Access token có hiệu lực khoảng 1 giờ.

const API = 'https://www.googleapis.com/calendar/v3';
export const SCOPES = 'https://www.googleapis.com/auth/calendar';
const TOKEN_KEY = 'sotay.token';

export class AuthError extends Error {}

export class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

let token = null; // { value, exp }
try {
  token = JSON.parse(sessionStorage.getItem(TOKEN_KEY) || 'null');
} catch {
  token = null;
}

export const hasValidToken = () => !!token && token.exp > Date.now() + 30_000;

function setToken(value, expiresIn) {
  token = { value, exp: Date.now() + Number(expiresIn || 3600) * 1000 };
  try {
    sessionStorage.setItem(TOKEN_KEY, JSON.stringify(token));
  } catch {
    /* bỏ qua */
  }
}

function clearToken() {
  token = null;
  try {
    sessionStorage.removeItem(TOKEN_KEY);
  } catch {
    /* bỏ qua */
  }
}

// ---------------------------------------------------------------- Đăng nhập

let tokenClient = null;
let tokenClientId = '';
let pending = null;

function waitForGis(timeout = 10_000) {
  return new Promise((resolve, reject) => {
    const started = Date.now();
    (function check() {
      if (window.google?.accounts?.oauth2) return resolve(window.google.accounts.oauth2);
      if (Date.now() - started > timeout) {
        return reject(new Error('Không tải được thư viện đăng nhập của Google. Hãy kiểm tra kết nối mạng.'));
      }
      setTimeout(check, 100);
    })();
  });
}

// Khởi tạo sẵn để khi người dùng bấm "Kết nối", cửa sổ đăng nhập mở ngay (không bị chặn popup).
export async function prepareAuth(clientId) {
  const oauth2 = await waitForGis();
  if (tokenClient && tokenClientId === clientId) return;
  tokenClientId = clientId;
  tokenClient = oauth2.initTokenClient({
    client_id: clientId,
    scope: SCOPES,
    callback: (resp) => pending?.done(resp),
    error_callback: (err) => pending?.fail(err),
  });
}

export async function connect(clientId) {
  if (!clientId) throw new AuthError('Chưa có Google Client ID. Hãy nhập trong phần Cài đặt.');
  if (!tokenClient || tokenClientId !== clientId) await prepareAuth(clientId);
  return new Promise((resolve, reject) => {
    pending = {
      done(resp) {
        pending = null;
        if (resp.error) return reject(new AuthError(resp.error_description || resp.error));
        if (!window.google.accounts.oauth2.hasGrantedAllScopes(resp, SCOPES)) {
          return reject(new AuthError('Bạn cần đánh dấu cho phép ứng dụng truy cập Google Calendar.'));
        }
        setToken(resp.access_token, resp.expires_in);
        resolve();
      },
      fail(err) {
        pending = null;
        const messages = {
          popup_closed: 'Bạn đã đóng cửa sổ đăng nhập Google.',
          popup_failed_to_open: 'Trình duyệt đã chặn cửa sổ đăng nhập. Hãy cho phép popup cho trang này.',
        };
        reject(new AuthError(messages[err?.type] || err?.message || 'Đăng nhập Google thất bại.'));
      },
    };
    tokenClient.requestAccessToken({ prompt: '' });
  });
}

export function disconnect() {
  if (token && window.google?.accounts?.oauth2) {
    window.google.accounts.oauth2.revoke(token.value, () => {});
  }
  clearToken();
}

// ---------------------------------------------------------------- Gọi API

async function call(method, path, { query, body } = {}) {
  if (!hasValidToken()) throw new AuthError('Phiên đăng nhập Google đã hết hạn. Hãy kết nối lại.');
  const url = new URL(API + path);
  for (const [k, v] of Object.entries(query || {})) {
    if (v !== undefined && v !== null && v !== '') url.searchParams.set(k, String(v));
  }
  const headers = { Authorization: `Bearer ${token.value}` };
  if (body) headers['Content-Type'] = 'application/json';
  let res;
  try {
    res = await fetch(url, { method, headers, body: body ? JSON.stringify(body) : undefined });
  } catch {
    throw new ApiError(0, 'Không kết nối được tới Google Calendar. Hãy kiểm tra mạng.');
  }
  if (res.status === 401) {
    clearToken();
    throw new AuthError('Phiên đăng nhập Google đã hết hạn. Hãy kết nối lại.');
  }
  if (res.status === 204) return null;
  const data = await res.json().catch(() => null);
  if (!res.ok) {
    throw new ApiError(res.status, data?.error?.message || `Google Calendar trả về lỗi ${res.status}.`);
  }
  return data;
}

async function listAll(path, query) {
  const items = [];
  let pageToken;
  do {
    const data = await call('GET', path, { query: { ...query, pageToken } });
    items.push(...(data?.items || []));
    pageToken = data?.nextPageToken;
  } while (pageToken);
  return items;
}

const cal = (id) => `/calendars/${encodeURIComponent(id)}`;
const evt = (calendarId, eventId) => `${cal(calendarId)}/events/${encodeURIComponent(eventId)}`;
const isGone = (err) => err instanceof ApiError && (err.status === 404 || err.status === 410);

export const listCalendars = () => listAll('/users/me/calendarList', { maxResults: 250 });

export const createCalendar = (summary) => call('POST', '/calendars', { body: { summary } });

// Toàn bộ sự kiện do ứng dụng tạo ra (được gắn thuộc tính riêng sotay=1).
export const listAppEvents = (calendarId) =>
  listAll(`${cal(calendarId)}/events`, { privateExtendedProperty: 'sotay=1', showDeleted: true, maxResults: 2500 });

// Lịch trình trong một khoảng thời gian (dùng cho mục "Lịch Google").
export const listEvents = (calendarId, timeMin, timeMax) =>
  listAll(`${cal(calendarId)}/events`, { singleEvents: true, orderBy: 'startTime', timeMin, timeMax, maxResults: 250 });

export const insertEvent = (calendarId, body) => call('POST', `${cal(calendarId)}/events`, { body });

export async function getEvent(calendarId, eventId) {
  try {
    return await call('GET', evt(calendarId, eventId));
  } catch (err) {
    if (isGone(err)) return null;
    throw err;
  }
}

export const updateEvent = (calendarId, eventId, body) => call('PUT', evt(calendarId, eventId), { body });

export async function deleteEvent(calendarId, eventId) {
  try {
    await call('DELETE', evt(calendarId, eventId));
  } catch (err) {
    if (!isGone(err)) throw err;
  }
}

// Chuyển sự kiện sang lịch khác (giữ nguyên id). Trả về null nếu sự kiện không còn.
export async function moveEvent(calendarId, eventId, destination) {
  try {
    return await call('POST', `${evt(calendarId, eventId)}/move`, { query: { destination } });
  } catch (err) {
    if (isGone(err)) return null;
    throw err;
  }
}
