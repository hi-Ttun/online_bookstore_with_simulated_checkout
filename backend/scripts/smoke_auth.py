"""
Kiểm thử xác thực và phân quyền bằng cách gọi trực tiếp API (không qua giao diện web)
(đề: "Test authorized and forbidden API calls directly, not only hidden browser buttons").

Chuẩn bị:  docker compose up -d  ->  chạy app (profile local)
Chạy:      python scripts/smoke_auth.py
"""
import base64
import hashlib
import hmac
import json
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = sys.argv[1].rstrip("/") if len(sys.argv) > 1 else "http://localhost:8080"
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
        try:
            parsed = json.loads(raw) if raw else None
        except ValueError:
            parsed = raw.decode(errors="replace")
        return e.code, parsed, dict(e.headers)


def check(name, condition, info=""):
    global failures
    print(("PASS " if condition else "FAIL ") + name + ("" if condition else f"   -> {info}"))
    if not condition:
        failures += 1


def dev_token(user, **params):
    query = "&".join([f"user={user}"] + [f"{k}={v}" for k, v in params.items()])
    s, b, _ = call("POST", f"/dev/token?{query}")
    assert s == 200, (s, b)
    return b["accessToken"]


def bearer(tok):
    return {"Authorization": "Bearer " + tok}


def b64url(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def forge(header, claims, secret):
    """Tự ký 1 JWT – giả lập kẻ tấn công không biết khóa bí mật của server."""
    signing_input = b64url(json.dumps(header).encode()) + "." + b64url(json.dumps(claims).encode())
    sig = hmac.new(secret.encode(), signing_input.encode(), hashlib.sha256).digest() if secret else b""
    return signing_input + "." + b64url(sig)


def code_of(body):
    return body.get("error", {}).get("code") if isinstance(body, dict) else body


CHECKOUT = {"items": [{"productId": 20, "quantity": 1}], "paymentScenario": "SUCCESS"}

# ---------- API công khai ----------
check("GET /api/products không cần token -> 200", call("GET", "/api/products")[0] == 200)
check("GET /healthz không cần token -> 200", call("GET", "/healthz")[0] == 200)
s, b, _ = call("GET", "/v3/api-docs")
check("Swagger (OpenAPI) công khai, có khai báo bearer token",
      s == 200 and "bearer" in b.get("components", {}).get("securitySchemes", {}), s)

# ---------- 401: chưa đăng nhập / token không hợp lệ ----------
s, b, h = call("POST", "/api/checkout", CHECKOUT, {"Idempotency-Key": str(uuid.uuid4())})
check("checkout không token -> 401 UNAUTHORIZED (JSON) + header WWW-Authenticate",
      s == 401 and code_of(b) == "UNAUTHORIZED" and h.get("WWW-Authenticate", "").startswith("Bearer"), (s, b, h))
check("GET /api/orders không token -> 401", call("GET", "/api/orders")[0] == 401)
check("GET /api/admin/orders không token -> 401", call("GET", "/api/admin/orders")[0] == 401)
s, b, _ = call("GET", "/api/orders", headers=bearer("day-khong-phai-token"))
check("token rác -> 401", s == 401 and code_of(b) == "UNAUTHORIZED", (s, b))
s, b, _ = call("GET", "/api/orders", headers=bearer(dev_token("alice", expiresInSeconds=-3600)))
check("token đã hết hạn -> 401", s == 401, (s, b))

claims = {"sub": "operator", "email": "operator@demo.local", "email_verified": True,
          "iat": int(time.time()), "exp": int(time.time()) + 3600}
s, b, _ = call("GET", "/api/admin/orders", headers=bearer(forge({"alg": "HS256", "typ": "JWT"}, claims, "doan-mo-khoa")))
check("token tự ký bằng khóa sai (giả làm operator) -> 401", s == 401, (s, b))
s, b, _ = call("GET", "/api/admin/orders", headers=bearer(forge({"alg": "none", "typ": "JWT"}, claims, None)))
check("token alg=none (không chữ ký) -> 401", s == 401, (s, b))

# ---------- 403: đăng nhập rồi nhưng không đủ quyền ----------
alice = dev_token("alice")
s, b, _ = call("GET", "/api/admin/orders", headers=bearer(alice))
check("khách hàng gọi GET /api/admin/orders -> 403 FORBIDDEN (JSON)", s == 403 and code_of(b) == "FORBIDDEN", (s, b))
check("khách hàng gọi POST /api/admin/reconcile -> 403",
      call("POST", "/api/admin/reconcile", headers=bearer(alice))[0] == 403)
check("khách hàng gọi POST /api/admin/orders/{id}/fulfill -> 403",
      call("POST", f"/api/admin/orders/{uuid.uuid4()}/fulfill", headers=bearer(alice))[0] == 403)
s, b, _ = call("GET", "/api/admin/orders", headers=bearer(dev_token("operator", emailVerified="false")))
check("email operator CHƯA xác minh -> 403 (không ai tự đăng ký giả email operator được)", s == 403, (s, b))

# ---------- 200: đúng quyền ----------
operator = dev_token("operator")
check("operator gọi GET /api/admin/orders -> 200", call("GET", "/api/admin/orders", headers=bearer(operator))[0] == 200)
check("operator gọi GET /api/admin/ops/summary -> 200",
      call("GET", "/api/admin/ops/summary", headers=bearer(operator))[0] == 200)

# ---------- Khách hàng chỉ thấy đơn của mình ----------
s, b, _ = call("POST", "/api/checkout", CHECKOUT,
               {"Idempotency-Key": str(uuid.uuid4()), "X-User-Id": "bob", **bearer(alice)})
check("alice checkout kèm header giả mạo X-User-Id: bob -> 201", s == 201, (s, b))
order_id = b["orderId"] if s == 201 else None
bob = dev_token("bob")
check("header giả mạo bị bỏ qua: đơn thuộc về alice (theo token)",
      call("GET", f"/api/orders/{order_id}", headers=bearer(alice))[0] == 200)
check("bob xem đơn của alice -> 404", call("GET", f"/api/orders/{order_id}", headers=bearer(bob))[0] == 404)
s, b, _ = call("GET", "/api/orders", headers=bearer(bob))
check("danh sách đơn của bob không chứa đơn của alice", s == 200 and order_id not in [o["orderId"] for o in b], b)
s, b, _ = call("GET", f"/api/admin/orders/{order_id}", headers=bearer(operator))
check("operator xem được đơn của alice", s == 200 and b["orderId"] == order_id, (s, b))

# ---------- CORS + endpoint dev ----------
s, _, h = call("OPTIONS", "/api/checkout", headers={"Origin": "http://localhost:5173",
                                                     "Access-Control-Request-Method": "POST",
                                                     "Access-Control-Request-Headers": "authorization,idempotency-key"})
check("CORS preflight từ FE (không token) -> 200, cho phép header Authorization",
      s == 200 and h.get("Access-Control-Allow-Origin") == "http://localhost:5173"
      and "authorization" in h.get("Access-Control-Allow-Headers", "").lower(), (s, h))
s, _, _ = call("OPTIONS", "/api/checkout", headers={"Origin": "http://evil.com", "Access-Control-Request-Method": "POST"})
check("CORS preflight từ trang lạ -> 403", s == 403, s)
check("/dev/token với user sai định dạng -> 400", call("POST", "/dev/token?user=Bad%20User")[0] == 400)

print(f"\n{'TẤT CẢ ĐỀU PASS' if failures == 0 else f'{failures} FAIL'}")
sys.exit(1 if failures else 0)
