# Bookstore Backend

Backend của dự án **Online Bookstore with Simulated Checkout**: Spring Boot 4 (Java 21), PostgreSQL, Google Cloud Pub/Sub,
triển khai trên Cloud Run.

| Tài liệu | Nội dung |
|---|---|
| [docs/BACKEND.md](docs/BACKEND.md) | Tài liệu kỹ thuật: kiến trúc, dữ liệu, luồng nghiệp vụ, API, bảo mật, observability, kiểm thử |
| [docs/HANDOVER.md](docs/HANDOVER.md) | Bàn giao cho Frontend, Cloud/DevOps, QA; đối chiếu phân công với đề bài |

## Yêu cầu

- JDK 21
- Docker (PostgreSQL và Pub/Sub emulator cho môi trường local; Testcontainers khi chạy test)
- Python 3 (tùy chọn, cho các script kiểm thử end-to-end)

## Chạy local

```bash
docker compose up -d                 # PostgreSQL (cổng 5433) + Pub/Sub emulator (8085)
./mvnw spring-boot:run               # profile local; Flyway tạo bảng khi khởi động lần đầu
docker compose exec postgres psql -U bookstore -d bookstore -f /seed/seed.sql   # nạp 50 sách mẫu
```

Trên Windows dùng `.\mvnw.cmd` thay cho `./mvnw`.

- Swagger UI: http://localhost:8080/swagger-ui.html
- Token phát triển: `POST /dev/token?user=alice` (khách hàng) hoặc `user=operator` (Store Operator), sau đó dùng nút
  **Authorize** trên Swagger.

### Lỗi thường gặp

| Hiện tượng | Cách xử lý |
|---|---|
| `Connection to localhost:5433 refused` khi khởi động | Docker chưa chạy hoặc chưa `docker compose up -d` |
| `Port 8080 was already in use` | Đã có một tiến trình ứng dụng khác đang chạy; dừng tiến trình đó |
| `JAVA_HOME not found` khi chạy `mvnw` | Cài JDK 21 và đặt biến môi trường `JAVA_HOME` trỏ tới thư mục JDK |
| VS Code báo `The input line is too long` khi Run Java | Thêm `"shortenCommandLine": "jarmanifest"` vào cấu hình trong `launch.json`, hoặc chạy bằng `mvnw` |
| Đơn hàng đứng ở `PENDING_PAYMENT` | Kiểm tra container `pubsub` và `pubsub-init` đã chạy; emulator cần gọi được `host.docker.internal:8080` |

## Kiểm thử

```bash
./mvnw test                          # 13 test tự động (Test 1, 2, 3 của đề, timeout, bảo mật)
python scripts/smoke_checkout.py     # kiểm thử end-to-end, cần ứng dụng đang chạy và dữ liệu vừa seed
```

Danh sách đầy đủ: [docs/BACKEND.md, mục 11](docs/BACKEND.md#11-kiểm-thử).

## Build image

```bash
docker build -t bookstore-backend .
```

Một image dùng cho ba service Cloud Run (api, worker, relay); cấu hình và biến môi trường:
[docs/BACKEND.md, mục 10](docs/BACKEND.md#10-cấu-hình-và-triển-khai).
