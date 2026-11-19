# Online Bookstore with Simulated Checkout

Final Project 04 – Cloud Computing (Hệ thống cửa hàng sách trực tuyến tích hợp thanh toán mô phỏng trên Google Cloud Platform).

## 1. Cấu trúc Dự án

```
online_bookstore_with_simulated_checkout/
├── .github/workflows/          # CI/CD Workflows (backend-ci-cd.yml, infra-ci.yml)
├── backend/                    # Spring Boot 4.1 API, Outbox Relay, Pub/Sub Worker, Flyway migration
│   ├── db/seed.sql             # Bộ dữ liệu mẫu 50 đầu sách và tồn kho
│   ├── docs/                   # Tài liệu chi tiết BACKEND.md và bàn giao HANDOVER.md
│   └── scripts/                # Bộ script Python kiểm thử tự động e2e (smoke test)
├── frontend/                   # Ứng dụng React 18 + Vite (Khách hàng & Store Operator)
│   ├── docs/FRONTEND.md        # Tài liệu thiết kế giao diện và kịch bản demo
│   └── mock/server.mjs         # Mock server phục vụ chạy thử nhanh UI
├── infra/                      # Terraform (IaC) dựng hạ tầng đầy đủ trên GCP
│   └── README.md               # Hướng dẫn chi tiết triển khai hạ tầng
├── start-web.bat               # File chạy tự động 1-click cho Windows
├── start-web.ps1               # File chạy tự động PowerShell
├── package.json                # Scripts npm cấp root
├── 04_online_bookstore_with_simulated_checkout.pdf  # Đề bài gốc của môn học
└── README.md
```

## 2. Tài liệu Tham khảo Nhanh

| Tài liệu | Mô tả |
|---|---|
| [backend/README.md](backend/README.md) | Cài đặt và chạy Backend local |
| [backend/docs/BACKEND.md](backend/docs/BACKEND.md) | Kiến trúc phân tán, đặc tả API, Transactional Outbox, bảo mật |
| [backend/docs/HANDOVER.md](backend/docs/HANDOVER.md) | Phân công vai trò, tích hợp Frontend, checklist GCP và kiểm thử |
| [frontend/README.md](frontend/README.md) | Cài đặt và cấu hình Frontend |
| [frontend/docs/FRONTEND.md](frontend/docs/FRONTEND.md) | Thiết kế UI, Idempotency, trang quản trị Operator, kịch bản demo |
| [infra/README.md](infra/README.md) | Hướng dẫn Terraform, CI/CD, biến môi trường, rollback và dọn dẹp |

## 3. Khởi chạy Web

### Cách 1: Chạy 1-Click (Khuyên dùng)
* Nhấp đúp chuột vào file **`start-web.bat`** tại thư mục gốc dự án.
* Hoặc chạy trong Terminal:
  ```powershell
  .\start-web.bat
  ```
Hệ thống sẽ tự động bật Mock Backend (8080), bật Frontend Vite (5173) và tự mở trình duyệt tới `http://localhost:5173/`.

### Cách 2: Chạy bằng Terminal
```powershell
# Terminal 1: Bật Backend giả lập (cổng 8080)
node frontend/mock/server.mjs

# Terminal 2: Bật Frontend React (cổng 5173)
cd frontend
npm run dev
```

Truy cập: **`http://localhost:5173`**
- Chọn tài khoản **alice** hoặc **bob** để xem danh mục sách và đặt hàng.
- Chọn tài khoản **Store Operator** để vào trang Quản trị (Vận hành, Giao hàng, Tồn kho, Biến động kho).
