// MOCK BACKEND – CHỈ DÙNG ĐỂ PHÁT TRIỂN / XEM THỬ GIAO DIỆN KHI CHƯA CHẠY ĐƯỢC BACKEND THẬT.
// Không thay thế Backend Spring Boot: không có Pub/Sub, outbox thật, không kiểm tra chữ ký JWT.
// Mô phỏng đúng hợp đồng API ở backend/docs/BACKEND.md mục 7 và đọc 50 sách từ backend/db/seed.sql.
//
//   node mock/server.mjs            (cổng 8080, giống Backend)
//   MOCK_RELAY=off node mock/server.mjs   (giả lập tắt outbox relay để xem thanh vận hành)
import http from "node:http";
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const PORT = Number(process.env.PORT || 8080);
const dir = path.dirname(fileURLToPath(import.meta.url));
const seedSql = fs.readFileSync(path.join(dir, "../../backend/db/seed.sql"), "utf8");

// ---- Dữ liệu mẫu ----
const products = [];
const re = /\(\s*(\d+),\s*'(BOOK-\d+)',\s*'((?:[^']|'')+)',\s*'((?:[^']|'')+)',\s*(\d+)\)/g;
for (const m of seedSql.matchAll(re)) {
  products.push({
    id: +m[1],
    sku: m[2],
    title: m[3].replace(/''/g, "'"),
    author: m[4].replace(/''/g, "'"),
    priceMinor: +m[5],
    currency: "VND",
    imageUrl: null,
  });
}
const STOCK = { "BOOK-001": 1, "BOOK-002": 5, "BOOK-003": 3, "BOOK-004": 10 };
const inventory = new Map();
const movements = [];
let movementSeq = 0;
const now = () => new Date().toISOString();
for (const p of products) {
  const onHand = STOCK[p.sku] ?? 20;
  inventory.set(p.id, { onHand, reserved: 0, updatedAt: now() });
  movements.push({ id: ++movementSeq, productId: p.id, sku: p.sku, orderId: null, kind: "SEED", quantity: onHand, createdAt: now() });
}
const orders = new Map(); // id -> order
const byKey = new Map(); // customer|key -> orderId
const outbox = []; // { id, createdAt, publishedAt, orderId, type }
let relayOn = process.env.MOCK_RELAY !== "off";

// ---- Tiện ích ----
function send(res, status, body, headers = {}) {
  res.writeHead(status, { "Content-Type": "application/json", ...headers });
  res.end(body === undefined ? "" : JSON.stringify(body));
}
function err(res, cid, status, code, message, details = {}) {
  send(res, status, { error: { code, message, correlationId: cid, details } }, { "X-Correlation-Id": cid });
}
function b64url(obj) {
  return Buffer.from(JSON.stringify(obj)).toString("base64url");
}
function parseToken(req) {
  const h = req.headers.authorization || "";
  if (!h.startsWith("Bearer ")) return null;
  try {
    const payload = JSON.parse(Buffer.from(h.slice(7).split(".")[1], "base64url").toString());
    if (payload.exp * 1000 < Date.now()) return null;
    return payload;
  } catch {
    return null;
  }
}
const isOperator = (u) => u.email === "operator@demo.local" && u.email_verified;
function receipt(o) {
  return {
    orderId: o.id,
    status: o.status,
    paymentScenario: o.paymentScenario,
    totalMinor: o.totalMinor,
    currency: "VND",
    createdAt: o.createdAt,
    lines: o.lines,
    paymentAttempts: o.attempts,
  };
}
function move(productId, orderId, kind, quantity) {
  const p = products.find((x) => x.id === productId);
  movements.push({ id: ++movementSeq, productId, sku: p.sku, orderId, kind, quantity, createdAt: now() });
}

// ---- "Relay + worker" mô phỏng: xử lý sự kiện order.created sau ~1 giây ----
setInterval(() => {
  if (!relayOn) return;
  for (const ev of outbox.filter((e) => !e.publishedAt)) {
    ev.publishedAt = now();
    setTimeout(() => processPayment(orders.get(ev.orderId)), 800);
  }
}, 1000);

function processPayment(o) {
  if (!o || o.status !== "PENDING_PAYMENT") return;
  const attempt = { status: "PENDING", createdAt: now(), resolvedAt: null };
  o.attempts.push(attempt);
  if (o.paymentScenario === "SUCCESS") settle(o, attempt, true);
  else if (o.paymentScenario === "FAIL") settle(o, attempt, false);
  else {
    attempt.status = "UNKNOWN";
    o.status = "PAYMENT_UNKNOWN";
    o.unknownSince = Date.now();
  }
}
function settle(o, attempt, ok) {
  attempt.status = ok ? "SUCCEEDED" : "FAILED";
  attempt.resolvedAt = now();
  for (const l of o.lines) {
    const inv = inventory.get(l.productId);
    inv.reserved -= l.quantity;
    if (ok) inv.onHand -= l.quantity;
    inv.updatedAt = now();
    move(l.productId, o.id, ok ? "COMMIT" : "RELEASE", l.quantity);
  }
  o.status = ok ? "PAID" : "PAYMENT_FAILED";
}
// Đối soát: timeout được xác định là thành công sau >= 10 giây.
function reconcile(force) {
  let n = 0;
  for (const o of orders.values()) {
    if (o.status === "PAYMENT_UNKNOWN" && (force || Date.now() - o.unknownSince > 15000) && Date.now() - o.unknownSince > 10000) {
      settle(o, o.attempts[o.attempts.length - 1], true);
      n++;
    }
  }
  return n;
}
setInterval(() => reconcile(false), 5000);

// ---- HTTP ----
const server = http.createServer(async (req, res) => {
  const cid = (req.headers["x-correlation-id"] || crypto.randomUUID()).toString().slice(0, 100);
  const url = new URL(req.url, "http://x");
  const p = url.pathname;
  let raw = "";
  for await (const c of req) raw += c;
  let body = null;
  try {
    body = raw ? JSON.parse(raw) : null;
  } catch {
    return err(res, cid, 400, "VALIDATION_ERROR", "JSON không hợp lệ");
  }
  const H = { "X-Correlation-Id": cid };
  setTimeout(() => route(), 120); // độ trễ mạng giả lập

  function route() {
    if (p === "/healthz") return send(res, 200, { status: "ok" }, H);
    if (p === "/mock/relay" && req.method === "POST") {
      relayOn = url.searchParams.get("enabled") !== "false";
      return send(res, 200, { relay: relayOn }, H);
    }
    if (p === "/dev/token" && req.method === "POST") {
      const user = url.searchParams.get("user") || "";
      if (!/^[a-z0-9-]{1,40}$/.test(user)) return err(res, cid, 400, "VALIDATION_ERROR", "user không hợp lệ");
      const exp = Math.floor(Date.now() / 1000) + 3600;
      const email = `${user}@demo.local`;
      const token = `${b64url({ alg: "none" })}.${b64url({ sub: user, email, email_verified: true, exp })}.mock`;
      return send(res, 200, { accessToken: token, tokenType: "Bearer", expiresIn: 3600, sub: user, email, emailVerified: true }, H);
    }
    if (p === "/api/products" && req.method === "GET") {
      return send(res, 200, products.map((x) => ({ ...x, available: inventory.get(x.id).onHand - inventory.get(x.id).reserved })), H);
    }
    let m = p.match(/^\/api\/products\/(\d+)$/);
    if (m) {
      const x = products.find((y) => y.id === +m[1]);
      if (!x) return err(res, cid, 404, "PRODUCT_NOT_FOUND", "Không tìm thấy sách");
      const inv = inventory.get(x.id);
      return send(res, 200, { ...x, available: inv.onHand - inv.reserved }, H);
    }

    if (!p.startsWith("/api/")) return err(res, cid, 404, "NOT_FOUND", "Không tìm thấy");
    const user = parseToken(req);
    if (!user) return err(res, cid, 401, "UNAUTHORIZED", "Cần đăng nhập");

    if (p === "/api/checkout" && req.method === "POST") {
      const key = req.headers["idempotency-key"];
      const items = body && body.items;
      const sc = body && body.paymentScenario;
      if (!key || !Array.isArray(items) || !items.length || items.length > 20 || !["SUCCESS", "FAIL", "TIMEOUT"].includes(sc)
        || items.some((i) => !(i.quantity >= 1 && i.quantity <= 100))) {
        return err(res, cid, 400, "VALIDATION_ERROR", "Dữ liệu gửi lên không hợp lệ", { errors: ["items/paymentScenario/Idempotency-Key"] });
      }
      const sorted = [...items].sort((a, b) => a.productId - b.productId);
      if (new Set(sorted.map((i) => i.productId)).size !== sorted.length) {
        return err(res, cid, 400, "VALIDATION_ERROR", "Mỗi sách chỉ được xuất hiện một lần trong items");
      }
      const hash = sorted.map((i) => `${i.productId}x${i.quantity}`).join(",") + "|" + sc;
      const k = `${user.sub}|${key}`;
      if (byKey.has(k)) {
        const o = orders.get(byKey.get(k));
        if (o.hash !== hash) {
          return err(res, cid, 409, "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key đã được dùng cho một giỏ hàng khác", { orderId: o.id });
        }
        return send(res, 200, receipt(o), { ...H, "Idempotent-Replayed": "true" });
      }
      for (const i of sorted) {
        if (!products.find((x) => x.id === i.productId)) return err(res, cid, 404, "PRODUCT_NOT_FOUND", `Không tìm thấy sách id=${i.productId}`, { productId: i.productId });
      }
      for (const i of sorted) {
        const inv = inventory.get(i.productId);
        if (inv.onHand - inv.reserved < i.quantity) {
          return err(res, cid, 409, "OUT_OF_STOCK", `Sách id=${i.productId} không đủ số lượng`, { productId: i.productId, requested: i.quantity });
        }
      }
      const id = crypto.randomUUID();
      const lines = sorted.map((i) => {
        const pr = products.find((x) => x.id === i.productId);
        const inv = inventory.get(i.productId);
        inv.reserved += i.quantity;
        inv.updatedAt = now();
        move(i.productId, id, "RESERVE", i.quantity);
        return { productId: pr.id, title: pr.title, quantity: i.quantity, unitPriceMinor: pr.priceMinor, lineTotalMinor: pr.priceMinor * i.quantity };
      });
      const o = { id, customer: user.sub, hash, status: "PENDING_PAYMENT", paymentScenario: sc, totalMinor: lines.reduce((s, l) => s + l.lineTotalMinor, 0), createdAt: now(), lines, attempts: [], fulfilledAt: null };
      orders.set(id, o);
      byKey.set(k, id);
      outbox.push({ id: crypto.randomUUID(), orderId: id, createdAt: Date.now(), publishedAt: null });
      return send(res, 201, receipt(o), H);
    }
    if (p === "/api/orders" && req.method === "GET") {
      const list = [...orders.values()].filter((o) => o.customer === user.sub).reverse().slice(0, 100);
      return send(res, 200, list.map((o) => ({ orderId: o.id, status: o.status, totalMinor: o.totalMinor, currency: "VND", createdAt: o.createdAt })), H);
    }
    m = p.match(/^\/api\/orders\/([\w-]+)$/);
    if (m && req.method === "GET") {
      const o = orders.get(m[1]);
      if (!o || o.customer !== user.sub) return err(res, cid, 404, "ORDER_NOT_FOUND", "Không tìm thấy đơn hàng");
      return send(res, 200, receipt(o), H);
    }

    if (p.startsWith("/api/admin/")) {
      if (!isOperator(user)) return err(res, cid, 403, "FORBIDDEN", "Không có quyền");
      if (p === "/api/admin/orders") {
        const st = url.searchParams.get("status");
        const list = [...orders.values()].filter((o) => !st || o.status === st).reverse().slice(0, 200);
        return send(res, 200, list.map((o) => ({ orderId: o.id, customerId: o.customer, status: o.status, paymentScenario: o.paymentScenario, totalMinor: o.totalMinor, currency: "VND", itemCount: o.lines.reduce((s, l) => s + l.quantity, 0), createdAt: o.createdAt, fulfilledAt: o.fulfilledAt })), H);
      }
      m = p.match(/^\/api\/admin\/orders\/([\w-]+)(\/fulfill)?$/);
      if (m) {
        const o = orders.get(m[1]);
        if (!o) return err(res, cid, 404, "ORDER_NOT_FOUND", "Không tìm thấy đơn hàng");
        if (m[2] && req.method === "POST") {
          if (o.status === "PAID") {
            o.status = "FULFILLED";
            o.fulfilledAt = now();
          } else if (o.status !== "FULFILLED") {
            return err(res, cid, 409, "INVALID_STATE", "Đơn chưa thanh toán thành công", { status: o.status });
          }
        }
        return send(res, 200, receipt(o), H);
      }
      if (p === "/api/admin/inventory") {
        return send(res, 200, products.map((x) => { const i = inventory.get(x.id); return { productId: x.id, sku: x.sku, title: x.title, onHand: i.onHand, reserved: i.reserved, available: i.onHand - i.reserved, updatedAt: i.updatedAt }; }), H);
      }
      if (p === "/api/admin/inventory/movements") {
        const pid = url.searchParams.get("productId");
        return send(res, 200, movements.filter((x) => !pid || x.productId === +pid).slice().reverse().slice(0, 200), H);
      }
      if (p === "/api/admin/ops/summary") {
        const pending = outbox.filter((e) => !e.publishedAt);
        const byStatus = {};
        for (const o of orders.values()) byStatus[o.status] = (byStatus[o.status] || 0) + 1;
        return send(res, 200, {
          outboxPending: pending.length,
          outboxOldestAgeSeconds: pending.length ? Math.floor((Date.now() - Math.min(...pending.map((e) => e.createdAt))) / 1000) : 0,
          paymentsUnknown: [...orders.values()].filter((o) => o.status === "PAYMENT_UNKNOWN").length,
          ordersByStatus: byStatus,
        }, H);
      }
      if (p === "/api/admin/reconcile" && req.method === "POST") return send(res, 200, { resolved: reconcile(true) }, H);
    }
    return err(res, cid, 404, "NOT_FOUND", "Không tìm thấy");
  }
});

server.listen(PORT, () => {
  console.log(`[mock] Backend giả lập chạy tại http://localhost:${PORT} – ${products.length} sách, relay ${relayOn ? "BẬT" : "TẮT"}`);
});
