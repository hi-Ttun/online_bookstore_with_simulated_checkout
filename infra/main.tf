terraform {
  required_version = ">= 1.5"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }
  # Bucket truyền vào lúc init: terraform init -backend-config="bucket=<PROJECT_ID>-tfstate"
  backend "gcs" {
    prefix = "bookstore"
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
}

data "google_project" "this" {}

locals {
  # Image giữ chỗ của Google cho lần tạo đầu. CI/CD thay bằng image backend thật;
  # Terraform bỏ qua thay đổi image (lifecycle.ignore_changes) để không ghi đè bản CI đã deploy.
  placeholder_image = "us-docker.pkg.dev/cloudrun/container/hello"
  pubsub_agent      = "serviceAccount:service-${data.google_project.this.number}@gcp-sa-pubsub.iam.gserviceaccount.com"
}

# ---------- Artifact Registry ----------
resource "google_artifact_registry_repository" "repo" {
  location      = var.region
  repository_id = "bookstore"
  format        = "DOCKER"
}

# ---------- Bucket riêng tư (seed SQL, file nội bộ). Không bao giờ public. ----------
resource "google_storage_bucket" "private" {
  name                        = "${var.project_id}-bookstore-private"
  location                    = var.region
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  force_destroy               = true
}

# Cloud SQL đọc được file seed để chạy `gcloud sql import sql`
resource "google_storage_bucket_iam_member" "sql_reads_seed" {
  bucket = google_storage_bucket.private.name
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${google_sql_database_instance.pg.service_account_email_address}"
}
