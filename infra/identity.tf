# Identity Platform: đăng nhập Email/Password (HANDOVER mục 5.2).
# Nếu apply báo lỗi ở resource này: xóa file, bật trên Console
# (Identity Platform -> Enable -> Providers -> Email/Password) và ghi lại thao tác vào báo cáo.
resource "google_identity_platform_config" "default" {
  sign_in {
    email {
      enabled           = true
      password_required = true
    }
  }

  authorized_domains = [
    "localhost",
    "${var.project_id}.firebaseapp.com",
    "${var.project_id}.web.app",
    trimprefix(google_cloud_run_v2_service.web.uri, "https://"),
  ]
}
