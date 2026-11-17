# Thông số theo docs/HANDOVER.md mục 5.2
resource "google_pubsub_topic" "events" {
  name                       = "order-events"
  message_retention_duration = "86400s"
}

resource "google_pubsub_topic" "dlq" {
  name = "order-events-dlq"
}

resource "google_pubsub_subscription" "worker_push" {
  name                 = "order-events-worker"
  topic                = google_pubsub_topic.events.id
  ack_deadline_seconds = 30

  push_config {
    push_endpoint = "${google_cloud_run_v2_service.backend["worker"].uri}/internal/pubsub/push"
    oidc_token {
      service_account_email = google_service_account.invoker.email
      audience              = google_cloud_run_v2_service.backend["worker"].uri
    }
  }

  retry_policy {
    minimum_backoff = "1s"
    maximum_backoff = "10s"
  }

  dead_letter_policy {
    dead_letter_topic     = google_pubsub_topic.dlq.id
    max_delivery_attempts = 5
  }
}

# Pull subscription để QA xem / phát lại message (Test 3, HANDOVER mục 6.1)
resource "google_pubsub_subscription" "debug" {
  name                       = "order-events-debug"
  topic                      = google_pubsub_topic.events.id
  ack_deadline_seconds       = 10
  message_retention_duration = "86400s"
}

# Giữ message hỏng trong DLQ để operator xem
resource "google_pubsub_subscription" "dlq_debug" {
  name                       = "order-events-dlq-debug"
  topic                      = google_pubsub_topic.dlq.id
  ack_deadline_seconds       = 10
  message_retention_duration = "604800s"
}

# Pub/Sub service agent: chuyển message vào DLQ + tạo OIDC token thay sa-pubsub-invoker
resource "google_pubsub_topic_iam_member" "agent_dlq_pub" {
  topic  = google_pubsub_topic.dlq.id
  role   = "roles/pubsub.publisher"
  member = local.pubsub_agent
}

resource "google_pubsub_subscription_iam_member" "agent_sub" {
  subscription = google_pubsub_subscription.worker_push.id
  role         = "roles/pubsub.subscriber"
  member       = local.pubsub_agent
}

resource "google_service_account_iam_member" "agent_token" {
  service_account_id = google_service_account.invoker.name
  role               = "roles/iam.serviceAccountTokenCreator"
  member             = local.pubsub_agent
}
