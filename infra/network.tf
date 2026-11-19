# VPC riêng, không dùng default network
resource "google_compute_network" "vpc" {
  name                    = "bookstore-vpc"
  auto_create_subnetworks = false
}

resource "google_compute_subnetwork" "run" {
  name                     = "run-subnet"
  ip_cidr_range            = "10.10.0.0/24"
  region                   = var.region
  network                  = google_compute_network.vpc.id
  private_ip_google_access = true
}

# Private Service Access: cấp 1 dải IP để Google đặt Cloud SQL vào, rồi peering với VPC
resource "google_compute_global_address" "psa" {
  name          = "psa-range"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  prefix_length = 20
  network       = google_compute_network.vpc.id
}

resource "google_service_networking_connection" "psa" {
  network                 = google_compute_network.vpc.id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.psa.name]
}
