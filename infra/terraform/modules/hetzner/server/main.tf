resource "hcloud_server" "main" {
  # Identificación y configuración base del servidor
  name        = var.name
  server_type = var.server_type
  location    = var.location
  image       = var.image

  # Asociación de firewalls definidos en infraestructura
  firewall_ids = var.firewall_ids

  # Bootstrap inicial del servidor usando cloud-init (template de Terraform)
  user_data = templatefile("${path.module}/../../shared/templates/cloud-init.tftpl", {
    ssh_public_keys    = var.ssh_public_keys
    ops_ssh_public_key = var.ops_ssh_public_key
  })

  public_net {
    # Exponer IPv4 público para acceso inicial
    ipv4_enabled = true

    # IPv6 deshabilitado por ahora (no se gestiona en esta fase)
    ipv6_enabled = false
  }

  lifecycle {
    # Evitar recreación del servidor por cambios posteriores en cloud-init
    # cloud-init solo se ejecuta en el primer arranque
    ignore_changes = [user_data]
  }
}
