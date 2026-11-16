"""
Kiểm thử luồng thanh toán bất đồng bộ (SUCCESS / FAIL / TIMEOUT), gồm Test 3 của đề bài. Chỉ dùng thư viện chuẩn của Python.

Chuẩn bị:  docker compose up -d  ->  nạp seed (db/seed.sql)  ->  chạy app (profile local: relay + worker + reconciler)
Chạy:      python scripts/smoke_payment.py

Script đọc số kho thật (on_hand, reserved) bằng psql trong container Docker => CHỈ chạy được ở máy local.
Script phát lại sự kiện bằng cách POST thẳng vào /internal/pubsub/push, y như Pub/Sub gửi lại tin cũ.
"""
import base64
import json
import pathlib
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = "http://localhost:8080"
BACKEND_DIR = pathlib.Path(__file__).resolve().parent.parent
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
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read()
            return r.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raw = e.read()
        return e.code, json.loads(raw) if raw else None


def sql(query):
    """Chạy 1 câu SQL trong container postgres, trả về chuỗi kết quả (các cột cách nhau '|')."""
    return subprocess.run(
        ["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "bookstore", "-d", "bookstore", "-tA", "-c", query],
        cwd=BACKEND_DIR, capture_output=True, text=True, check=True).stdout.strip()


def stock(product_id):
    on_hand, reserved = sql(f"SELECT on_hand, reserved FROM inventory WHERE product_id = {product_id}").split("|")
    return int(on_hand), int(reserved)


def movements(order_id, kind):
    return int(sql(f"SELECT count(*) FROM inventory_movement WHERE order_id = '{order_id}' AND kind = '{kind}'"))


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


def wait_status(order_id, user, wanted, timeout=40):
    """Gọi GET /api/orders/{id} mỗi giây (y như FE sẽ làm) tới khi status = wanted."""
    deadline = time.time() + timeout
    status = None
    while time.time() < deadline:
        status = call("GET", f"/api/orders/{order_id}", headers=auth(user))[1]["status"]
        if status == wanted:
            return True, status
        time.sleep(1)
    return False, status


def replay(event_id, event_type, order_id):
    """Giả lập Pub/Sub gửi lại 1 tin: POST đúng định dạng push envelope vào worker."""
    envelope = {"message": {"data": base64.b64encode(b"{}").decode(), "messageId": "replay-" + str(uuid.uuid4()),
                            "attributes": {"event_id": event_id, "event_type": event_type, "aggregate_id": order_id}},
                "subscription": "projects/local-project/subscriptions/order-events-worker"}
    return call("POST", "/internal/pubsub/push", envelope)[0]


# ---------- SUCCESS: sách 5 (20 cuốn), mua 2 ----------
o1 = checkout(5, 2, "SUCCESS", "s-user")
ok, st = wait_status(o1, "s-user", "PAID")
check("SUCCESS: đơn chuyển sang PAID", ok, st)
check("SUCCESS: kho sách 5 = on_hand 18, reserved 0", stock(5) == (18, 0), stock(5))
check("SUCCESS: đúng 1 movement COMMIT", movements(o1, "COMMIT") == 1, movements(o1, "COMMIT"))
receipt = call("GET", f"/api/orders/{o1}", headers=auth("s-user"))[1]
check("SUCCESS: biên nhận có 1 payment attempt SUCCEEDED",
      [a["status"] for a in receipt["paymentAttempts"]] == ["SUCCEEDED"], receipt["paymentAttempts"])

# ---------- Test 3: FAIL, sách 3 (3 cuốn), mua 2 ----------
o2 = checkout(3, 2, "FAIL", "f-user")
# (không kiểm reserved = 2 ở đây: worker chạy nhanh, có thể đã nhả kho trước khi dòng này chạy)
check("Test 3: on_hand sách 3 không đổi (3) – thất bại không được trừ kho thật", stock(3)[0] == 3, stock(3))
ok, st = wait_status(o2, "f-user", "PAYMENT_FAILED")
check("Test 3: đơn chuyển sang PAYMENT_FAILED", ok, st)
check("Test 3: kho hoàn lại đủ (on_hand 3, reserved 0)", stock(3) == (3, 0), stock(3))
check("Test 3: đúng 1 movement RELEASE", movements(o2, "RELEASE") == 1, movements(o2, "RELEASE"))

failed_event = sql(f"SELECT id FROM outbox_event WHERE aggregate_id = '{o2}' AND event_type = 'payment.failed'")
codes = [replay(failed_event, "payment.failed", o2) for _ in range(3)]
check("Test 3: phát lại sự kiện payment.failed 3 lần -> worker ack (204) cả 3", codes == [204] * 3, codes)
check("Test 3: sau replay, kho VẪN on_hand 3, reserved 0", stock(3) == (3, 0), stock(3))
check("Test 3: sau replay, VẪN 1 movement RELEASE", movements(o2, "RELEASE") == 1, movements(o2, "RELEASE"))

code = replay(str(uuid.uuid4()), "payment.failed", o2)
check("Test 3: sự kiện payment.failed MỚI (event_id khác) cho cùng đơn -> không hoàn kho lần 2",
      code == 204 and stock(3) == (3, 0) and movements(o2, "RELEASE") == 1, (code, stock(3)))
code = replay(str(uuid.uuid4()), "payment.succeeded", o2)
check("sự kiện payment.succeeded đến muộn cho đơn đã FAILED -> bị bỏ qua",
      code == 204 and stock(3) == (3, 0) and movements(o2, "COMMIT") == 0, (code, stock(3)))

# Phát lại order.created -> không được thanh toán lần 2
created_event = sql(f"SELECT id FROM outbox_event WHERE aggregate_id = '{o1}' AND event_type = 'order.created'")
code = replay(created_event, "order.created", o1)
attempts = int(sql(f"SELECT count(*) FROM payment_attempt WHERE order_id = '{o1}'"))
check("phát lại order.created -> vẫn 1 payment attempt", code == 204 and attempts == 1, (code, attempts))

# ---------- TIMEOUT: sách 4 (10 cuốn), mua 1 ----------
o3 = checkout(4, 1, "TIMEOUT", "t-user")
ok, st = wait_status(o3, "t-user", "PAYMENT_UNKNOWN", timeout=15)
check("TIMEOUT: đơn sang PAYMENT_UNKNOWN (KHÔNG bị coi là thất bại)", ok, st)
check("TIMEOUT: kho vẫn đang giữ (on_hand 10, reserved 1)", stock(4) == (10, 1), stock(4))
ok, st = wait_status(o3, "t-user", "PAID", timeout=40)
check("TIMEOUT: reconciler đối soát -> PAID", ok, st)
check("TIMEOUT: kho sách 4 = on_hand 9, reserved 0", stock(4) == (9, 0), stock(4))
check("TIMEOUT: payment attempt UNKNOWN -> SUCCEEDED",
      sql(f"SELECT status FROM payment_attempt WHERE order_id = '{o3}'") == "SUCCEEDED")

# ---------- Tin hỏng ----------
s, _ = call("POST", "/internal/pubsub/push", {"message": {"data": "", "attributes": {}}})
check("tin thiếu attributes -> 400 (Pub/Sub sẽ đưa vào dead-letter sau 5 lần)", s == 400, s)

print(f"\n{'TẤT CẢ ĐỀU PASS' if failures == 0 else f'{failures} FAIL'}")
sys.exit(1 if failures else 0)
