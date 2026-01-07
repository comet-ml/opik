output "release_name" {
  description = "Helm release name"
  value       = helm_release.esnode_core.name
}

output "namespace" {
  description = "Namespace where esnode-core is deployed"
  value       = helm_release.esnode_core.namespace
}

output "service_name" {
  description = "Service name for metrics scraping"
  value       = helm_release.esnode_core.name
}
