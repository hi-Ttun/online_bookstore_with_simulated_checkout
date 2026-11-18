# Frontend và giao diện quản trị

**Người thực hiện:** Quân – Vị trí 3 (Frontend & Giao diện Website)
**Dùng cho báo cáo:** mục 3.2.1 (Phía Khách hàng), 3.2.2 (Phía Store Operator) và phần Frontend của 2.3.1, 3.5.1.

---

## 1. Công nghệ và lý do lựa chọn

| Thành phần | Lựa chọn | Lý do |
|---|---|---|
| Thư viện giao diện | React 18 | Phổ biến, chia màn hình thành component tái sử dụng (biên nhận dùng chung cho khách và operator) |
| Công cụ build | Vite 5 | Build nhanh; dev server có proxy tới Backend nên không vướng CORS khi phát triển |
| Định tuyến | React Router (HashRouter) | Đường dẫn dạng `/#/cart` chạy được trên mọi máy chủ file tĩnh mà không cần cấu hình rewrite |
| Đăng nhập | Firebase Authentication SDK (Identity Platform) | Cùng nhà cung cấp OIDC mà Backend kiểm tra JWT; SDK tự làm mới ID token |
| Triển khai | nginx trong container trên Cloud Run | Cùng nền tảng với Backend, HTTPS mặc định, scale về 0 khi không dùng |

Không dùng thư viện UI hay quản lý state bên ngoài: CSS thuần và React Context là đủ cho phạm vi đề tài,
giảm kích thước bundle (~68 KB gzip phần ứng dụng; Firebase SDK chỉ tải khi ở chế độ `firebase`).

## 2. Cấu trúc mã nguồn

```
frontend/
├── index.html                 # nạp /config.js trước ứng dụng
├── public/config.js           # cấu hình lúc chạy (API_URL, AUTH_MODE, Firebase)
├── src/
│   ├── main.jsx, App.jsx      # khởi tạo, định tuyến
│   ├── lib/
│   │   ├── api.js             # gọi API, chuẩn hóa lỗi, gắn token
│   │   ├── auth.jsx           # đăng nhập Firebase / dev token, xác định quyền operator
│   │   ├── cart.jsx           # giỏ hàng (localStorage)
│   │   ├── idempotency.js     # sinh và giữ Idempotency-Key
│   │   ├── format.js          # tiền VND, giờ Việt Nam, nhãn trạng thái
│   │   └── usePolling.js      # tự làm mới dữ liệu định kỳ
│   ├── components/            # Header, Receipt, Badge, ErrorBox, BookCover, RequireAuth
│   └── pages/                 # Catalogue, Cart, Checkout, OrderDetail, MyOrders, Login
│       └── admin/             # Operations, Fulfillment, AdminOrders, Inventory, Movements
├── mock/server.mjs            # Backend giả lập, chỉ để phát triển giao diện
├── Dockerfile, nginx.conf.template, docker-entrypoint.d/
└── docs/FRONTEND.md           # tài liệu này
```

## 3. Phía Khách hàng (báo cáo mục 3.2.1)

### 3.1. Danh mục sách

- `GET /api/products?page=0&size=100` – API công khai, **không gửi token** (token hết hạn sẽ làm API công khai trả 401).
- Hiển thị 50 sách: SKU, tên, tác giả, giá, số lượng còn bán (`available`). Tìm theo tên/tác giả/SKU, sắp xếp
  theo giá/tên, lọc "Còn hàng".
- `available = 0` → nút "Hết hàng" bị vô hiệu hóa; không cho thêm vượt quá số còn bán.
- `imageUrl` đang để trống → vẽ bìa mặc định bằng CSS (màu theo id sách), không phụ thuộc tài nguyên ngoài.

### 3.2. Giỏ hàng

- Lưu ở `localStorage` (`bookstore.cart`), đồng bộ giữa các tab. Không lưu giỏ ở server vì đề không yêu cầu,
  và tồn kho chỉ được giữ (reserve) khi checkout.
- Thêm cùng một sách nhiều lần → gộp số lượng. Giới hạn theo Backend: tối đa 20 dòng, 1–100 cuốn/dòng.
- Giỏ hàng được xóa khi đăng xuất (tránh để lại cho người dùng sau trên máy dùng chung).

### 3.3. Thanh toán idempotent

Luồng:

```
Mở trang thanh toán ─► keyFor(nội dung) ─► POST /api/checkout
                         │                    Header: Authorization, Idempotency-Key
                         │                    Body: { items, paymentScenario }
                         │
     cùng nội dung ─────►│ dùng lại key cũ     201 Created ─► lưu key, xóa giỏ, mở trang đơn
     nội dung đổi ──────►│ sinh key mới        200 + Idempotent-Replayed ─► đơn cũ, không tạo mới
                                               409 OUT_OF_STOCK ─► báo sách thiếu, tải lại tồn kho
                                               409 IDEMPOTENCY_KEY_REUSED ─► sinh key mới, mời bấm lại
                                               lỗi mạng / 5xx ─► giữ nguyên key, mời bấm lại
```

Quy tắc giữ key (`src/lib/idempotency.js`):

- **Nội dung** = danh sách `productId×quantity` đã sắp xếp + kịch bản, giống cách Backend tính `request_hash`
  (ví dụ `1x2,5x1|SUCCESS`).
- Mỗi nội dung có đúng một key, lưu trong `sessionStorage`. Bấm "Đặt hàng" nhiều lần, F5, hay lỗi mạng rồi gửi lại
  đều dùng cùng key → Backend chỉ tạo **một** đơn.
- Không sinh key mới mỗi lần bấm nút: làm vậy thì một request đã tới server nhưng mất phản hồi sẽ thành đơn thứ hai
  khi người dùng bấm lại (điểm số 1 trong HANDOVER.md mục 2.1).
- Nút "Đặt hàng" bị khóa trong lúc chờ phản hồi để tránh gửi chồng; nhưng tính đúng đắn không dựa vào việc khóa nút
  mà dựa vào key.

### 3.4. Nút chọn kịch bản thanh toán

Ba lựa chọn gửi trong trường `paymentScenario`:

| Lựa chọn | Giá trị | Kết quả mong đợi trên giao diện |
|---|---|---|
| Thanh toán thành công | `SUCCESS` | Đang xử lý → **Thanh toán thành công** |
| Thanh toán thất bại | `FAIL` | Đang xử lý → **Thanh toán thất bại**, sách hoàn lại kho |
| Quá thời gian | `TIMEOUT` | Đang xử lý → **Đang xác minh thanh toán** (không báo lỗi) → Thành công sau đối soát |

### 3.5. Theo dõi trạng thái đơn

Thanh toán xử lý bất đồng bộ (outbox → Pub/Sub → worker), nên sau khi tạo đơn, trang chi tiết gọi
`GET /api/orders/{id}` mỗi 1,5 giây (3 giây khi `PAYMENT_UNKNOWN`) và dừng khi đạt trạng thái cuối
`PAID`, `PAYMENT_FAILED` hoặc `FULFILLED`. Tab bị ẩn thì tạm dừng hỏi lại.

| Trạng thái | Nhãn hiển thị |
|---|---|
| `PENDING_PAYMENT` | Đang xử lý thanh toán |
| `PAYMENT_UNKNOWN` | Đang xác minh thanh toán (giải thích đây không phải lỗi) |
| `PAID` | Thanh toán thành công |
| `PAYMENT_FAILED` | Thanh toán thất bại |
| `FULFILLED` | Đã giao hàng |

Biên nhận gồm: mã đơn, thời gian (đổi từ UTC sang giờ Việt Nam), kịch bản, các dòng sách với giá tại thời điểm mua,
tổng tiền và lịch sử các lần gọi cổng thanh toán (`paymentAttempts`).

## 4. Phía Store Operator (báo cáo mục 3.2.2)

Menu "Quản trị" chỉ hiện khi tài khoản là operator. Frontend xác định bằng cách gọi thử
`GET /api/admin/ops/summary` (200 = operator, 403 = khách). Đây chỉ là **ẩn giao diện**; quyền thực sự do
Backend kiểm tra ở mọi API quản trị (khách gọi trực tiếp vẫn bị 403 – đề yêu cầu kiểm thử API trực tiếp,
không chỉ ẩn nút).

| Tab | Nội dung | Làm mới |
|---|---|---|
| Vận hành | `outboxPending`, `outboxOldestAgeSeconds` (đỏ khi > 60 giây, cùng ngưỡng cảnh báo), `paymentsUnknown`, số đơn theo trạng thái, nút **Đối soát ngay** | 3 giây |
| Cần giao hàng | Đơn `PAID`; nút **Đã giao** → `FULFILLED`. `409 INVALID_STATE` được báo lỗi rồi tải lại danh sách | 5 giây |
| Lịch sử đơn hàng | 200 đơn mới nhất, lọc theo trạng thái; bấm một đơn để xem biên nhận | 5 giây |
| Tồn kho | `onHand` / `reserved` / `available` từng sách, tổng cộng, lọc đang giữ / sắp hết / hết hàng | 4 giây |
| Biến động kho | `SEED`, `RESERVE`, `COMMIT`, `RELEASE`; lọc theo sách, loại, đơn; đếm số dòng theo loại | 5 giây |

## 5. Xác thực

| Chế độ | Dùng khi | Cách lấy token |
|---|---|---|
| `firebase` | Triển khai GCP | `signInWithEmailAndPassword` / `createUserWithEmailAndPassword`; mỗi request gọi `currentUser.getIdToken()` (SDK tự làm mới) |
| `dev` | Phát triển local | `POST /dev/token?user=alice` (endpoint chỉ có ở profile local, trả 404 trên cloud) |

- Token gửi trong header `Authorization: Bearer <token>`; không lưu mật khẩu.
- `401` → xóa phiên, chuyển tới trang đăng nhập kèm thông báo hết phiên.
- `403` → thông báo "không có quyền".

## 6. Xử lý lỗi và truy vết (báo cáo mục 3.5.1)

- Mọi lỗi API theo dạng `{"error": {"code", "message", "correlationId", "details"}}` được hiển thị cùng
  **mã tra cứu (correlationId)**. Người vận hành dùng mã này lọc Cloud Logging:
  `jsonPayload.correlationId="<id>"`.
- Sau khi đặt hàng, trang đơn hiển thị HTTP status và `X-Correlation-Id` của request checkout → nối được
  Frontend → API → Pub/Sub → Worker trong log.
- Frontend không ghi log token hay dữ liệu cá nhân ra console.

## 7. Triển khai (báo cáo mục 2.3.1)

- Image: build bằng Node, chạy bằng `nginx:alpine` (multi-stage), lắng nghe `$PORT` của Cloud Run.
- **Một image cho mọi môi trường**: `API_URL`, `AUTH_MODE`, cấu hình Firebase truyền bằng biến môi trường;
  script `docker-entrypoint.d/40-app-config.sh` sinh `config.js` khi container khởi động.
- `config.js`, `index.html` không cache; file trong `/assets/` có hash nên cache 1 năm.
- Service `bookstore-frontend`: public (`--allow-unauthenticated`), chỉ phục vụ file tĩnh, không giữ bí mật.
  Firebase Web API key là định danh công khai của ứng dụng, không phải mật khẩu.

Phương án thay thế đã cân nhắc: Cloud Storage + Cloud CDN. Không chọn vì cần Load Balancer (chi phí cố định hằng
tháng) để có HTTPS với domain riêng, trong khi Cloud Run có HTTPS sẵn và scale về 0.

Ranh giới tin cậy: trình duyệt là vùng **không tin cậy**. Frontend chỉ là tiện ích hiển thị; mọi quyết định về
tồn kho, giá, quyền truy cập đều do Backend thực hiện (giá trong giỏ hàng chỉ để hiển thị, Backend tính lại tổng tiền
từ cơ sở dữ liệu).

## 8. Kịch bản demo (10–15 phút)

1. **Hành trình khách hàng**: mở danh mục → thêm 2 sách → giỏ hàng → đăng nhập → thanh toán `SUCCESS` → trang đơn tự
   chuyển *Đang xử lý* → *Thành công*.
2. **Test 2 (idempotency)**: trên trang đơn vừa tạo, mở "Công cụ demo Test 2":
   - "Gửi lại cùng key, cùng nội dung" → `HTTP 200 · Idempotent-Replayed: true`, cùng `orderId`.
   - "Gửi lại cùng key, đổi số lượng" → `HTTP 409 IDEMPOTENCY_KEY_REUSED`.
3. **Test 3 (thất bại)**: mua `BOOK-003` (Frankenstein) × 2 với `FAIL` → *Thanh toán thất bại*. Operator → Tồn kho:
   BOOK-003 = 3 / 0 / 3; Biến động kho lọc BOOK-003: đúng 1 dòng `RELEASE`.
4. **Timeout**: mua `BOOK-004` với `TIMEOUT` → *Đang xác minh thanh toán* → Operator → Vận hành thấy
   `paymentsUnknown = 1` → "Đối soát ngay" (hoặc đợi tự đối soát) → đơn thành *Thành công*.
5. **Fulfillment**: Operator → Cần giao hàng → "Đã giao" → khách thấy *Đã giao hàng*.
6. **Test 1** (tranh mua `BOOK-001`, tồn 1): hai trình duyệt (alice, bob) cùng có BOOK-001 trong giỏ; người bấm sau
   nhận thông báo *không đủ hàng* (`409 OUT_OF_STOCK`). Kiểm thử đồng thời thật do Tiên chạy bằng script/Postman.
7. **Diễn tập sự cố**: khi Tân tắt relay, tab Vận hành cho thấy `outboxPending` và tuổi outbox tăng, chuyển đỏ khi
   > 60 giây; bật lại relay → về 0, đơn chuyển trạng thái cuối.

## 9. Kiểm thử Frontend

Đã kiểm thử tự động bằng trình duyệt headless (Playwright) với Backend giả lập `mock/server.mjs`:

| Kịch bản | Kết quả |
|---|---|
| Danh mục hiện 50 sách, đúng tồn theo seed (1, 5, 3, 10, 20…) | Đạt |
| Idempotency-Key giữ nguyên khi đổi kịch bản rồi đổi lại; đổi khi nội dung đổi | Đạt |
| `SUCCESS` → Thanh toán thành công; giỏ được xóa | Đạt |
| Gửi lại cùng key/cùng nội dung → 200, cùng orderId; đổi nội dung → 409 | Đạt |
| `FAIL` BOOK-003 × 2 → thất bại; tồn kho 3/0/3; đúng 1 dòng RELEASE | Đạt |
| `TIMEOUT` → Đang xác minh; Đối soát ngay → resolved 1 | Đạt |
| Hai khách tranh cuốn BOOK-001 cuối → người sau nhận OUT_OF_STOCK | Đạt |
| Khách mở trang quản trị → bị chặn, menu Quản trị ẩn | Đạt |
| Operator: Đã giao làm đơn rời khỏi danh sách cần giao | Đạt |

**Cần làm thêm khi có môi trường thật:** chạy lại các kịch bản trên với Backend thật (local `docker compose` và trên
GCP), chụp màn hình làm minh chứng cho báo cáo, kiểm tra đăng nhập Firebase với tài khoản demo do Tân tạo.
