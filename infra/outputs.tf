output "api_url" {
  value = google_cloud_run_v2_service.backend["api"].uri
}
output "worker_url" {
  value = google_cloud_run_v2_service.backend["worker"].uri
}
output "web_url" {
  value = google_cloud_run_v2_service.web.uri
}
output "sql_private_ip" {
  value = google_sql_database_instance.pg.private_ip_address
}
output "private_bucket" {
  value = google_storage_bucket.private.name
}
output "REGISTRY" {
  value = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.repo.repository_id}"
}
output "WIF_PROVIDER" {
  value = google_iam_workload_identity_pool_provider.github.name
}
output "DEPLOY_SA" {
  value = google_service_account.deployer.email
}
