# Frontend – Online Bookstore

Giao diện web cho khách hàng và Store Operator. React 18 + Vite, gọi Backend qua REST API
(đặc tả: [backend/docs/BACKEND.md](../backend/docs/BACKEND.md) mục 7; hướng dẫn tích hợp:
[backend/docs/HANDOVER.md](../backend/docs/HANDOVER.md) mục 4).

**Người phụ trách:** Quân (Vị trí 3 – Frontend & Giao diện Website).
Tài liệu thiết kế và nội dung cho báo cáo: [docs/FRONTEND.md](docs/FRONTEND.md).

## Chức năng

| Người dùng | Màn hình | API |
|---|---|---|
| Khách | Danh mục 50 sách (tìm kiếm, sắp xếp, lọc còn hàng) | `GET /api/products` |
| Khách | Giỏ hàng (localStorage, gộp số lượng, giới hạn theo tồn) | – |
| Khách | Thanh toán + chọn kịch bản **Thành công / Thất bại / Timeout** | `POST /api/checkout` (`Idempotency-Key`) |
| Khách | Đơn của tôi, biên nhận, tự cập nhật trạng thái | `GET /api/orders`, `GET /api/orders/{id}` |
| Operator | Vận hành: outbox, đối soát, số đơn theo trạng thái | `GET /api/admin/ops/summary`, `POST /api/admin/reconcile` |
| Operator | Cần giao hàng (Fulfillment) + nút "Đã giao" | `GET /api/admin/orders?status=PAID`, `POST /api/admin/orders/{id}/fulfill` |
| Operator | Lịch sử đơn hàng + chi tiết | `GET /api/admin/orders`, `GET /api/admin/orders/{id}` |
| Operator | Tồn kho (tự làm mới 4 giây) | `GET /api/admin/inventory` |
| Operator | Biến động kho (lọc theo sách / loại / đơn) | `GET /api/admin/inventory/movements` |

## Chạy local

Yêu cầu: Node.js 20+.

```bash
cd frontend
npm install
npm run dev            # http://localhost:5173
```

Vite chuyển tiếp `/api`, `/dev`, `/healthz` sang Backend ở `http://localhost:8080`
(đổi bằng biến `BACKEND_URL`). Chạy Backend theo [backend/README.md](../backend/README.md).

Đăng nhập ở chế độ phát triển: chọn **Khách hàng alice / bob** hoặc **Store Operator**
(token lấy từ `POST /dev/token`, chỉ có ở Backend profile local).

### Chưa chạy được Backend? Dùng mock

```bash
node mock/server.mjs   # cổng 8080, đọc 50 sách từ backend/db/seed.sql
npm run dev
```

`mock/server.mjs` **chỉ để phát triển giao diện**: mô phỏng hợp đồng API (mã lỗi, idempotency, trạng thái đơn,
tồn kho), không có Pub/Sub, outbox thật hay kiểm tra chữ ký JWT. Không dùng làm minh chứng kiểm thử.
`MOCK_RELAY=off node mock/server.mjs` giả lập relay bị tắt để xem thanh vận hành.

## Cấu hình

Cấu hình được đọc lúc chạy từ `public/config.js` (`window.__APP_CONFIG__`). Khi chạy bằng Docker, file này
được sinh từ biến môi trường:

| Biến | Ý nghĩa | Ví dụ |
|---|---|---|
| `API_URL` | URL service `bookstore-api`; để trống = cùng origin | `https://bookstore-api-xxxx.a.run.app` |
| `AUTH_MODE` | `firebase` (GCP) hoặc `dev` (local) | `firebase` |
| `FIREBASE_API_KEY` | Web API key của Identity Platform (giá trị công khai) | `AIza...` |
| `FIREBASE_AUTH_DOMAIN` | | `<project>.firebaseapp.com` |
| `FIREBASE_PROJECT_ID` | Trùng `AUTH_PROJECT_ID` của Backend | `<project>` |

## Triển khai lên Cloud Run

```bash
# Build và đẩy image (tag theo git SHA)
docker build -t $REGION-docker.pkg.dev/$PROJECT/$REPO/bookstore-frontend:$SHA frontend/
docker push $REGION-docker.pkg.dev/$PROJECT/$REPO/bookstore-frontend:$SHA

gcloud run deploy bookstore-frontend \
  --image $REGION-docker.pkg.dev/$PROJECT/$REPO/bookstore-frontend:$SHA \
  --region $REGION --allow-unauthenticated --port 8080 \
  --min-instances 0 --max-instances 2 --memory 256Mi \
  --set-env-vars API_URL=https://<bookstore-api-url>,AUTH_MODE=firebase,FIREBASE_API_KEY=<key>,FIREBASE_AUTH_DOMAIN=<project>.firebaseapp.com,FIREBASE_PROJECT_ID=<project>
```

Sau khi có URL Frontend, cần:

1. Thêm URL Frontend vào `APP_CORS_ORIGINS` của service `bookstore-api`.
2. Thêm domain Frontend (`bookstore-frontend-xxxx.a.run.app`) vào **Authorized domains** của Identity Platform.

Kiểm tra: `GET https://<frontend>/healthz` trả `ok`; mở trang, danh mục hiện 50 sách.
