// Lớp gọi API dùng chung. Tuân theo quy ước của Backend (BACKEND.md mục 7.1):
//  - Lỗi: {"error": {"code", "message", "correlationId", "details"}}
//  - Mọi response có header X-Correlation-Id
//  - API cần đăng nhập: Authorization: Bearer <JWT>; API công khai KHÔNG gửi token.
import { config } from "./config";

export class ApiError extends Error {
  constructor(status, code, message, correlationId, details) {
    super(message);
    this.status = status;
    this.code = code;
    this.correlationId = correlationId || null;
    this.details = details || null;
  }
}

let getToken = async () => null;
let onUnauthorized = () => {};

/** AuthProvider đăng ký cách lấy token và xử lý khi token bị từ chối (401). */
export function setAuthHooks(hooks) {
  getToken = hooks.getToken;
  onUnauthorized = hooks.onUnauthorized;
}

const DEFAULT_MESSAGES = {
  0: "Không kết nối được máy chủ. Kiểm tra mạng rồi thử lại.",
  401: "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.",
  403: "Tài khoản của bạn không có quyền thực hiện thao tác này.",
  404: "Không tìm thấy dữ liệu.",
  500: "Máy chủ gặp lỗi. Vui lòng thử lại sau.",
};

/**
 * Gọi API và trả về { data, status, correlationId, replayed }.
 * @param {string} path  ví dụ "/api/products"
 * @param {object} opts  { method, body, auth, headers }
 */
export async function apiRequest(path, { method = "GET", body, auth = false, headers = {} } = {}) {
  const h = { ...headers };
  if (body !== undefined) h["Content-Type"] = "application/json";
  if (auth) {
    const token = await getToken();
    if (!token) {
      onUnauthorized();
      throw new ApiError(401, "UNAUTHORIZED", DEFAULT_MESSAGES[401]);
    }
    h.Authorization = `Bearer ${token}`;
  }

  let res;
  try {
    res = await fetch(config.apiUrl + path, {
      method,
      headers: h,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
  } catch {
    throw new ApiError(0, "NETWORK_ERROR", DEFAULT_MESSAGES[0]);
  }

  const correlationId = res.headers.get("X-Correlation-Id");
  const text = await res.text();
  let data = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = null;
    }
  }

  if (!res.ok) {
    const err = (data && data.error) || {};
    if (res.status === 401 && auth) onUnauthorized();
    throw new ApiError(
      res.status,
      err.code || `HTTP_${res.status}`,
      err.message || DEFAULT_MESSAGES[res.status] || DEFAULT_MESSAGES[500],
      err.correlationId || correlationId,
      err.details,
    );
  }

  return {
    data,
    status: res.status,
    correlationId,
    replayed: res.headers.get("Idempotent-Replayed") === "true",
  };
}

/** Rút gọn: chỉ trả về phần dữ liệu. */
export async function api(path, opts) {
  return (await apiRequest(path, opts)).data;
}

// ---- Các endpoint (BACKEND.md mục 7) ----

export const Catalog = {
  list: () => api("/api/products?page=0&size=100"),
  get: (id) => api(`/api/products/${id}`),
};

export const Orders = {
  checkout: (idempotencyKey, body) =>
    apiRequest("/api/checkout", {
      method: "POST",
      auth: true,
      body,
      headers: { "Idempotency-Key": idempotencyKey },
    }),
  mine: () => api("/api/orders", { auth: true }),
  get: (id) => api(`/api/orders/${id}`, { auth: true }),
};

export const Admin = {
  orders: (status) => api(`/api/admin/orders${status ? `?status=${status}` : ""}`, { auth: true }),
  order: (id) => api(`/api/admin/orders/${id}`, { auth: true }),
  fulfill: (id) => api(`/api/admin/orders/${id}/fulfill`, { method: "POST", auth: true }),
  inventory: () => api("/api/admin/inventory", { auth: true }),
  movements: (productId) =>
    api(`/api/admin/inventory/movements${productId ? `?productId=${productId}` : ""}`, { auth: true }),
  ops: () => api("/api/admin/ops/summary", { auth: true }),
  reconcile: () => api("/api/admin/reconcile", { method: "POST", auth: true }),
};
