# Một image backend, 3 service Cloud Run khác nhau ở biến môi trường (HANDOVER mục 5.1).
locals {
  common_env = {
    SPRING_PROFILES_ACTIVE = "cloud"
    DB_URL                 = "jdbc:postgresql://${google_sql_database_instance.pg.private_ip_address}:5432/${google_sql_database.app.name}"
    DB_USER                = google_sql_user.app.name
    APP_CORS_ORIGINS       = "${google_cloud_run_v2_service.web.uri},${var.extra_cors_origins}"
    AUTH_PROJECT_ID        = var.project_id
    APP_OPERATOR_EMAILS    = var.operator_emails
    GCP_PROJECT_ID         = var.project_id # app.pubsub.project-id không có mặc định
    PUBSUB_TOPIC           = google_pubsub_topic.events.name
  }

  backend = {
    api = {
      ingress  = "INGRESS_TRAFFIC_ALL"
      min      = 0
      max      = 3
      cpu_idle = true
      env      = {}
    }
    worker = {
      ingress  = "INGRESS_TRAFFIC_ALL" # Pub/Sub push đi từ ngoài; chặn bằng IAM (không có allUsers)
      min      = 0
      max      = 2
      cpu_idle = true
      env      = { APP_WORKER_ENABLED = "true" }
    }
    relay = {
      ingress  = "INGRESS_TRAFFIC_INTERNAL_ONLY" # không ai từ Internet gọi được
      min      = var.relay_always_on ? 1 : 0
      max      = 1
      cpu_idle = false # CPU luôn cấp để @Scheduled chạy nền (= --no-cpu-throttling)
      env      = { APP_RELAY_ENABLED = "true", APP_RECONCILER_ENABLED = "true" }
    }
  }
}

resource "google_cloud_run_v2_service" "backend" {
  for_each            = local.backend
  name                = "bookstore-${each.key}"
  location            = var.region
  ingress             = each.value.ingress
  deletion_protection = false

  template {
    service_account = google_service_account.svc[each.key].email

    scaling {
      min_instance_count = each.value.min
      max_instance_count = each.value.max
    }

    # Direct VPC egress: chỉ traffic tới dải IP private (Cloud SQL) đi qua VPC.
    # Traffic ra Internet (www.googleapis.com lấy JWKS, Pub/Sub API) đi thẳng -> không cần Cloud NAT.
    vpc_access {
      egress = "PRIVATE_RANGES_ONLY"
      network_interfaces {
        network    = google_compute_network.vpc.id
        subnetwork = google_compute_subnetwork.run.id
      }
    }

    containers {
      image = local.placeholder_image

      resources {
        limits = {
          cpu    = "1"
          memory = "1Gi"
        }
        cpu_idle          = each.value.cpu_idle
        startup_cpu_boost = true # Spring Boot khởi động nhanh hơn
      }

      dynamic "env" {
        for_each = merge(local.common_env, each.value.env)
        content {
          name  = env.key
          value = env.value
        }
      }

      env {
        name = "DB_PASSWORD"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.db_password.secret_id
            version = "latest"
          }
        }
      }
    }
  }

  depends_on = [
    google_secret_manager_secret_iam_member.db_pw,
    google_secret_manager_secret_version.db_password,
  ]

  lifecycle {
    ignore_changes = [template[0].containers[0].image, client, client_version]
  }
}

# ---------- Frontend (vị trí 3 deploy vào đây) ----------
resource "google_cloud_run_v2_service" "web" {
  name                = "bookstore-web"
  location            = var.region
  ingress             = "INGRESS_TRAFFIC_ALL"
  deletion_protection = false

  template {
    service_account = google_service_account.web.email
    scaling {
      min_instance_count = 0
      max_instance_count = 2
    }
    containers {
      image = local.placeholder_image
    }
  }

  lifecycle {
    ignore_changes = [template[0].containers[0].image, client, client_version]
  }
}

# ---------- Ai được gọi service nào ----------
resource "google_cloud_run_v2_service_iam_member" "api_public" {
  name     = google_cloud_run_v2_service.backend["api"].name
  location = var.region
  role     = "roles/run.invoker"
  member   = "allUsers" # quyền thật kiểm tra bằng JWT trong SecurityConfig
}

resource "google_cloud_run_v2_service_iam_member" "web_public" {
  name     = google_cloud_run_v2_service.web.name
  location = var.region
  role     = "roles/run.invoker"
  member   = "allUsers"
}

resource "google_cloud_run_v2_service_iam_member" "worker_invoker" {
  name     = google_cloud_run_v2_service.backend["worker"].name
  location = var.region
  role     = "roles/run.invoker"
  member   = "serviceAccount:${google_service_account.invoker.email}"
}
