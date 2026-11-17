variable "project_id" {
  type = string
}

variable "region" {
  type    = string
  default = "asia-southeast1"
}

variable "github_repo" {
  description = "Repo GitHub dạng owner/repo"
  type        = string
  default     = "hi-Ttun/online_bookstore_with_simulated_checkout"
}

variable "operator_emails" {
  description = "APP_OPERATOR_EMAILS: email tài khoản Store Operator, phân tách bằng dấu phẩy"
  type        = string
}

variable "extra_cors_origins" {
  description = "Origin thêm cho APP_CORS_ORIGINS (ngoài URL của bookstore-web)"
  type        = string
  default     = "http://localhost:5173,http://localhost:3000"
}

variable "relay_always_on" {
  description = "true: relay luôn chạy 1 instance (cần cho demo). false: tiết kiệm tiền khi không làm việc."
  type        = bool
  default     = true
}
