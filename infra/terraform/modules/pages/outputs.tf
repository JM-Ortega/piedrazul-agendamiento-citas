output "pages_hostname" {
  description = "Hostname pages.dev del proyecto"
  value       = "${cloudflare_pages_project.main.subdomain}.pages.dev"
}

output "pages_url" {
  description = "URL del proyecto en Cloudflare Pages"
  value       = "https://${cloudflare_pages_project.main.subdomain}.pages.dev"
}

output "project_name" {
  description = "Nombre del proyecto en Cloudflare Pages"
  value       = cloudflare_pages_project.main.name
}
