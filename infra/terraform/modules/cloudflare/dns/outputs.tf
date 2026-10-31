output "frontend_fqdn" {
  description = "FQDN del frontend"
  value       = var.base_domain
}

output "api_fqdn" {
  description = "FQDN del backend API"
  value       = "api.${var.base_domain}"
}

output "auth_fqdn" {
  description = "FQDN del servicio de autenticación"
  value       = "auth.${var.base_domain}"
}

output "frontend_record_id" {
  description = "ID del registro DNS del frontend (CNAME al subdominio *.pages.dev); ordena la asociación del dominio propio en Pages"
  value       = cloudflare_dns_record.frontend.id
}
