# Online Bookstore with Simulated Checkout

Final Project 04 – Cloud Computing.

| Thư mục | Nội dung |
|---|---|
| [backend/](backend/) | Spring Boot API, worker, outbox relay |
| [frontend/](frontend/) | Giao diện web React: khách hàng (danh mục, giỏ hàng, thanh toán) và Store Operator |

## Tài liệu Backend

| Tài liệu | Dành cho |
|---|---|
| [backend/README.md](backend/README.md) | Cài đặt, chạy local, xử lý lỗi thường gặp |
| [backend/docs/BACKEND.md](backend/docs/BACKEND.md) | Kiến trúc, dữ liệu, luồng nghiệp vụ, đặc tả API, cấu hình triển khai |
| [backend/docs/HANDOVER.md](backend/docs/HANDOVER.md) | Hướng dẫn tích hợp cho Frontend (mục 4), triển khai GCP (mục 5), kiểm thử và giám sát (mục 6) |

Đặc tả OpenAPI khi Backend đang chạy: `http://localhost:8080/v3/api-docs` (Swagger UI: `/swagger-ui.html`).

## Tài liệu Frontend

| Tài liệu | Dành cho |
|---|---|
| [frontend/README.md](frontend/README.md) | Cài đặt, chạy local, cấu hình, triển khai Cloud Run |
| [frontend/docs/FRONTEND.md](frontend/docs/FRONTEND.md) | Thiết kế giao diện, luồng checkout idempotent, trang quản trị, kịch bản demo |
