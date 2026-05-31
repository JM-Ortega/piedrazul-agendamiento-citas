output "server_ip" {
  description = "IP pública del servidor"
  value       = module.server.ipv4_address
}

output "server_id" {
  description = "ID del servidor en Hetzner"
  value       = module.server.server_id
}

output "ssh_user" {
  description = "Usuario SSH para automatización"
  value       = module.server.ssh_user
}

output "frontend_fqdn" {
  description = "FQDN del frontend"
  value       = module.dns.frontend_fqdn
}

output "api_fqdn" {
  description = "FQDN del backend API"
  value       = module.dns.api_fqdn
}

output "auth_fqdn" {
  description = "FQDN del servicio de autenticación"
  value       = module.dns.auth_fqdn
}

output "pages_url" {
  description = "URL del proyecto en Cloudflare Pages"
  value       = module.pages.pages_url
}
