# ---------- Mỗi service 1 Service Account, quyền tối thiểu ----------
resource "google_service_account" "svc" {
  for_each     = toset(["api", "worker", "relay"])
  account_id   = "sa-${each.key}"
  display_name = "bookstore-${each.key} runtime"
}

resource "google_service_account" "web" {
  account_id   = "sa-web"
  display_name = "bookstore-web runtime (không có quyền thêm)"
}

resource "google_service_account" "invoker" {
  account_id   = "sa-pubsub-invoker"
  display_name = "Pub/Sub push -> bookstore-worker"
}

resource "google_project_iam_member" "sql_client" {
  for_each = google_service_account.svc
  project  = var.project_id
  role     = "roles/cloudsql.client"
  member   = "serviceAccount:${each.value.email}"
}

resource "google_project_iam_member" "log_writer" {
  for_each = google_service_account.svc
  project  = var.project_id
  role     = "roles/logging.logWriter"
  member   = "serviceAccount:${each.value.email}"
}

# Chỉ đọc đúng secret db-password, không cấp cả project
resource "google_secret_manager_secret_iam_member" "db_pw" {
  for_each  = google_service_account.svc
  secret_id = google_secret_manager_secret.db_password.id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${each.value.email}"
}

# Chỉ relay được publish, và chỉ vào topic order-events
resource "google_pubsub_topic_iam_member" "relay_pub" {
  topic  = google_pubsub_topic.events.id
  role   = "roles/pubsub.publisher"
  member = "serviceAccount:${google_service_account.svc["relay"].email}"
}
