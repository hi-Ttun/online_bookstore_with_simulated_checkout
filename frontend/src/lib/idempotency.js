// Quản lý Idempotency-Key cho checkout (HANDOVER.md mục 4.2, BACKEND.md mục 7.3).
//
// Quy tắc: sinh key MỘT LẦN cho mỗi lượt thanh toán và GIỮ NGUYÊN khi người dùng bấm lại
// hoặc gửi lại do lỗi mạng. Chỉ sinh key mới khi nội dung đơn (giỏ hàng + kịch bản thanh toán) thay đổi.
// Nếu sinh key mới mỗi lần bấm thì Backend sẽ coi là đơn mới => mất tác dụng chống đặt trùng.

const PENDING = "bookstore.checkout.pending";
const LAST = "bookstore.checkout.last";

function uuid() {
  if (crypto.randomUUID) return crypto.randomUUID();
  // Dự phòng cho trình duyệt cũ / trang không phải HTTPS.
  const b = crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = [...b].map((x) => x.toString(16).padStart(2, "0")).join("");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

/** Dấu vân tay nội dung đơn, giống cách Backend chuẩn hóa: "1x2,5x1|SUCCESS". */
export function fingerprint(body) {
  const lines = [...body.items]
    .sort((a, b) => a.productId - b.productId)
    .map((i) => `${i.productId}x${i.quantity}`)
    .join(",");
  return `${lines}|${body.paymentScenario}`;
}

function readPending() {
  try {
    const v = JSON.parse(sessionStorage.getItem(PENDING));
    return v && typeof v === "object" ? v : {};
  } catch {
    return {};
  }
}

/**
 * Lấy key của nội dung này, hoặc sinh key mới nếu là nội dung chưa gặp.
 * Lưu theo từng nội dung: người dùng đổi kịch bản rồi đổi lại vẫn dùng đúng key cũ, nên một
 * request đã gửi nhưng mất phản hồi (lỗi mạng) không thể bị tạo thành đơn thứ hai.
 */
export function keyFor(body) {
  const fp = fingerprint(body);
  const pending = readPending();
  if (pending[fp]) return pending[fp];
  pending[fp] = uuid();
  sessionStorage.setItem(PENDING, JSON.stringify(pending));
  return pending[fp];
}

/** Bắt buộc sinh key mới cho nội dung này (dùng khi Backend báo IDEMPOTENCY_KEY_REUSED). */
export function resetKey(body) {
  const pending = readPending();
  delete pending[fingerprint(body)];
  sessionStorage.setItem(PENDING, JSON.stringify(pending));
}

/** Lưu lần checkout thành công gần nhất để demo Test 2 (gửi lại cùng key). */
export function rememberLast(key, body, orderId) {
  sessionStorage.removeItem(PENDING);
  sessionStorage.setItem(LAST, JSON.stringify({ key, body, orderId }));
}

export function lastCheckout() {
  try {
    return JSON.parse(sessionStorage.getItem(LAST));
  } catch {
    return null;
  }
}
