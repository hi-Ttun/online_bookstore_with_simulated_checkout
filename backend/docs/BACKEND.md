# Tài liệu kỹ thuật Backend

**Dự án:** Online Bookstore with Simulated Checkout (Final Project 04, Cloud Computing)
**Phạm vi:** dịch vụ Backend (API, outbox relay, worker xử lý sự kiện, đối soát thanh toán)
**Phụ trách:** Hạnh (Backend Core / Distributed Logic)
**Nội dung báo cáo tương ứng:** Mục 3.1, 3.2 (phần Backend), 3.3

---

## Mục lục

1. [Tổng quan](#1-tổng-quan)
2. [Công nghệ sử dụng](#2-công-nghệ-sử-dụng)
3. [Kiến trúc](#3-kiến-trúc)
4. [Cấu trúc mã nguồn](#4-cấu-trúc-mã-nguồn)
5. [Thiết kế dữ liệu](#5-thiết-kế-dữ-liệu)
6. [Luồng nghiệp vụ và cơ chế tin cậy](#6-luồng-nghiệp-vụ-và-cơ-chế-tin-cậy)
7. [Đặc tả API](#7-đặc-tả-api)
8. [Bảo mật](#8-bảo-mật)
9. [Khả năng quan sát (Observability)](#9-khả-năng-quan-sát-observability)
10. [Cấu hình và triển khai](#10-cấu-hình-và-triển-khai)
11. [Kiểm thử](#11-kiểm-thử)
12. [Đối chiếu yêu cầu đề bài](#12-đối-chiếu-yêu-cầu-đề-bài)
13. [Giới hạn và ngoài phạm vi](#13-giới-hạn-và-ngoài-phạm-vi)

---

## 1. Tổng quan

Backend cung cấp REST API cho hai nhóm người dùng: **khách hàng** (xem danh mục, đặt hàng, theo dõi đơn) và
**Store Operator** (quản lý đơn, tồn kho, giao hàng, xử lý sự cố thanh toán). Trọng tâm thiết kế là tính đúng đắn
trong môi trường phân tán:

- Tạo đơn, giữ tồn kho và ghi sự kiện trong **một transaction** (Transactional Outbox).
- **Không bán vượt tồn kho** khi nhiều khách mua cùng lúc.
- **Idempotent checkout**: gửi lại cùng request chỉ tạo một đơn.
- **Xử lý sự kiện chịu được việc gửi lặp** (at-least-once delivery).
- **Thanh toán timeout được coi là "chưa xác định"** cho tới khi đối soát, không mặc định là thất bại.

---

## 2. Công nghệ sử dụng

| Thành phần | Lựa chọn | Ghi chú |
|---|---|---|
| Ngôn ngữ / runtime | Java 21 (LTS) | |
| Framework | Spring Boot 4.1 (Spring MVC, Spring Security) | |
| Cơ sở dữ liệu | PostgreSQL 16 (Cloud SQL) | |
| Truy cập dữ liệu | Spring `JdbcClient` + SQL tường minh | Xem mục 2.1 |
| Migration | Flyway | Schema tạo tự động khi ứng dụng khởi động |
| Messaging | Google Cloud Pub/Sub (`google-cloud-pubsub`) | Emulator cho môi trường local |
| Xác thực | OAuth2 Resource Server, JWT của Firebase Authentication / Identity Platform | |
| Tài liệu API | springdoc-openapi (Swagger UI) | `/swagger-ui.html` |
| Kiểm thử | JUnit 5, AssertJ, Testcontainers (PostgreSQL) | |
| Đóng gói | Docker multi-stage, Maven Wrapper | |

### 2.1. Lý do dùng JdbcClient thay cho JPA/Hibernate

Các bảo đảm tính đúng đắn quan trọng nhất của hệ thống phụ thuộc vào những câu lệnh SQL cụ thể của PostgreSQL:

| Yêu cầu | Câu lệnh |
|---|---|
| Chống bán vượt tồn kho | `UPDATE inventory SET reserved = reserved + :q WHERE product_id = :id AND on_hand - reserved >= :q` |
| Idempotency khi request trùng đồng thời | `INSERT ... ON CONFLICT (customer_id, idempotency_key) DO NOTHING RETURNING id` |
| Nhiều relay chạy song song không lấy trùng | `SELECT ... FOR UPDATE SKIP LOCKED` |
| Consumer chống trùng | `INSERT INTO processed_event ... ON CONFLICT DO NOTHING` |

JPA không biểu diễn được các câu lệnh này bằng entity; dùng JPA vẫn phải viết `@Query(nativeQuery = true)` cho toàn bộ
phần cốt lõi, đồng thời thêm các cơ chế không cần thiết (persistence context, dirty checking, lazy loading). Cách
"đọc - kiểm tra - ghi" thông thường của JPA còn gây race condition nếu không bổ sung optimistic locking và retry.
Viết SQL tường minh giúp logic đồng thời hiển thị rõ trong mã nguồn và kiểm chứng trực tiếp được.

Java Collections và Stream API được dùng xuyên suốt (`List`, `Map`, `Set`, `Collectors`) trong tầng service và
repository; dữ liệu truyền giữa các tầng là Java `record` bất biến.

---

## 3. Kiến trúc

### 3.1. Các thành phần

```
                    HTTPS + JWT
   Frontend  ──────────────────────►  API (Cloud Run, public)
                                        │  1 transaction: orders + order_line + inventory + outbox_event
                                        ▼
                               Cloud SQL PostgreSQL (Private IP, qua Serverless VPC Access)
                                        ▲                       │ đọc outbox chưa gửi
                     ghi kết quả,       │                       ▼
                     chống trùng        │              Outbox Relay (Cloud Run, chạy nền)
                                        │                       │ publish
                                        │                       ▼
                                        │              Pub/Sub topic: order-events
                                        │                       │ push + OIDC token        sau 5 lần lỗi
                                        │                       ▼                         ────────────►  order-events-dlq
                                        └────────────── Worker (Cloud Run, require auth)
                                                                │
                                                                ▼
                                                   Cổng thanh toán mô phỏng
                                        Reconciler (định kỳ / Operator kích hoạt): đối soát thanh toán UNKNOWN
```

### 3.2. Một image, nhiều vai trò

Toàn bộ Backend là một codebase và một Docker image. Vai trò của mỗi service Cloud Run được quyết định bằng biến môi trường:

| Service | Biến bật | Truy cập | Ghi chú |
|---|---|---|---|
| `bookstore-api` | (mặc định) | Public HTTPS | API cho Frontend |
| `bookstore-worker` | `APP_WORKER_ENABLED=true` | Require authentication | Endpoint `POST /internal/pubsub/push` chỉ được đăng ký ở service này |
| `bookstore-relay` | `APP_RELAY_ENABLED=true`, `APP_RECONCILER_ENABLED=true` | Không cần ingress | `min-instances=1`, CPU always allocated |

Ở môi trường local, cả bốn vai trò chạy trong cùng một tiến trình.

---

## 4. Cấu trúc mã nguồn

```
backend/
├── src/main/java/vn/edu/uth/bookstore/
│   ├── BookstoreApplication.java
│   ├── config/        SecurityConfig, LocalAuthConfig, PubSubConfig, WebConfig (CORS), OpenApiConfig
│   ├── controller/    ProductController, OrderController, AdminController,
│   │                  PubSubPushController, DevTokenController, HealthController
│   ├── dto/           request/response record (CheckoutRequest, OrderReceipt, ApiErrorResponse, ...)
│   ├── exception/     ApiException, GlobalExceptionHandler
│   ├── logging/       CorrelationIdFilter, CloudLoggingFormatter
│   ├── model/         OrderStatus, PaymentStatus, PaymentScenario, MovementType, EventType
│   ├── repository/    truy cập dữ liệu bằng JdbcClient (một lớp cho mỗi nhóm bảng)
│   ├── scheduler/     OutboxRelayJob, OutboxStatsJob, ReconciliationJob
│   └── service/       CheckoutService, OrderEventService, ReconciliationService, SimulatedPaymentGateway
├── src/main/resources/
│   ├── application.properties          cấu hình chung (đọc từ biến môi trường)
│   ├── application-local.properties    môi trường phát triển (docker-compose)
│   ├── application-cloud.properties    Cloud Run (log JSON, Firebase JWT)
│   └── db/migration/V1__init.sql       schema
├── src/test/java/vn/edu/uth/bookstore/  integration test và unit test
├── db/seed.sql                          dữ liệu mẫu 50 sách
├── scripts/                             kiểm thử end-to-end trên môi trường đang chạy
├── docker-compose.yml                   PostgreSQL + Pub/Sub emulator cho local
└── Dockerfile
```

Phân tầng: `controller` (HTTP) → `service` (nghiệp vụ, transaction) → `repository` (SQL). Controller chỉ đọc dữ liệu
đơn giản có thể gọi repository trực tiếp.

---

## 5. Thiết kế dữ liệu

### 5.1. Các bảng

Sáu bảng bắt buộc theo đề bài và hai bảng bổ sung phục vụ yêu cầu output và chống trùng.

| Bảng | Mục đích | Ràng buộc chính |
|---|---|---|
| `product` | Sách | `sku` UNIQUE; `price_minor BIGINT >= 0` |
| `inventory` | Tồn kho | `on_hand >= 0`, `reserved >= 0`, `CHECK (reserved <= on_hand)` |
| `orders` | Đơn hàng | `UNIQUE (customer_id, idempotency_key)`; `status`, `payment_scenario` giới hạn bằng CHECK |
| `order_line` | Dòng đơn, lưu giá tại thời điểm mua | `UNIQUE (order_id, product_id)`; `quantity > 0` |
| `payment_attempt` | Mỗi lần gọi cổng thanh toán | `operation_key` UNIQUE |
| `outbox_event` | Sự kiện chờ publish | `published_at IS NULL` = chưa gửi; index một phần trên bản ghi chưa gửi |
| `inventory_movement` | Lịch sử biến động kho (output bắt buộc) | `UNIQUE (order_id, product_id, kind)` |
| `processed_event` | Sự kiện consumer đã xử lý | `PRIMARY KEY (event_id, consumer)` |

Tiền tệ lưu dạng **số nguyên theo đơn vị nhỏ nhất** (`BIGINT`; VND tính bằng đồng). Phép nhân/cộng tiền dùng
`Math.multiplyExact` / `Math.addExact` để phát hiện tràn số. Tên bảng `orders` tránh trùng từ khóa SQL `ORDER`.
Thời gian lưu `TIMESTAMPTZ`, ứng dụng chạy UTC.

### 5.2. Trạng thái đơn hàng

```
                     payment.succeeded
 PENDING_PAYMENT ─────────────────────────► PAID ──── fulfill ────► FULFILLED
       │   │
       │   │         payment.failed
       │   └──────────────────────────────► PAYMENT_FAILED
       │ timeout
       ▼
 PAYMENT_UNKNOWN ──── đối soát ────► payment.succeeded → PAID
                                  └► payment.failed    → PAYMENT_FAILED
```

Mọi chuyển trạng thái đều là câu UPDATE có điều kiện trên trạng thái hiện tại
(`... WHERE id = :id AND status IN (...)`); sự kiện đến lặp hoặc đến muộn chỉ cập nhật 0 dòng và bị bỏ qua.

### 5.3. Quy tắc tồn kho

Số lượng còn bán được = `on_hand - reserved`.

| Thời điểm | on_hand | reserved | Movement |
|---|---|---|---|
| Checkout thành công | không đổi | + q | `RESERVE` |
| Thanh toán thành công | − q | − q | `COMMIT` |
| Thanh toán thất bại | không đổi | − q | `RELEASE` |
| Thanh toán UNKNOWN | không đổi | không đổi (tiếp tục giữ) | – |

### 5.4. Dữ liệu mẫu

`db/seed.sql` xóa toàn bộ dữ liệu nghiệp vụ và nạp lại 50 sách; chạy nhiều lần cho cùng một kết quả.
Một số sách có tồn kho được chọn trước để phục vụ kiểm thử:

| SKU | Tồn kho | Kịch bản | Kết quả kỳ vọng |
|---|---|---|---|
| `BOOK-001` | 1 | Test 1: nhiều khách mua cuốn cuối | Đúng 1 đơn thành công, các request khác nhận `409 OUT_OF_STOCK`; sau thanh toán on_hand = 0 |
| `BOOK-002` | 5 | Test 2: idempotency | Gửi lặp cùng key chỉ tạo 1 đơn, còn bán được 4 |
| `BOOK-003` | 3 | Test 3: thanh toán thất bại | Đơn `PAYMENT_FAILED`, kho trở về on_hand 3 / reserved 0, đúng 1 movement `RELEASE` kể cả khi phát lại sự kiện |
| `BOOK-004` | 10 | Timeout và đối soát | `PAYMENT_UNKNOWN` → `PAID`, on_hand 9 |
| `BOOK-005` … `BOOK-050` | 20 | Mua bình thường | – |

Kiểm tra nhanh sau khi seed: tổng 50 sách, tổng tồn kho 939.

---

## 6. Luồng nghiệp vụ và cơ chế tin cậy

### 6.1. Checkout (`CheckoutService`)

Toàn bộ các bước sau nằm trong một transaction; mọi exception đều rollback.

1. Sắp các dòng theo `productId` tăng dần (mọi giao dịch khóa tồn kho cùng thứ tự để tránh deadlock); từ chối sách trùng.
2. Tính `request_hash` = SHA-256 của nội dung chuẩn hóa, ví dụ `1x2,5x1|SUCCESS`.
3. Đọc giá hiện hành, tính tổng tiền.
4. `INSERT INTO orders ... ON CONFLICT DO NOTHING`. Nếu key đã tồn tại: hash trùng thì trả lại đơn cũ (`200`,
   `Idempotent-Replayed: true`); hash khác thì trả `409 IDEMPOTENCY_KEY_REUSED`.
5. Giữ tồn kho từng sách bằng UPDATE có điều kiện; không đủ hàng thì trả `409 OUT_OF_STOCK`, rollback toàn bộ đơn.
6. Ghi `order_line`, `inventory_movement (RESERVE)` và `outbox_event (order.created)`.
7. Commit, trả `201` kèm biên nhận ở trạng thái `PENDING_PAYMENT`.

**Chống bán vượt tồn kho.** Điều kiện tồn kho được kiểm tra trong chính câu UPDATE. PostgreSQL khóa dòng khi cập
nhật, nên các giao dịch mua cùng một sách được tuần tự hóa; giao dịch đến sau thấy điều kiện không còn thỏa và nhận
0 dòng. Ràng buộc `CHECK (reserved <= on_hand)` là lớp bảo vệ cuối ở mức cơ sở dữ liệu.

**Idempotency đồng thời.** Khi hai request cùng key đến cùng lúc, câu INSERT của request sau phải chờ request trước
commit hoặc rollback, rồi mới quyết định chèn hay bỏ qua. Nếu request đầu thất bại (ví dụ hết hàng), key không được
lưu và lần gửi lại sẽ được xử lý như request mới.

### 6.2. Outbox relay (`OutboxRelayJob`)

Chạy mỗi giây (`fixedDelay`, không chồng chu kỳ):

1. `SELECT ... WHERE published_at IS NULL ORDER BY created_at LIMIT 100 FOR UPDATE SKIP LOCKED`.
2. Publish từng sự kiện lên topic `order-events`, chờ Pub/Sub xác nhận (tối đa 10 giây).
3. Thành công: ghi `published_at`. Lỗi: tăng `publish_attempts`, ghi `last_error`, dừng lô để giữ thứ tự và thử lại ở chu kỳ sau.

Message gồm `data` (payload JSON) và các attribute `event_id`, `event_type`, `aggregate_id` (orderId), `correlation_id`.

Relay bảo đảm **at-least-once**: nếu tiến trình dừng sau khi Pub/Sub nhận message nhưng trước khi ghi
`published_at`, sự kiện sẽ được gửi lại. Consumer chịu trách nhiệm chống trùng (mục 6.3).

### 6.3. Worker (`PubSubPushController`, `OrderEventService`)

Pub/Sub push subscription gọi `POST /internal/pubsub/push`. Worker trả `204` (ack) **chỉ sau khi transaction đã commit**;
lỗi trả `500` để Pub/Sub gửi lại (backoff 1–10 giây), message không hợp lệ trả `400`. Sau 5 lần thất bại, message
được chuyển sang dead-letter topic `order-events-dlq`.

| Sự kiện | Xử lý |
|---|---|
| `order.created` | Gọi cổng thanh toán với `operation_key = order:{id}:attempt:1`. SUCCESS/FAIL: ghi `payment_attempt` và outbox `payment.succeeded` / `payment.failed`. TIMEOUT: ghi `payment_attempt (UNKNOWN)`, đơn chuyển `PAYMENT_UNKNOWN`, không phát sự kiện |
| `payment.succeeded` | Đơn → `PAID`; xuất kho; movement `COMMIT` |
| `payment.failed` | Đơn → `PAYMENT_FAILED`; trả lại phần giữ chỗ; movement `RELEASE` |

**Ba lớp chống trùng:**

| Lớp | Cơ chế | Ngăn chặn |
|---|---|---|
| 1 | `processed_event (event_id, consumer)`, ghi **cùng transaction** với thay đổi dữ liệu | Pub/Sub gửi lại cùng một message |
| 2 | Chuyển trạng thái có điều kiện | Sự kiện khác (event_id mới) cho đơn đã chốt; sự kiện đến muộn |
| 3 | `UNIQUE (order_id, product_id, kind)` trên `inventory_movement` | Lớp bảo vệ cuối ở mức cơ sở dữ liệu |

Việc ghi `processed_event` cùng transaction là bắt buộc: nếu ghi riêng và tiến trình dừng giữa hai bước, lần gửi lại
sẽ bị bỏ qua trong khi tồn kho chưa được cập nhật.

### 6.4. Thanh toán mô phỏng (`SimulatedPaymentGateway`)

Kết quả chỉ phụ thuộc kịch bản khách chọn khi checkout, nên có thể tái lập:

| Kịch bản | `charge()` | `lookup()` khi đối soát |
|---|---|---|
| `SUCCESS` | SUCCEEDED | SUCCEEDED |
| `FAIL` | FAILED (`card_declined`) | FAILED |
| `TIMEOUT` | không phản hồi (exception) | SUCCEEDED: giao dịch thực tế đã trừ tiền |

Kịch bản TIMEOUT minh họa lý do không được coi timeout là thất bại: hủy đơn ngay sẽ khiến khách mất tiền mà không có hàng.

### 6.5. Đối soát (`ReconciliationService`)

Chạy định kỳ (mặc định 30 giây) hoặc do Operator kích hoạt qua `POST /api/admin/reconcile`. Với mỗi
`payment_attempt` ở trạng thái UNKNOWN đã chờ ít nhất 10 giây: tra cứu kết quả theo `operation_key`, cập nhật
`payment_attempt` và ghi outbox `payment.succeeded` / `payment.failed` trong một transaction. Việc chốt đơn sau đó
đi qua worker như luồng thông thường, nên logic xuất kho và trả kho chỉ tồn tại ở một nơi.

### 6.6. Diễn tập sự cố (failure drill)

Yêu cầu của đề: dừng publisher sau khi commit, khởi động lại relay, chứng minh sự kiện được xử lý mà không có tác dụng lặp.

1. Triển khai revision mới của `bookstore-relay` với `APP_RELAY_ENABLED=false`. Instance vẫn chạy và tiếp tục ghi
   `event=outbox_stats`.
2. Đặt đơn: API trả `201`, sự kiện nằm trong `outbox_event` với `published_at = NULL`; `oldestAgeSeconds` tăng dần
   và cảnh báo được kích hoạt.
3. Bật lại `APP_RELAY_ENABLED=true`: sự kiện tồn đọng được publish, đơn được xử lý; các sự kiện đã gửi trước đó
   không bị gửi lại.

Kết quả đã kiểm chứng ở môi trường local: trong thời gian relay tắt, `oldestAgeSeconds` tăng 3 → 8 → 13 → 18 giây;
sau khi bật lại, đúng một message được publish. Khi Pub/Sub ngừng hoạt động, API vẫn nhận đơn (`201`), relay ghi
`last_error`, và sự kiện được publish thành công ngay khi Pub/Sub hoạt động trở lại.

---

## 7. Đặc tả API

Swagger UI: `/swagger-ui.html`; đặc tả OpenAPI: `/v3/api-docs`.

### 7.1. Quy ước chung

| Mục | Quy ước |
|---|---|
| Xác thực | `Authorization: Bearer <JWT>` cho mọi API trừ danh mục, health check và Swagger |
| Correlation ID | `X-Correlation-Id` (tùy chọn); server luôn trả lại header này, tự sinh UUID nếu không có |
| Tiền | Số nguyên đơn vị nhỏ nhất: `"priceMinor": 85000, "currency": "VND"` |
| Thời gian | ISO-8601, UTC |
| Lỗi | `{"error": {"code", "message", "correlationId", "details"}}` |

### 7.2. Danh mục (công khai)

| Method | Path | Mô tả |
|---|---|---|
| GET | `/api/products?page=0&size=50` | Danh sách sách (`size` 1–100): `id, sku, title, author, priceMinor, currency, imageUrl, available` |
| GET | `/api/products/{id}` | Chi tiết sách; không có: `404 PRODUCT_NOT_FOUND` |
| GET | `/healthz` | Health check |

### 7.3. Khách hàng (đã đăng nhập)

**POST `/api/checkout`**

- Header bắt buộc: `Idempotency-Key` (tối đa 100 ký tự, khuyến nghị UUID). Sinh **một lần cho mỗi lượt thanh toán**
  và giữ nguyên khi gửi lại.
- Body:
  ```json
  { "items": [ { "productId": 1, "quantity": 2 } ], "paymentScenario": "SUCCESS" }
  ```
  `paymentScenario` ∈ `SUCCESS | FAIL | TIMEOUT`; 1–20 dòng; `quantity` 1–100; mỗi `productId` xuất hiện một lần.

| Kết quả | HTTP | Mã lỗi |
|---|---|---|
| Tạo đơn mới | 201 | – |
| Gửi lại cùng key và cùng nội dung | 200, header `Idempotent-Replayed: true` | – |
| Cùng key, khác nội dung | 409 | `IDEMPOTENCY_KEY_REUSED` |
| Không đủ hàng (không tạo đơn) | 409 | `OUT_OF_STOCK` (`details.productId`) |
| Sách không tồn tại | 404 | `PRODUCT_NOT_FOUND` |
| Dữ liệu không hợp lệ, thiếu key | 400 | `VALIDATION_ERROR` (`details.errors`) |
| Chưa đăng nhập | 401 | `UNAUTHORIZED` |

Biên nhận (`OrderReceipt`):
```json
{
  "orderId": "8f1c…", "status": "PENDING_PAYMENT", "paymentScenario": "SUCCESS",
  "totalMinor": 170000, "currency": "VND", "createdAt": "2026-11-14T10:00:00Z",
  "lines": [ { "productId": 1, "title": "Pride and Prejudice", "quantity": 2,
               "unitPriceMinor": 85000, "lineTotalMinor": 170000 } ],
  "paymentAttempts": [ { "status": "SUCCEEDED", "createdAt": "…", "resolvedAt": "…" } ]
}
```

Thanh toán xử lý bất đồng bộ; trạng thái cuối cùng có được qua `GET /api/orders/{id}`.

| Method | Path | Mô tả |
|---|---|---|
| GET | `/api/orders` | 100 đơn gần nhất của người dùng hiện tại |
| GET | `/api/orders/{id}` | Biên nhận; đơn của người khác: `404 ORDER_NOT_FOUND` |

### 7.4. Store Operator (role OPERATOR)

| Method | Path | Mô tả |
|---|---|---|
| GET | `/api/admin/orders?status=` | 200 đơn mới nhất: `orderId, customerId, status, paymentScenario, totalMinor, currency, itemCount, createdAt, fulfilledAt`. `status=PAID` là danh sách cần giao |
| GET | `/api/admin/orders/{id}` | Biên nhận của đơn bất kỳ |
| POST | `/api/admin/orders/{id}/fulfill` | `PAID → FULFILLED`; gọi lại trên đơn đã FULFILLED vẫn `200`; trạng thái khác: `409 INVALID_STATE` (`details.status`) |
| GET | `/api/admin/inventory` | `productId, sku, title, onHand, reserved, available, updatedAt` |
| GET | `/api/admin/inventory/movements?productId=` | 200 biến động mới nhất: `id, productId, sku, orderId, kind, quantity, createdAt` |
| GET | `/api/admin/ops/summary` | `outboxPending, outboxOldestAgeSeconds, paymentsUnknown, ordersByStatus` |
| POST | `/api/admin/reconcile` | Đối soát ngay; trả `{ "resolved": N }` |

### 7.5. Nội bộ

| Method | Path | Mô tả |
|---|---|---|
| POST | `/internal/pubsub/push` | Chỉ có trên service worker; được Cloud Run IAM bảo vệ |
| POST | `/dev/token?user=` | Chỉ có ở profile local: phát token phát triển |

---

## 8. Bảo mật

| Hạng mục | Thực hiện |
|---|---|
| Xác thực | JWT của Firebase Authentication / Identity Platform. API tự kiểm tra chữ ký (khóa công khai của Google), `iss = https://securetoken.google.com/<AUTH_PROJECT_ID>`, `aud`, thời hạn |
| Định danh khách hàng | Claim `sub` của token; không tin bất kỳ header định danh nào do client gửi |
| Phân quyền | Mọi người dùng đã xác thực có role CUSTOMER; thêm OPERATOR khi `email` thuộc `APP_OPERATOR_EMAILS` **và** `email_verified = true` (Firebase cho phép tự đăng ký với email bất kỳ) |
| Phân quyền theo endpoint | `/api/admin/**` yêu cầu OPERATOR; checkout và đơn hàng yêu cầu đăng nhập; danh mục công khai |
| Dữ liệu riêng tư | Khách chỉ đọc được đơn của chính mình (truy vấn lọc theo `customer_id`) |
| Worker | Endpoint chỉ được đăng ký khi `APP_WORKER_ENABLED=true`; service worker bật "Require authentication", Pub/Sub gửi OIDC token |
| CSRF / session | Tắt CSRF, không dùng session (API stateless dùng bearer token) |
| CORS | Chỉ các origin trong `APP_CORS_ORIGINS`; header `Authorization`, `Idempotency-Key`, `X-Correlation-Id` |
| Bí mật | Không có bí mật trong mã nguồn hay image; `DB_PASSWORD` lấy từ Secret Manager. Thiếu biến cấu hình thì ứng dụng dừng ngay khi khởi động |
| Token phát triển | `POST /dev/token` và khóa HS256 chỉ tồn tại ở profile local; profile cloud trả 404 và từ chối token này (đã kiểm chứng) |
| Log | Không ghi token, header Authorization hay mật khẩu; Correlation ID từ client được lọc ký tự để chống log injection |
| Container | Chạy bằng user không có quyền root |

Mã lỗi xác thực: `401 UNAUTHORIZED` (thiếu, sai, hết hạn token) và `403 FORBIDDEN` (không đủ quyền), cùng định dạng lỗi chung.

---

## 9. Khả năng quan sát (Observability)

### 9.1. Correlation ID

`CorrelationIdFilter` gán Correlation ID cho mọi request (lấy từ header hoặc sinh mới), đặt vào MDC và trả lại trong
header response và body lỗi. ID được lưu vào `orders.correlation_id`, `outbox_event.correlation_id`, gửi kèm message
Pub/Sub và được worker, reconciler khôi phục. Toàn bộ log của một đơn hàng, từ API qua relay, worker tới reconciler,
dùng chung một ID.

Ví dụ chuỗi log của một đơn TIMEOUT (lọc theo correlationId):
```
checkout_created → outbox_published(order.created) → worker_received(order.created)
→ payment_attempted(UNKNOWN) → payment_reconciled → outbox_published(payment.succeeded)
→ worker_received(payment.succeeded) → order_settled(PAID)
```

### 9.2. Định dạng log

- Profile local: log văn bản có tiền tố `[correlationId]`.
- Profile cloud: log JSON một dòng (`CloudLoggingFormatter`) gồm `severity` (theo chuẩn Cloud Logging), `time`, `message`,
  `logger`, `thread`, các trường MDC (`correlationId`, `orderId`, `eventId`), các key-value nghiệp vụ và `stack_trace`.

### 9.3. Dữ liệu cho bốn chỉ số giám sát

| Chỉ số (đề bài) | Dòng log | Trường giá trị | Metric đề xuất |
|---|---|---|---|
| Checkout latency | `event=http_request`, `path=/api/checkout`, `method=POST` | `durationMs`, `status` | Distribution, p95 |
| Outbox age | `event=outbox_stats` (30 giây/lần, chạy cả khi relay tắt) | `oldestAgeSeconds`, `pending` | Distribution, max |
| Reconciliation backlog | `event=reconcile_stats` | `unknownCount`, `resolved` | Distribution, max |
| Inventory conflicts | `event=inventory_conflict` | `productId` | Counter |

Các trường giá trị là số trong `jsonPayload`, dùng trực tiếp được cho log-based metric. Cảnh báo đề xuất gắn với
failure drill: `oldestAgeSeconds > 60` trong 2 phút. `GET /api/admin/ops/summary` cung cấp nhanh ba trong bốn chỉ số.

---

## 10. Cấu hình và triển khai

### 10.1. Profile

| Profile | Kích hoạt | Đặc điểm |
|---|---|---|
| `local` | Mặc định khi không đặt `SPRING_PROFILES_ACTIVE` | DB và Pub/Sub emulator từ docker-compose; token phát triển; mọi vai trò chạy chung |
| `cloud` | `SPRING_PROFILES_ACTIVE=cloud` | Firebase JWT; log JSON; cấu hình hoàn toàn từ biến môi trường |

### 10.2. Biến môi trường (profile cloud)

| Biến | Bắt buộc | Ví dụ / mặc định | Mô tả |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | ✔ | `cloud` | |
| `PORT` | | do Cloud Run đặt | |
| `DB_URL` | ✔ | `jdbc:postgresql://10.x.x.x:5432/bookstore` | Private IP của Cloud SQL |
| `DB_USER` | ✔ | `bookstore` | |
| `DB_PASSWORD` | ✔ | (Secret Manager) | |
| `DB_POOL_SIZE` | | `5` | Số kết nối tối đa mỗi instance |
| `APP_CORS_ORIGINS` | ✔ | `https://<frontend>` | Phân tách bằng dấu phẩy |
| `AUTH_PROJECT_ID` | ✔ | `<firebase-project-id>` | Dùng kiểm tra `iss`, `aud` của JWT |
| `APP_OPERATOR_EMAILS` | ✔ | `operator@example.com` | Phân tách bằng dấu phẩy |
| `GCP_PROJECT_ID` | relay | `<gcp-project-id>` | Project chứa topic Pub/Sub |
| `PUBSUB_TOPIC` | | `order-events` | |
| `APP_RELAY_ENABLED` | | `false` | `true` trên service relay |
| `APP_RELAY_INTERVAL_MS` | | `1000` | |
| `APP_WORKER_ENABLED` | | `false` | `true` trên service worker |
| `APP_RECONCILER_ENABLED` | | `false` | `true` trên một service chạy nền |
| `APP_RECONCILER_INTERVAL_MS` | | `30000` | |
| `APP_RECONCILER_MIN_AGE_SECONDS` | | `10` | |
| `APP_OUTBOX_STATS_ENABLED` | | `true` | |
| `APP_OUTBOX_STATS_INTERVAL_MS` | | `30000` | |

### 10.3. Yêu cầu hạ tầng GCP

| Hạng mục | Cấu hình |
|---|---|
| Cloud SQL | PostgreSQL 16, Private IP; Cloud Run kết nối qua Serverless VPC Access. Bảng do Flyway tạo khi khởi động |
| Pub/Sub | Topic `order-events`, `order-events-dlq`. Subscription `order-events-worker`: push tới `https://<worker>/internal/pubsub/push` kèm OIDC token, ack deadline 30 giây, retry 1–10 giây, dead-letter `order-events-dlq` sau 5 lần |
| IAM | Relay: `roles/pubsub.publisher` trên topic. Service account của push subscription: `roles/run.invoker` trên worker. Pub/Sub service agent: `roles/pubsub.publisher` trên DLQ và `roles/pubsub.subscriber` trên subscription |
| Identity Platform | Bật provider Email/Password; tài khoản operator phải xác minh email |
| Mạng | API cần truy cập `www.googleapis.com` để tải khóa công khai (cần Cloud NAT nếu toàn bộ egress đi qua VPC) |

### 10.4. Build và chạy

```bash
./mvnw -B package                        # build + chạy toàn bộ test (cần Docker)
docker build -t bookstore-backend .      # image Cloud Run
```

Môi trường local: xem `README.md`.

---

## 11. Kiểm thử

### 11.1. Kiểm thử tự động (`./mvnw test`)

Chạy trên PostgreSQL thật do Testcontainers khởi tạo; mỗi test bắt đầu từ dữ liệu seed.
Lớp `IntegrationTestBase` thay vai trò relay và Pub/Sub bằng cách gọi trực tiếp endpoint worker, nên kết quả không phụ
thuộc thời gian.

| Lớp | Test | Kiểm tra |
|---|---|---|
| `AcceptanceTest` | Test 1 | 10 khách gửi đồng thời mua `BOOK-001` (1 cuốn): đúng 1 × 201, 9 × 409; 1 đơn, 1 sự kiện outbox; sau thanh toán kho 0/0 |
| | Test 2 | Cùng key cùng nội dung: 201 rồi 200 cùng orderId; khác nội dung: 409; 5 request cùng key đồng thời: 1 × 201 + 4 × 200 |
| | Test 3 | FAIL → `PAYMENT_FAILED`, kho 3/0, 1 `RELEASE`; phát lại `payment.failed` 3 lần, thêm sự kiện lỗi mới và `payment.succeeded` đến muộn: kho không đổi, vẫn 1 `RELEASE`, 0 `COMMIT`; phát lại `order.created` không tạo thanh toán thứ hai |
| | Timeout | `PAYMENT_UNKNOWN`, kho tiếp tục giữ; đối soát trước thời gian tối thiểu không thay đổi gì; sau đó → `PAID`, kho 9/0 |
| `SecurityTest` | 5 test | Danh mục công khai; 401 khi thiếu hoặc hết hạn token (có correlationId); 403 khi khách gọi API operator; operator 200; đơn của người khác 404 |
| `CheckoutServiceTest` | 4 test | Tính ổn định và khả năng phân biệt của `request_hash` |

Kết quả: **13/13 test đạt**.

### 11.2. Kiểm thử end-to-end (`scripts/`)

Chạy trên ứng dụng đang hoạt động, bao gồm relay, Pub/Sub emulator và worker thật. Chỉ dùng thư viện chuẩn của Python.

| Script | Số kiểm tra | Nội dung |
|---|---|---|
| `smoke_checkout.py` | 20 | Idempotency, 20 khách tranh 1 cuốn, rollback đơn nhiều sách, validation, quyền xem đơn |
| `smoke_payment.py` | 20 | SUCCESS / FAIL (Test 3 và phát lại) / TIMEOUT qua Pub/Sub thật; message lỗi |
| `smoke_admin.py` | 26 | Toàn bộ API Operator, fulfill, đối soát thủ công |
| `smoke_auth.py` | 24 | 401/403, token hết hạn, token ký sai khóa, token `alg=none`, header định danh giả mạo, CORS |
| `smoke_correlation.py` | 16 | Correlation ID qua toàn bộ chuỗi xử lý, định dạng log JSON, trường số cho metric, không ghi token vào log |

Kết quả: **106/106 kiểm tra đạt**, log không có lỗi. `smoke_payment.py` đọc tồn kho qua psql nên chỉ chạy ở local;
các script còn lại nhận URL môi trường làm tham số.

Các kiểm chứng bổ sung đã thực hiện: diễn tập tắt/bật relay (mục 6.6), Pub/Sub ngừng hoạt động và tự phục hồi,
message lỗi vào dead-letter sau 5 lần, profile cloud từ chối token phát triển, Docker image chạy ở vai trò API và relay.

---

## 12. Đối chiếu yêu cầu đề bài

| Yêu cầu trong đề | Hiện thực |
|---|---|
| Data model: Product, Inventory, Order, OrderLine, PaymentAttempt, OutboxEvent | 6 bảng tương ứng + `inventory_movement`, `processed_event` (mục 5.1) |
| Store money in integer minor units | `BIGINT`, phép tính dùng `Math.*Exact` |
| Repeatable seed script và bộ dữ liệu có kết quả kỳ vọng | `db/seed.sql`, bảng kỳ vọng mục 5.4 |
| Product catalogue and shopping cart | API danh mục; giỏ hàng giữ ở Frontend và gửi lên khi checkout |
| Idempotent checkout and stock reservation | Mục 6.1 |
| Order history and fulfillment dashboard | `GET /api/orders`, `GET /api/admin/orders`, `POST .../fulfill` |
| Outputs: order receipts, inventory movements, payment-attempt states, fulfillment list | `OrderReceipt`, `/inventory/movements`, `paymentAttempts`, `/admin/orders?status=PAID` |
| Create the order, stock reservation and outgoing event atomically; outbox relay publishes later | Mục 6.1, 6.2 |
| A payment timeout is unknown until reconciled | `PAYMENT_UNKNOWN` + đối soát (mục 6.4, 6.5) |
| Messages may be redelivered; consumer records dedup key with its database effect | `processed_event` cùng transaction (mục 6.3) |
| Acknowledge after durable progress; bounded retries; dead-letter; operator-visible recovery | Ack sau commit; retry policy; DLQ; `ops/summary` + `POST /reconcile` |
| Failure drill: stop publisher, restart, no duplicate effects | Mục 6.6 |
| Validate tokens and permissions in the API | Mục 8 |
| Secrets in Secret Manager; workload identity | `DB_PASSWORD` qua Secret Manager; Pub/Sub dùng service account (ADC) |
| Record request/job IDs across logs, API responses and workers | Mục 9.1 |
| Monitoring: checkout latency, outbox age, reconciliation backlog, inventory conflicts | Mục 9.3 |
| Do not log credentials | Mục 8 |
| Test 1, Test 2, Test 3 | `AcceptanceTest` + script (mục 11) |
| Test authorized and forbidden API calls directly | `SecurityTest`, `smoke_auth.py` |

---

## 13. Giới hạn và ngoài phạm vi

| Hạng mục | Trạng thái |
|---|---|
| Giữ chỗ có thời hạn (optional extension của đề) | Không triển khai |
| Giỏ hàng lưu phía server | Không triển khai; Frontend giữ giỏ hàng |
| Thanh toán thật, hoàn tiền, đa tiền tệ | Ngoài phạm vi; cửa hàng chỉ dùng VND |
| Ảnh bìa sách | `imageUrl` để trống trong dữ liệu mẫu |
| Kiểm thử trên GCP thật (Firebase, Pub/Sub push + DLQ, Cloud Logging) | Đã kiểm chứng bằng emulator và profile cloud ở local; cần xác nhận lại sau khi triển khai |
| `deliveryAttempt` trong push request | Pub/Sub emulator không gửi trường này; Pub/Sub thật có gửi khi bật dead-letter |
