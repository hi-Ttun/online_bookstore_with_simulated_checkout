// Định dạng hiển thị (HANDOVER.md mục 4.3).

const money = new Intl.NumberFormat("vi-VN", { style: "currency", currency: "VND" });

/** Tiền lưu dạng số nguyên đơn vị nhỏ nhất (VND: đồng). */
export function formatMoney(minor, currency = "VND") {
  if (minor == null) return "–";
  if (currency !== "VND") {
    return new Intl.NumberFormat("vi-VN", { style: "currency", currency }).format(minor / 100);
  }
  return money.format(minor);
}

const dateTime = new Intl.DateTimeFormat("vi-VN", {
  timeZone: "Asia/Ho_Chi_Minh",
  day: "2-digit",
  month: "2-digit",
  year: "numeric",
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
});

/** Backend trả thời gian UTC (ISO-8601); hiển thị theo giờ Việt Nam. */
export function formatTime(iso) {
  if (!iso) return "–";
  return dateTime.format(new Date(iso));
}

export function shortId(uuid) {
  return uuid ? String(uuid).slice(0, 8) : "–";
}

export const ORDER_STATUS = {
  PENDING_PAYMENT: { label: "Đang xử lý thanh toán", tone: "info" },
  PAYMENT_UNKNOWN: { label: "Đang xác minh thanh toán", tone: "warn" },
  PAID: { label: "Thanh toán thành công", tone: "ok" },
  PAYMENT_FAILED: { label: "Thanh toán thất bại", tone: "bad" },
  FULFILLED: { label: "Đã giao hàng", tone: "done" },
};

export const PAYMENT_STATUS = {
  PENDING: { label: "Đang chờ", tone: "info" },
  SUCCEEDED: { label: "Thành công", tone: "ok" },
  FAILED: { label: "Thất bại", tone: "bad" },
  UNKNOWN: { label: "Chưa rõ (chờ đối soát)", tone: "warn" },
};

export const SCENARIOS = [
  { value: "SUCCESS", label: "Thanh toán thành công", hint: "Cổng thanh toán chấp nhận giao dịch." },
  { value: "FAIL", label: "Thanh toán thất bại", hint: "Cổng từ chối; sách được hoàn lại vào kho." },
  {
    value: "TIMEOUT",
    label: "Quá thời gian (Timeout)",
    hint: "Không rõ kết quả; đơn chờ đối soát rồi mới chốt.",
  },
];

export const MOVEMENT_KIND = {
  SEED: { label: "Nhập ban đầu", tone: "info" },
  RESERVE: { label: "Giữ hàng", tone: "warn" },
  COMMIT: { label: "Xuất bán", tone: "ok" },
  RELEASE: { label: "Hoàn kho", tone: "bad" },
};

/** Trạng thái cuối: ngừng hỏi lại (polling). */
export const FINAL_STATUSES = ["PAID", "PAYMENT_FAILED", "FULFILLED"];
