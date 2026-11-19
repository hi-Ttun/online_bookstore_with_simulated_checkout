resource "google_sql_database_instance" "pg" {
  name             = "bookstore-pg"
  database_version = "POSTGRES_16"
  region           = var.region
  depends_on       = [google_service_networking_connection.psa]

  settings {
    edition           = "ENTERPRISE" # bắt buộc để dùng tier rẻ db-f1-micro
    tier              = "db-f1-micro"
    availability_type = "ZONAL"
    disk_size         = 10
    disk_autoresize   = false

    ip_configuration {
      ipv4_enabled    = false # KHÔNG có Public IP
      private_network = google_compute_network.vpc.id
    }

    backup_configuration {
      enabled    = true
      start_time = "18:00" # UTC = 01:00 giờ VN
    }
  }

  deletion_protection = false # để terraform destroy được khi cleanup
}

resource "google_sql_database" "app" {
  name     = "bookstore"
  instance = google_sql_database_instance.pg.name
}

resource "random_password" "db" {
  length  = 24
  special = false
}

# Flyway chạy bằng user này khi app khởi động -> user này sở hữu các bảng
resource "google_sql_user" "app" {
  name     = "bookstore"
  instance = google_sql_database_instance.pg.name
  password = random_password.db.result
}

# ---------- Secret Manager ----------
resource "google_secret_manager_secret" "db_password" {
  secret_id = "db-password"
  replication {
    auto {}
  }
}

resource "google_secret_manager_secret_version" "db_password" {
  secret      = google_secret_manager_secret.db_password.id
  secret_data = random_password.db.result
}
