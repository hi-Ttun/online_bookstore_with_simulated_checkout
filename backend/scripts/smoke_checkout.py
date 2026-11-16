"""
Kiểm thử API checkout: idempotency, chống bán vượt tồn kho, validation. Chỉ dùng thư viện chuẩn của Python.

Chuẩn bị:  docker compose up -d  ->  nạp seed (db/seed.sql)  ->  chạy app
Chạy:      python scripts/smoke_checkout.py            (mặc định http://localhost:8080)
           python scripts/smoke_checkout.py https://<url-cloud-run>

Script giả định DB vừa seed xong (BOOK-001 còn 1 cuốn...). Chạy lại thì seed lại trước.
"""
import json
import sys
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor

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



def call(method, path, body=None, user="demo-customer", key=None):
    headers = {"Content-Type": "application/json", **auth(user)}
    if key is not None:
        headers["Idempotency-Key"] = key
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, json.loads(r.read() or b"null"), dict(r.headers)
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"null"), dict(e.headers)


def check(name, condition, info=""):
    global failures
    print(("PASS " if condition else "FAIL ") + name + ("" if condition else f"   -> {info}"))
    if not condition:
        failures += 1


def checkout(items, scenario="SUCCESS", user="demo-customer", key=None):
    return call("POST", "/api/checkout", {"items": items, "paymentScenario": scenario}, user=user, key=key)


def available(product_id):
    return call("GET", f"/api/products/{product_id}")[1]["available"]


# ---------- Đặt hàng bình thường + Test 2 (idempotency) ----------
k1 = str(uuid.uuid4())
s, b, _ = checkout([{"productId": 2, "quantity": 1}], user="alice", key=k1)
check("checkout mới -> 201 PENDING_PAYMENT", s == 201 and b["status"] == "PENDING_PAYMENT", (s, b))
check("tổng tiền = 120000 (số nguyên)", b.get("totalMinor") == 120000, b)
order1 = b.get("orderId")

s, b, h = checkout([{"productId": 2, "quantity": 1}], user="alice", key=k1)
check("Test 2a: gửi lại cùng key + cùng nội dung -> 200 cùng orderId",
      s == 200 and b["orderId"] == order1 and h.get("Idempotent-Replayed") == "true", (s, b, h))

s, b, _ = checkout([{"productId": 2, "quantity": 2}], user="alice", key=k1)
check("Test 2b: cùng key, khác nội dung -> 409 IDEMPOTENCY_KEY_REUSED",
      s == 409 and b["error"]["code"] == "IDEMPOTENCY_KEY_REUSED", (s, b))
check("sách 2 chỉ bị giữ 1 cuốn (5 -> 4)", available(2) == 4, available(2))

# 10 request cùng key bắn ĐỒNG THỜI -> vẫn chỉ 1 đơn
k2 = str(uuid.uuid4())
with ThreadPoolExecutor(10) as pool:
    results = list(pool.map(lambda _: checkout([{"productId": 5, "quantity": 1}], user="alice", key=k2), range(10)))
statuses = sorted(r[0] for r in results)
ids = {r[1]["orderId"] for r in results}
check("Test 2c: 10 request đồng thời cùng key -> 1x201 + 9x200, 1 orderId",
      statuses == [200] * 9 + [201] and len(ids) == 1, (statuses, ids))
check("sách 5 chỉ bị giữ 1 cuốn (20 -> 19)", available(5) == 19, available(5))

# ---------- Test 1: 20 người tranh 1 cuốn cuối (BOOK-001) ----------
with ThreadPoolExecutor(20) as pool:
    results = list(pool.map(
        lambda i: checkout([{"productId": 1, "quantity": 1}], user=f"buyer-{i}", key=str(uuid.uuid4())), range(20)))
ok = [r for r in results if r[0] == 201]
conflicts = [r for r in results if r[0] == 409 and r[1]["error"]["code"] == "OUT_OF_STOCK"]
check("Test 1: 20 người mua cuốn cuối -> đúng 1 thành công, 19 OUT_OF_STOCK",
      len(ok) == 1 and len(conflicts) == 19, sorted(r[0] for r in results))
check("sách 1 còn 0", available(1) == 0, available(1))

# Đơn nhiều sách, 1 sách hết -> rollback CẢ đơn (sách 3 không bị giữ)
s, b, _ = checkout([{"productId": 3, "quantity": 1}, {"productId": 1, "quantity": 1}], user="bob",
                   key=str(uuid.uuid4()))
check("đơn [sách 3, sách 1(hết)] -> 409 OUT_OF_STOCK productId=1",
      s == 409 and b["error"]["details"]["productId"] == 1, (s, b))
check("rollback: sách 3 vẫn còn 3", available(3) == 3, available(3))

# ---------- Kiểm tra dữ liệu đầu vào ----------
s, b, _ = checkout([{"productId": 2, "quantity": 1}], key=None)
check("thiếu Idempotency-Key -> 400", s == 400 and b["error"]["code"] == "VALIDATION_ERROR", (s, b))
s, b, _ = checkout([{"productId": 2, "quantity": 0}], key=str(uuid.uuid4()))
check("quantity = 0 -> 400 có chi tiết field", s == 400 and "items[0].quantity" in str(b["error"]["details"]), (s, b))
s, b, _ = checkout([{"productId": 2, "quantity": 1}, {"productId": 2, "quantity": 1}], key=str(uuid.uuid4()))
check("sách trùng trong items -> 400", s == 400, (s, b))
s, b, _ = checkout([{"productId": 999, "quantity": 1}], key=str(uuid.uuid4()))
check("sách không tồn tại -> 404 PRODUCT_NOT_FOUND", s == 404 and b["error"]["code"] == "PRODUCT_NOT_FOUND", (s, b))
s, b, _ = checkout([{"productId": 2, "quantity": 1}], scenario="MAYBE", key=str(uuid.uuid4()))
check("paymentScenario sai -> 400", s == 400, (s, b))
s, b, _ = checkout([], key=str(uuid.uuid4()))
check("giỏ rỗng -> 400", s == 400, (s, b))

# ---------- Xem đơn ----------
s, b, _ = call("GET", f"/api/orders/{order1}", user="alice")
check("alice xem đơn của mình -> 200 có lines",
      s == 200 and b["lines"][0]["productId"] == 2 and b["lines"][0]["lineTotalMinor"] == 120000, (s, b))
s, b, _ = call("GET", f"/api/orders/{order1}", user="mallory")
check("người khác xem đơn của alice -> 404", s == 404, (s, b))
s, b, _ = call("GET", "/api/orders", user="alice")
check("alice có đúng 2 đơn", s == 200 and len(b) == 2, (s, b))

print(f"\n{'TẤT CẢ ĐỀU PASS' if failures == 0 else f'{failures} FAIL'}")
sys.exit(1 if failures else 0)
