# Bàn giao Backend

**Dự án:** Online Bookstore with Simulated Checkout (Final Project 04, Cloud Computing)
**Người bàn giao:** Hạnh (Backend Core / Distributed Logic)
**Người nhận:** Quân (Frontend), Tân (Cloud Infrastructure & DevOps), Tiên (QA, Reliability & Observability)
**Tài liệu kỹ thuật chi tiết:** [BACKEND.md](BACKEND.md)
**Cài đặt và chạy local:** [README.md](../README.md)

---

## 1. Trạng thái Backend

| Hạng mục | Trạng thái |
|---|---|
| Schema 8 bảng (6 bảng bắt buộc + `inventory_movement`, `processed_event`), seed 50 sách | Hoàn thành |
| API danh mục, checkout idempotent, đơn hàng của khách | Hoàn thành |
| API Store Operator (đơn, giao hàng, tồn kho, biến động kho, vận hành, đối soát) | Hoàn thành |
| Transactional Outbox + relay publish lên Pub/Sub | Hoàn thành |
| Worker xử lý sự kiện, chống trùng 3 lớp, dead-letter | Hoàn thành |
| Thanh toán mô phỏng (SUCCESS / FAIL / TIMEOUT) và đối soát | Hoàn thành |
| Xác thực JWT (Firebase / Identity Platform), phân quyền CUSTOMER / OPERATOR | Hoàn thành |
| Correlation ID xuyên suốt, log JSON, dữ liệu cho 4 chỉ số giám sát | Hoàn thành |
| Test tự động: Test 1, 2, 3, timeout, bảo mật (13 test) | Đạt 13/13 |
| Kiểm thử end-to-end với Pub/Sub emulator (5 script, 106 kiểm tra) | Đạt 106/106 |
| Dockerfile (một image cho 3 service Cloud Run) | Hoàn thành |
| Triển khai và kiểm thử trên GCP thật | **Chưa thực hiện**: phụ thuộc hạ tầng (mục 5) |

Swagger UI khi chạy local: `http://localhost:8080/swagger-ui.html`.

---

## 2. Đối chiếu bảng phân công với đề bài

Bảng phân công hiện tại đúng hướng. Một số điểm cần điều chỉnh hoặc bổ sung để khớp với đề bài:

### 2.1. Điểm cần sửa

| # | Nội dung trong bảng phân công | Yêu cầu của đề | Đề xuất |
|---|---|---|---|
| 1 | Frontend: "Mỗi lần bấm nút, tự động sinh ra một chuỗi ngẫu nhiên (UUID)" | Replay checkout với cùng key phải chỉ tạo một đơn | Sinh `Idempotency-Key` **một lần cho mỗi lượt thanh toán** và giữ nguyên khi người dùng bấm lại hoặc gửi lại do lỗi mạng. Sinh key mới mỗi lần bấm sẽ làm mất tác dụng idempotency |
| 2 | Diễn tập sự cố: "Tắt tiến trình xử lý ngầm (Worker)" | *"Stop the publisher after the database commit. Restart the outbox relay"* | Tắt **outbox relay**, không phải worker. Quy trình ở mục 6.3 |
| 3 | "Worker quét bảng OutboxEvent lấy các đơn chưa xử lý" | Outbox relay publish; consumer nhận message | Hai thành phần tách biệt: **relay** đọc `outbox_event` và publish lên Pub/Sub; **worker** nhận message từ Pub/Sub |
| 4 | Worker cập nhật đơn sang "Hoàn thành" hoặc "Hủy" | *"A payment timeout is unknown until reconciled, not automatically failed"* | Timeout chuyển đơn sang `PAYMENT_UNKNOWN` và chờ đối soát |
| 5 | Cloud: "lên giao diện web GCP bấm theo hướng dẫn" | *"Recreate infrastructure from code"* | Hạ tầng cần được mô tả bằng mã (Terraform hoặc script `gcloud` lưu trong repository), không chỉ thao tác trên Console |
| 6 | Cloud Run "API và Worker" | Relay là tiến trình riêng, có thể dừng độc lập | Triển khai **3 service**: api, worker, relay (mục 5.1) |

### 2.2. Hạng mục chưa có trong bảng phân công

| Hạng mục | Yêu cầu của đề | Người phụ trách đề xuất | Ghi chú |
|---|---|---|---|
| Kiểm tra token và quyền phía API | *"Validate tokens and permissions in the API"* | Hạnh | Đã hoàn thành ở Backend; Frontend chỉ tích hợp đăng nhập |
| Correlation ID, dữ liệu cho chỉ số giám sát | *"Record request or job IDs across logs, API responses and workers"* | Hạnh (ghi log), Tiên (dashboard, alert) | Backend đã ghi đủ trường; xem mục 6.2 |
| API cho Store Operator, lịch sử biến động kho | *"Order history and fulfillment dashboard"*, *"inventory movements"* | Hạnh | Đã hoàn thành |
| Hạ tầng dưới dạng mã (IaC) | *"Recreate infrastructure from code"* | Tân | |
| Tài khoản demo (khách hàng + operator) | *"Submit the application URL and demo accounts"* | Tân tạo, Quân ghi vào báo cáo | Tài khoản operator phải xác minh email |
| Sơ đồ kiến trúc thực tế (tên service, đường mạng, ranh giới tin cậy) | *"Draw the final deployed architecture..."* | Tân | Dùng mục 3 của BACKEND.md làm cơ sở |
| Thông số load test (thời lượng, mức đồng thời, warmup, dataset, tỷ lệ lỗi, p95) | *"For load tests, state duration, concurrency..."* | Tiên | |
| Kiểm tra HTTPS, quản lý bí mật, lưu trữ riêng tư | *"Verify HTTPS, secret handling and private storage"* | Tiên | |

### 2.3. Đề xuất cập nhật dòng của Hạnh trong bảng phân công

Bổ sung các hạng mục đã thực hiện để phần ghi công trong báo cáo chính xác:

- Kiểm tra JWT và phân quyền CUSTOMER / OPERATOR phía API (401 / 403).
- API Store Operator: danh sách đơn, giao hàng, tồn kho, biến động kho, vận hành, đối soát thủ công.
- Correlation ID xuyên suốt và log có cấu trúc cung cấp dữ liệu cho 4 chỉ số giám sát.
- Bộ test tự động cho Test 1, 2, 3 và Dockerfile.

---

## 3. Phân chia nội dung báo cáo

| Mục | Người viết | Nguồn từ Backend |
|---|---|---|
| 3.1 Thiết kế dữ liệu và quy tắc nghiệp vụ | Hạnh | BACKEND.md mục 5 |
| 3.2 Hiện thực Backend API | Hạnh | BACKEND.md mục 6.1, 7, 8 |
| 3.2 Frontend và giao diện quản trị | Quân | Mục 4 của tài liệu này |
| 3.3 Cơ chế tin cậy và bất đồng bộ | Hạnh | BACKEND.md mục 6.2 – 6.6 |
| 3.4 DevOps và CI/CD | Tân | Mục 5 của tài liệu này |
| 3.5 Giám sát và cảnh báo | Tiên | BACKEND.md mục 9; mục 6.2 của tài liệu này |
| 4.x Kiểm thử, diễn tập sự cố | Tiên | BACKEND.md mục 11; mục 6 của tài liệu này |

---

## 4. Bàn giao cho Frontend (Quân)

Đặc tả đầy đủ: BACKEND.md mục 7. Các điểm bắt buộc khi tích hợp:

### 4.1. Xác thực

- Đăng nhập bằng Firebase Authentication / Identity Platform; gửi ID token trong mọi request cần đăng nhập:
  ```js
  const token = await auth.currentUser.getIdToken();   // SDK tự làm mới token gần hết hạn
  fetch(`${API_URL}/api/checkout`, { method: "POST", headers: { Authorization: `Bearer ${token}`, ... } });
  ```
- Không gửi token cho API công khai (`GET /api/products`): token hết hạn vẫn bị trả 401.
- `401` → chuyển tới trang đăng nhập. `403` → thông báo không có quyền.
- Có thể ẩn menu quản trị với tài khoản không phải operator; Backend vẫn tự chặn API ở mọi trường hợp.
- Phát triển khi chưa có Firebase: lấy token tại `POST http://localhost:8080/dev/token?user=alice`
  (`user=operator` cho tài khoản Store Operator).

### 4.2. Checkout

1. Giỏ hàng lưu ở Frontend (ví dụ localStorage). Gộp số lượng nếu cùng một sách được thêm nhiều lần.
2. Khi mở trang thanh toán: `idempotencyKey = crypto.randomUUID()`. **Giữ nguyên** key khi người dùng bấm lại hoặc
   khi gửi lại do lỗi mạng; chỉ sinh key mới khi giỏ hàng thay đổi.
3. `POST /api/checkout` với header `Idempotency-Key` và body `{ items, paymentScenario }`; `paymentScenario` lấy từ
   lựa chọn kịch bản thanh toán (`SUCCESS`, `FAIL`, `TIMEOUT`).
4. Sau `201` hoặc `200`: gọi `GET /api/orders/{orderId}` mỗi 1–2 giây cho tới khi trạng thái là `PAID` hoặc
   `PAYMENT_FAILED`.

| Trạng thái đơn | Hiển thị đề xuất |
|---|---|
| `PENDING_PAYMENT` | Đang xử lý thanh toán |
| `PAYMENT_UNKNOWN` | Đang xác minh thanh toán (không hiển thị là lỗi; thường chuyển sang `PAID` sau 10–30 giây) |
| `PAID` | Thanh toán thành công |
| `PAYMENT_FAILED` | Thanh toán thất bại |
| `FULFILLED` | Đã giao hàng |

| Lỗi | Xử lý đề xuất |
|---|---|
| `409 OUT_OF_STOCK` | Thông báo sách `details.productId` không đủ hàng; tải lại danh mục |
| `409 IDEMPOTENCY_KEY_REUSED` | Lỗi phía Frontend (key dùng lại cho giỏ khác): sinh key mới |
| `400 VALIDATION_ERROR` | Hiển thị `details.errors` |

### 4.3. Hiển thị dữ liệu

- Tiền là số nguyên VND:
  `new Intl.NumberFormat("vi-VN", { style: "currency", currency: "VND" }).format(priceMinor)`.
- `available = 0` → vô hiệu hóa nút thêm vào giỏ.
- `imageUrl` hiện để trống → dùng ảnh mặc định.
- Thời gian trả về theo UTC (ISO-8601); chuyển sang giờ Việt Nam khi hiển thị.
- Khi có lỗi, hiển thị kèm `error.correlationId` để tra cứu log.

### 4.4. Trang Store Operator

| Màn hình | API |
|---|---|
| Lịch sử đơn hàng | `GET /api/admin/orders?status=` → chi tiết `GET /api/admin/orders/{id}` |
| Fulfillment dashboard | `GET /api/admin/orders?status=PAID` + nút "Đã giao" → `POST /api/admin/orders/{id}/fulfill` |
| Tồn kho | `GET /api/admin/inventory` (làm mới 3–5 giây/lần) |
| Biến động kho | `GET /api/admin/inventory/movements?productId=` |
| Thanh vận hành | `GET /api/admin/ops/summary` + nút "Đối soát ngay" → `POST /api/admin/reconcile` |

### 4.5. CORS

Origin của Frontend phải có trong `APP_CORS_ORIGINS` (local đã cho phép `http://localhost:5173` và
`http://localhost:3000`). Response cho phép đọc header `X-Correlation-Id` và `Idempotent-Replayed`.

---

## 5. Bàn giao cho Cloud Infrastructure & DevOps (Tân)

Chi tiết: BACKEND.md mục 3.2 và 10.

### 5.1. Mô hình triển khai

Một image cho cả ba service Cloud Run; khác nhau ở biến môi trường:

| Service | Biến riêng | Truy cập | Cấu hình |
|---|---|---|---|
| `bookstore-api` | – | Public (`--allow-unauthenticated`) | Autoscale |
| `bookstore-worker` | `APP_WORKER_ENABLED=true` | `--no-allow-unauthenticated` | Autoscale |
| `bookstore-relay` | `APP_RELAY_ENABLED=true`, `APP_RECONCILER_ENABLED=true`, `GCP_PROJECT_ID` | Ingress internal | `--min-instances=1 --no-cpu-throttling` |

Biến chung cho cả ba service: `SPRING_PROFILES_ACTIVE=cloud`, `DB_URL`, `DB_USER`, `DB_PASSWORD` (Secret Manager),
`APP_CORS_ORIGINS`, `AUTH_PROJECT_ID`, `APP_OPERATOR_EMAILS`. Bảng đầy đủ: BACKEND.md mục 10.2.

Thiếu biến bắt buộc thì ứng dụng dừng ngay khi khởi động và báo tên biến trong log. Không đặt
`SPRING_PROFILES_ACTIVE=cloud` thì ứng dụng dùng cấu hình local và không kết nối được cơ sở dữ liệu.

### 5.2. Hạ tầng cần tạo

| Hạng mục | Cấu hình |
|---|---|
| Cloud SQL | PostgreSQL 16, **Private IP**, không mở Public IP. Không cần chạy SQL thủ công: Flyway tạo bảng khi ứng dụng khởi động lần đầu. Nạp dữ liệu mẫu bằng `db/seed.sql` |
| Serverless VPC Access | Connector cho cả 3 service để truy cập Cloud SQL qua Private IP |
| Secret Manager | `DB_PASSWORD`, gắn vào service dưới dạng biến môi trường |
| Pub/Sub | Topic `order-events`, `order-events-dlq`. Push subscription `order-events-worker` → `https://<worker-url>/internal/pubsub/push`, bật OIDC token, ack deadline 30 giây, retry 1–10 giây, dead-letter `order-events-dlq`, max delivery attempts 5. Pull subscription `order-events-debug` trên `order-events` (dùng cho kiểm thử, mục 6.1) |
| Identity Platform | Bật provider Email/Password; tạo tài khoản demo khách hàng và operator; tài khoản operator phải xác minh email và có trong `APP_OPERATOR_EMAILS` |
| Artifact Registry | Repository Docker cho image Backend |

### 5.3. IAM (least privilege)

| Principal | Quyền |
|---|---|
| Service account của relay | `roles/pubsub.publisher` trên topic `order-events` |
| Service account dùng cho push subscription | `roles/run.invoker` trên service worker |
| Pub/Sub service agent (`service-<project-number>@gcp-sa-pubsub.iam.gserviceaccount.com`) | `roles/pubsub.publisher` trên `order-events-dlq`; `roles/pubsub.subscriber` trên `order-events-worker` |
| Service account của cả 3 service | `roles/cloudsql.client`, `roles/secretmanager.secretAccessor` trên secret DB |

Service API cần truy cập `www.googleapis.com` để tải khóa công khai xác thực JWT; nếu cấu hình egress toàn bộ qua VPC
thì cần Cloud NAT.

### 5.4. CI/CD

Pipeline đề xuất:

```
1. ./mvnw -B test                     # 13 test; thất bại thì dừng pipeline
2. docker build -t <region>-docker.pkg.dev/<project>/<repo>/bookstore-backend:<git-sha> backend/
3. docker push ...
4. gcloud run deploy bookstore-api / bookstore-worker / bookstore-relay --image ...:<git-sha>
```

- Bước test cần Docker (Testcontainers). GitHub Actions `ubuntu-latest` có sẵn Docker. Với Cloud Build, nên chạy
  bước test trên GitHub Actions và chỉ dùng Cloud Build cho build/deploy.
- Gắn tag image theo git SHA, không dùng `latest`.
- Rollback: `gcloud run services update-traffic <service> --to-revisions=<revision-trước>=100`.

### 5.5. Kiểm tra sau triển khai

| Kiểm tra | Kỳ vọng |
|---|---|
| `GET https://<api>/healthz` | `{"status":"ok"}` |
| `GET https://<api>/api/products` | 50 sách (sau khi seed) |
| `POST https://<api>/dev/token` | 404 |
| `GET https://<api>/api/orders` không token | 401 |
| Log trên Cloud Logging | JSON, có `severity`, `correlationId` |
| Đặt một đơn SUCCESS | Đơn chuyển `PAID` trong vài giây; log relay có `event=outbox_published` |

---

## 6. Bàn giao cho QA, Reliability & Observability (Tiên)

### 6.1. Ba bài test bắt buộc

| Test | Kiểm thử tự động | Kiểm thử trên môi trường đã triển khai |
|---|---|---|
| Test 1: hai khách mua cuốn cuối | `AcceptanceTest.test1_lastCopy_atMostOneSale` | Seed lại dữ liệu; gửi đồng thời nhiều request checkout `BOOK-001` (id 1) từ nhiều tài khoản: đúng 1 request `201`, còn lại `409 OUT_OF_STOCK` |
| Test 2: replay cùng key | `AcceptanceTest.test2_idempotentReplay_andConflict` | Gửi 2 lần cùng `Idempotency-Key` và cùng body: `201` rồi `200` cùng `orderId`; đổi body giữ key: `409 IDEMPOTENCY_KEY_REUSED` |
| Test 3: thanh toán thất bại | `AcceptanceTest.test3_paymentFailure_releasesOnce_evenOnReplay` | Checkout `BOOK-003` (id 3) số lượng 2, `paymentScenario=FAIL`: đơn `PAYMENT_FAILED`; `GET /api/admin/inventory` cho onHand 3, reserved 0; `GET /api/admin/inventory/movements?productId=3` có đúng 1 dòng `RELEASE` |

Để kiểm chứng phần "replay the failure event" trên GCP: mở subscription `order-events-debug` (pull) hoặc message trong
DLQ để lấy message `payment.failed`, publish lại vào topic `order-events` với đúng attributes; tồn kho và số dòng
`RELEASE` phải không đổi.

Lưu lại input, thời điểm, kết quả và log liên quan (lọc theo `correlationId`) cho từng test.

### 6.2. Dashboard và cảnh báo

Tạo log-based metric (Logging → Log-based Metrics):

| Chỉ số | Loại | Filter | Field giá trị | Biểu đồ |
|---|---|---|---|---|
| Checkout latency | Distribution | `jsonPayload.event="http_request" AND jsonPayload.path="/api/checkout" AND jsonPayload.method="POST"` | `jsonPayload.durationMs` | Percentile 95 |
| Outbox age | Distribution | `jsonPayload.event="outbox_stats"` | `jsonPayload.oldestAgeSeconds` | Max |
| Reconciliation backlog | Distribution | `jsonPayload.event="reconcile_stats"` | `jsonPayload.unknownCount` | Max |
| Inventory conflicts | Counter | `jsonPayload.event="inventory_conflict"` | – | Sum theo phút |

Cảnh báo gắn với failure drill:

| Thuộc tính | Giá trị đề xuất |
|---|---|
| Điều kiện | Outbox age (max) > 60 giây |
| Cửa sổ đánh giá | 2 phút |
| Độ trễ dự kiến | Khoảng 3–4 phút sau khi relay ngừng (log-based metric trễ 1–2 phút + cửa sổ 2 phút) |
| Kênh | Email |
| Hành động đầu tiên | Kiểm tra service `bookstore-relay` (đang tắt, lỗi quyền Pub/Sub); xem cột `last_error` trong `outbox_event`; khởi động lại relay; xác nhận `oldestAgeSeconds` về 0 |

Truy vết một đơn: Logs Explorer với `jsonPayload.correlationId="<id>"` (lấy từ header `X-Correlation-Id` của response
hoặc `error.correlationId`).

### 6.3. Diễn tập sự cố

1. Triển khai revision mới của `bookstore-relay` với `APP_RELAY_ENABLED=false`.
2. Đặt một đơn: API trả `201`; `GET /api/admin/ops/summary` cho `outboxPending` ≥ 1 và `outboxOldestAgeSeconds` tăng dần.
3. Chờ cảnh báo được gửi; ghi lại thời điểm.
4. Triển khai lại với `APP_RELAY_ENABLED=true`.
5. Xác nhận: đơn chuyển trạng thái cuối, `outboxPending` về 0, tồn kho đúng, không có đơn hoặc movement trùng
   (`GET /api/admin/inventory/movements`).

### 6.4. Kiểm thử bảo mật

Gọi trực tiếp API (Postman hoặc `scripts/smoke_auth.py` ở local):

| Kiểm tra | Kỳ vọng |
|---|---|
| Checkout không token | 401 `UNAUTHORIZED` |
| Token hết hạn / chữ ký sai | 401 |
| Tài khoản khách gọi `/api/admin/orders`, `/api/admin/reconcile` | 403 `FORBIDDEN` |
| Tài khoản operator gọi API quản trị | 200 |
| Xem đơn của người khác | 404 |
| HTTP thay vì HTTPS | Cloud Run chỉ phục vụ HTTPS |
| Cloud SQL | Không có Public IP |
| Mã nguồn, image | Không chứa mật khẩu; `DB_PASSWORD` lấy từ Secret Manager |

Trên môi trường GCP, lấy ID token thật từ Frontend (DevTools → Network → header Authorization).

### 6.5. Load test

Endpoint đề xuất: `GET /api/products` (đọc) và `POST /api/checkout` (ghi, mỗi request một `Idempotency-Key` mới, chọn
sách có tồn kho 20 và `paymentScenario=SUCCESS`). Báo cáo cần nêu thời lượng, mức đồng thời hoặc tốc độ request,
thời gian warmup, dữ liệu sử dụng, tỷ lệ lỗi và p95 từ mẫu xác định. Seed lại dữ liệu trước mỗi lần chạy.

### 6.6. Công cụ kiểm thử có sẵn

| Công cụ | Lệnh | Ghi chú |
|---|---|---|
| Test tự động | `./mvnw test` | Cần Docker |
| End-to-end | `python scripts/smoke_checkout.py`, `smoke_admin.py`, `smoke_auth.py`, `smoke_correlation.py`, `smoke_payment.py` | Chạy với ứng dụng local; lấy token qua `/dev/token` |
| Import Postman | `GET /v3/api-docs` | Đặc tả OpenAPI |

---

## 7. Lưu ý vận hành chung

- `db/seed.sql` **xóa toàn bộ** đơn hàng, tồn kho và outbox; chỉ dùng cho môi trường phát triển, demo và kiểm thử.
  Dừng relay và worker trước khi seed để tránh message còn tồn trên Pub/Sub trỏ tới đơn đã bị xóa.
- Không sửa file migration đã áp dụng (`V1__init.sql`); thay đổi schema bằng file `V2__*.sql` mới.
- Thay đổi hợp đồng API cần cập nhật BACKEND.md mục 7 và thông báo cho Frontend.
