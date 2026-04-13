output "frontend_fqdn" {
  description = "FQDN del frontend"
  value       = "${var.project}.${var.base_domain}"
}

output "api_fqdn" {
  description = "FQDN del backend API"
  value       = "api.${var.project}.${var.base_domain}"
}

output "auth_fqdn" {
  description = "FQDN del servicio de autenticación"
  value       = "auth.${var.project}.${var.base_domain}"
}
