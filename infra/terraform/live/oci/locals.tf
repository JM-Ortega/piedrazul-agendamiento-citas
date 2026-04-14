locals {
  # Availability Domain de Bogotá — único AD disponible en sa-bogota-1
  availability_domain = "ootp:SA-BOGOTA-1-AD-1"

  # Dominio del frontend con sufijo -oci para diferenciarlo de Hetzner
  frontend_domain = "${var.project}-oci.${var.base_domain}"

  # Fase 1 — SSH y web abiertos a todo el mundo
  # Fase 3 — enable_ssh = false con Tailscale
  ssh_sources = ["0.0.0.0/0"]

  # Fase 1 — web abierto a todo el mundo
  # Fase 2 — reemplazar con local.cloudflare_ips
  web_sources = ["0.0.0.0/0"]

  # Cloudflare IPs — usadas en Fase 2
  # Descomentar cuando Traefik esté configurado
  # cloudflare_ips = concat(
  #   data.cloudflare_ip_ranges.main.ipv4_cidr_blocks,
  #   data.cloudflare_ip_ranges.main.ipv6_cidr_blocks,
  # )
}
