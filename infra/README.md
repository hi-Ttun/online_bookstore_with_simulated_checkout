# Hạ tầng GCP (Terraform) — Online Bookstore

Người phụ trách: Tân (Cloud Infrastructure & DevOps). Region: `asia-southeast1`.

## Thành phần

| Tài nguyên | Tên | Ghi chú |
|---|---|---|
| VPC + subnet | `bookstore-vpc`, `run-subnet` 10.10.0.0/24 | Private Service Access cho Cloud SQL |
| Cloud SQL | `bookstore-pg` PostgreSQL 16, db-f1-micro | **Chỉ Private IP**. Flyway tạo bảng khi app khởi động |
| Cloud Run | `bookstore-api` (public), `bookstore-worker` (IAM, chỉ Pub/Sub gọi), `bookstore-relay` (ingress internal, min 1, CPU luôn cấp), `bookstore-web` (public) | Direct VPC egress `PRIVATE_RANGES_ONLY` → không cần Serverless VPC Connector hay Cloud NAT |
| Pub/Sub | `order-events`, `order-events-dlq`; sub `order-events-worker` (push + OIDC), `order-events-debug`, `order-events-dlq-debug` | ack 30s, retry 1–10s, DLQ sau 5 lần |
| Secret Manager | `db-password` | Sinh ngẫu nhiên, chỉ 3 SA backend đọc được |
| Storage | `<project>-bookstore-private` | Public access prevention = enforced |
| Identity Platform | Email/Password | |
| CI/CD | WIF pool `github-pool` → `sa-github-deployer` | Không dùng JSON key |

## Dựng lại từ đầu

```bash
PROJECT=<project-id>
gcloud storage buckets create gs://$PROJECT-tfstate --location=asia-southeast1 --uniform-bucket-level-access
gcloud storage buckets update gs://$PROJECT-tfstate --versioning
cd infra
cp terraform.tfvars.example terraform.tfvars   # sửa project_id, operator_emails
terraform init -backend-config="bucket=$PROJECT-tfstate"
terraform apply
```

## Seed dữ liệu (50 sách) vào DB Private IP

Cloud SQL tự đọc file từ bucket riêng tư, không cần mở Public IP. Chạy **sau** khi backend đã deploy ít nhất một lần
(Flyway tạo bảng). Dừng relay trước khi seed (HANDOVER mục 7).

```bash
gcloud storage cp backend/db/seed.sql gs://$PROJECT-bookstore-private/seed.sql
gcloud sql import sql bookstore-pg gs://$PROJECT-bookstore-private/seed.sql --database=bookstore --user=bookstore --quiet
```

## Rollback

```bash
gcloud run revisions list --service bookstore-api --region asia-southeast1
gcloud run services update-traffic bookstore-api --region asia-southeast1 --to-revisions=<REVISION>=100
```

## Tiết kiệm chi phí khi không làm việc

```bash
gcloud sql instances patch bookstore-pg --activation-policy=NEVER     # tắt DB (ALWAYS để bật lại)
terraform apply -var relay_always_on=false                            # relay về 0 instance
```

## Cleanup

```bash
terraform destroy
gcloud storage rm -r gs://$PROJECT-tfstate
gcloud artifacts repositories list --location=asia-southeast1   # xác nhận không còn
gcloud sql instances list; gcloud run services list              # xác nhận không còn tài nguyên tính phí
```
