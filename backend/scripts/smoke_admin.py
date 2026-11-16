"""
Kiểm thử API Store Operator. Chỉ gọi API, không truy cập DB.

Chuẩn bị:  docker compose up -d  ->  nạp seed  ->  chạy app với reconciler định kỳ TẮT để thử nút "Đối soát ngay":
             PowerShell:  $env:APP_RECONCILER_ENABLED='false'; .\\mvnw.cmd spring-boot:run
Chạy:      python scripts/smoke_admin.py [base_url]
"""
import json
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = sys.argv[1].rstrip("/") if len(sys.argv) > 1 else "http://localhost:8080"
failures = 0

_tokens = {}


def token(user):
    """Token dev (chỉ có ở profile local): POST /dev/token?user=... . user "operator" có quyền Store Operator."""
    if user not in _tokens:
        req = urllib.request.Request(f"{BASE}/dev/token?user={user}", method="POST")
        with urllib.request.urlopen(req) as r:
            _tokens[user] = json.loads(r.read())["accessToken"]
    return _tokens[user]


def auth(user):
    return {"Authorization": "Bearer " + token(user)}



def call(method, path, body=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    if path.startswith("/api/admin") and not headers:
        headers = auth("operator")  # mọi API /api/admin/** gọi bằng token của operator
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read()
            return r.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raw = e.read()
        return e.code, json.loads(raw) if raw else None


def check(name, condition, info=""):
    global failures
    print(("PASS " if condition else "FAIL ") + name + ("" if condition else f"   -> {info}"))
    if not condition:
        failures += 1


def checkout(product_id, quantity, scenario, user):
    s, b = call("POST", "/api/checkout",
                {"items": [{"productId": product_id, "quantity": quantity}], "paymentScenario": scenario},
                {"Idempotency-Key": str(uuid.uuid4()), **auth(user)})
    assert s == 201, (s, b)
    return b["orderId"]


def admin_status(order_id):
    return call("GET", f"/api/admin/orders/{order_id}")[1]["status"]


def wait_admin_status(order_id, wanted, timeout=30):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if admin_status(order_id) == wanted:
            return True
        time.sleep(1)
    return False


def inventory_of(product_id):
    row = next(r for r in call("GET", "/api/admin/inventory")[1] if r["productId"] == product_id)
    return row["onHand"], row["reserved"], row["available"]


# ---------- Chuẩn bị 3 đơn: thành công, thất bại, timeout ----------
s, inv = call("GET", "/api/admin/inventory")
check("tồn kho: đủ 50 sách, BOOK-001 onHand = 1",
      s == 200 and len(inv) == 50 and inv[0]["sku"] == "BOOK-001" and inv[0]["onHand"] == 1, (s, inv[:1]))

paid = checkout(10, 2, "SUCCESS", "op-a")
failed = checkout(11, 1, "FAIL", "op-b")
unknown = checkout(12, 1, "TIMEOUT", "op-c")
check("đơn TIMEOUT -> PAYMENT_UNKNOWN", wait_admin_status(unknown, "PAYMENT_UNKNOWN"), admin_status(unknown))
# Lần thanh toán UNKNOWN được tạo lúc worker xử lý (trước thời điểm này) => đếm giờ chờ đối soát từ đây
unknown_seen_at = time.time()
check("đơn SUCCESS -> PAID", wait_admin_status(paid, "PAID"), admin_status(paid))
check("đơn FAIL -> PAYMENT_FAILED", wait_admin_status(failed, "PAYMENT_FAILED"), admin_status(failed))

# ---------- Danh sách đơn ----------
s, rows = call("GET", "/api/admin/orders?status=PAID")
row = next((r for r in rows if r["orderId"] == paid), None) if s == 200 else None
check("fulfillment list (status=PAID) có đơn của op-a, itemCount = 2",
      row is not None and row["customerId"] == "op-a" and row["itemCount"] == 2 and row["fulfilledAt"] is None,
      (s, row))
check("fulfillment list chỉ chứa đơn PAID", all(r["status"] == "PAID" for r in rows), rows)
s, rows = call("GET", "/api/admin/orders")
check("danh sách mọi đơn có đủ 3 đơn", s == 200 and {paid, failed, unknown} <= {r["orderId"] for r in rows}, s)
s, b = call("GET", "/api/admin/orders?status=XYZ")
check("status không hợp lệ -> 400", s == 400 and b["error"]["code"] == "VALIDATION_ERROR", (s, b))
s, b = call("GET", f"/api/admin/orders/{failed}")
check("operator xem biên nhận đơn của khách khác -> 200, có attempt FAILED",
      s == 200 and [a["status"] for a in b["paymentAttempts"]] == ["FAILED"], (s, b))

# ---------- Tồn kho + lịch sử kho ----------
check("sách 10 (bán 2): onHand 18, reserved 0, available 18", inventory_of(10) == (18, 0, 18), inventory_of(10))
check("sách 11 (thanh toán lỗi): onHand 20, reserved 0", inventory_of(11) == (20, 0, 20), inventory_of(11))
check("sách 12 (đang chờ đối soát): onHand 20, reserved 1", inventory_of(12) == (20, 1, 19), inventory_of(12))
s, mv = call("GET", "/api/admin/inventory/movements?productId=10")
check("lịch sử sách 10 (mới nhất trước): COMMIT, RESERVE, SEED",
      s == 200 and [m["kind"] for m in mv] == ["COMMIT", "RESERVE", "SEED"], (s, [m["kind"] for m in mv]))
s, mv = call("GET", "/api/admin/inventory/movements?productId=11")
check("lịch sử sách 11: RELEASE, RESERVE, SEED", [m["kind"] for m in mv] == ["RELEASE", "RESERVE", "SEED"],
      [m["kind"] for m in mv])

# ---------- Thống kê vận hành ----------
s, ops = call("GET", "/api/admin/ops/summary")
check("ops summary: 1 thanh toán chờ đối soát, có đủ 3 trạng thái",
      s == 200 and ops["paymentsUnknown"] == 1 and ops["ordersByStatus"].get("PAYMENT_UNKNOWN") == 1
      and ops["ordersByStatus"].get("PAID", 0) >= 1 and ops["ordersByStatus"].get("PAYMENT_FAILED", 0) >= 1, ops)

# ---------- Fulfill ----------
s, b = call("POST", f"/api/admin/orders/{paid}/fulfill")
check("fulfill đơn PAID -> 200 FULFILLED", s == 200 and b["status"] == "FULFILLED", (s, b))
s, b = call("POST", f"/api/admin/orders/{paid}/fulfill")
check("fulfill lần 2 (double-click) -> vẫn 200", s == 200 and b["status"] == "FULFILLED", (s, b))
s, b = call("POST", f"/api/admin/orders/{failed}/fulfill")
check("fulfill đơn PAYMENT_FAILED -> 409 INVALID_STATE",
      s == 409 and b["error"]["code"] == "INVALID_STATE" and b["error"]["details"]["status"] == "PAYMENT_FAILED",
      (s, b))
s, b = call("POST", f"/api/admin/orders/{unknown}/fulfill")
check("fulfill đơn PAYMENT_UNKNOWN -> 409 (chưa chắc đã có tiền)", s == 409, (s, b))
s, b = call("POST", f"/api/admin/orders/{uuid.uuid4()}/fulfill")
check("fulfill đơn không tồn tại -> 404", s == 404 and b["error"]["code"] == "ORDER_NOT_FOUND", (s, b))
s, rows = call("GET", "/api/admin/orders?status=FULFILLED")
row = next((r for r in rows if r["orderId"] == paid), None)
check("đơn đã giao có fulfilledAt", row is not None and row["fulfilledAt"] is not None, row)

# ---------- Đối soát ngay ----------
# thanh toán UNKNOWN phải chờ >= 10 giây (APP_RECONCILER_MIN_AGE_SECONDS) mới được đối soát
time.sleep(max(0.0, 11 - (time.time() - unknown_seen_at)))
s, b = call("POST", "/api/admin/reconcile")
check("POST /api/admin/reconcile -> 200, đối soát 1 thanh toán", s == 200 and b["resolved"] == 1, (s, b))
check("đơn TIMEOUT -> PAID sau đối soát", wait_admin_status(unknown, "PAID", timeout=15), admin_status(unknown))
check("sách 12: onHand 19, reserved 0", inventory_of(12) == (19, 0, 19), inventory_of(12))
s, ops = call("GET", "/api/admin/ops/summary")
check("ops summary: không còn thanh toán chờ đối soát", ops["paymentsUnknown"] == 0, ops)
s, b = call("POST", "/api/admin/reconcile")
check("đối soát lần 2 -> không còn gì để làm (resolved 0)", s == 200 and b["resolved"] == 0, (s, b))

print(f"\n{'TẤT CẢ ĐỀU PASS' if failures == 0 else f'{failures} FAIL'}")
sys.exit(1 if failures else 0)
