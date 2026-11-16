"""
Kiểm thử Correlation-ID và log JSON.

Chuẩn bị:  chạy app với log JSON, ghi log ra file. PowerShell, trong thư mục backend:
             $env:LOGGING_STRUCTURED_FORMAT_CONSOLE='vn.edu.uth.bookstore.logging.CloudLoggingFormatter'
             .\\mvnw.cmd spring-boot:run | Tee-Object -FilePath app.log
Chạy:      python scripts/smoke_correlation.py              (chỉ kiểm phần API: header, body lỗi)
           python scripts/smoke_correlation.py app.log      (+ đọc log: chuỗi log của 1 đơn dùng chung 1 ID)
"""
import json
import re
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = "http://localhost:8080"
LOG_FILE = sys.argv[1] if len(sys.argv) > 1 else None
UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
failures = 0


def call(method, path, body=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read()
            return r.status, json.loads(raw) if raw else None, dict(r.headers)
    except urllib.error.HTTPError as e:
        raw = e.read()
        return e.code, json.loads(raw) if raw else None, dict(e.headers)


def check(name, condition, info=""):
    global failures
    print(("PASS " if condition else "FAIL ") + name + ("" if condition else f"   -> {info}"))
    if not condition:
        failures += 1


def token(user):
    with urllib.request.urlopen(urllib.request.Request(f"{BASE}/dev/token?user={user}", method="POST")) as r:
        return json.loads(r.read())["accessToken"]


ALICE = token("alice")


def checkout(scenario, product_id, correlation_id=None):
    headers = {"Authorization": "Bearer " + ALICE, "Idempotency-Key": str(uuid.uuid4())}
    if correlation_id:
        headers["X-Correlation-Id"] = correlation_id
    return call("POST", "/api/checkout",
                {"items": [{"productId": product_id, "quantity": 1}], "paymentScenario": scenario}, headers)


def wait_status(order_id, wanted, timeout=40):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if call("GET", f"/api/orders/{order_id}", headers={"Authorization": "Bearer " + ALICE})[1]["status"] == wanted:
            return True
        time.sleep(1)
    return False


# ---------- Phần API ----------
cid_ok = "demo-" + uuid.uuid4().hex[:12]
s, b, h = checkout("SUCCESS", 30, cid_ok)
check("FE gửi X-Correlation-Id -> server trả lại đúng ID trong header", s == 201 and h.get("X-Correlation-Id") == cid_ok, h)
order_ok = b["orderId"]

s, b, h = call("GET", "/api/products/1")
check("không gửi ID -> server tự sinh UUID", UUID_RE.match(h.get("X-Correlation-Id", "")) is not None, h)

s, b, h = call("GET", "/api/products/1", headers={"X-Correlation-Id": "co dau cach <script>"})
check("ID bẩn (dấu cách, ký tự lạ) -> bị thay bằng UUID mới (chống log injection)",
      UUID_RE.match(h.get("X-Correlation-Id", "")) is not None, h)

s, b, h = call("GET", "/api/products/999", headers={"X-Correlation-Id": "err-404-check"})
check("lỗi 404 -> body có error.correlationId = ID đã gửi",
      s == 404 and b["error"]["correlationId"] == "err-404-check", b)
s, b, h = call("GET", "/api/orders")
check("lỗi 401 (từ Spring Security) -> body cũng có correlationId, khớp header",
      s == 401 and b["error"]["correlationId"] == h.get("X-Correlation-Id"), (b, h))

cid_timeout = "demo-" + uuid.uuid4().hex[:12]
s, b, _ = checkout("TIMEOUT", 31, cid_timeout)
order_timeout = b["orderId"]
check("đơn SUCCESS -> PAID", wait_status(order_ok, "PAID"))
check("đơn TIMEOUT -> PAID (qua reconciler)", wait_status(order_timeout, "PAID"))

# ---------- Phần log (nếu có file) ----------
if LOG_FILE:
    time.sleep(2)  # chờ log cuối được ghi ra file
    lines = []
    with open(LOG_FILE, encoding="utf-8", errors="replace") as f:
        for raw in f:
            raw = raw.strip()
            if raw.startswith("{"):
                try:
                    lines.append(json.loads(raw))
                except ValueError:
                    pass
    check("log là JSON, mỗi dòng có severity/time/message", len(lines) > 0 and all(
        {"severity", "time", "message"} <= set(x) for x in lines), len(lines))
    check("severity theo chuẩn Cloud Logging (INFO/WARNING/ERROR/DEBUG)",
          {x["severity"] for x in lines} <= {"INFO", "WARNING", "ERROR", "DEBUG"}, {x["severity"] for x in lines})

    def chain(cid):
        return [re.match(r"event=(\w+)", x["message"]).group(1) + ("(" + x["message"].split("eventType=")[1].split()[0] + ")"
                if "eventType=" in x["message"] else "")
                for x in lines if x.get("correlationId") == cid and x["message"].startswith("event=")]

    got = chain(cid_ok)
    expected = ["checkout_created", "http_request", "outbox_published(order.created)", "worker_received(order.created)",
                "payment_attempted", "outbox_published(payment.succeeded)", "worker_received(payment.succeeded)",
                "order_settled"]
    check("đơn SUCCESS: API -> relay -> worker -> relay -> worker đều cùng 1 correlationId",
          all(e in got for e in expected), got)
    print("     chuỗi log:", " -> ".join(got))

    got = chain(cid_timeout)
    check("đơn TIMEOUT: có cả bước reconciler trong cùng chuỗi",
          "payment_reconciled" in got and "order_settled" in got, got)
    print("     chuỗi log:", " -> ".join(got))

    worker_lines = [x for x in lines if x.get("correlationId") == cid_ok and x["message"].startswith("event=worker_received")]
    check("log worker có trường orderId riêng (lọc được theo đơn)",
          worker_lines and all(x.get("orderId") == order_ok for x in worker_lines), worker_lines[:1])

    stats = [x for x in lines if x.get("event") == "outbox_stats"]
    check("outbox_stats có trường số oldestAgeSeconds (cho log-based metric)",
          stats and isinstance(stats[-1].get("oldestAgeSeconds"), int), stats[-1:])
    req = [x for x in lines if x.get("event") == "http_request" and x.get("path") == "/api/checkout"]
    check("http_request /api/checkout có durationMs + status dạng số (cho checkout latency p95)",
          req and isinstance(req[-1].get("durationMs"), int) and isinstance(req[-1].get("status"), int), req[-1:])
    rec = [x for x in lines if x.get("event") == "reconcile_stats"]
    check("reconcile_stats có trường số unknownCount (reconciliation backlog)",
          rec and isinstance(rec[-1].get("unknownCount"), int), rec[-1:])

    whole = open(LOG_FILE, encoding="utf-8", errors="replace").read()
    check("log KHÔNG chứa token / header Authorization", ALICE not in whole and "Bearer ey" not in whole)

print(f"\n{'TẤT CẢ ĐỀU PASS' if failures == 0 else f'{failures} FAIL'}")
sys.exit(1 if failures else 0)
