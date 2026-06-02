output "ipv4_address" {
  description = "IP pública del servidor"
  value       = hcloud_server.main.ipv4_address
}

output "server_id" {
  description = "ID del servidor en Hetzner"
  value       = hcloud_server.main.id
}

output "server_name" {
  description = "Nombre del servidor en Hetzner"
  value       = hcloud_server.main.name
}

output "ssh_user" {
  description = "Usuario SSH para automatización — Ansible y CI/CD"
  value       = "ansible"
}
