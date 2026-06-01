resource "hcloud_firewall" "main" {
  name = var.name

  # SSH opcional; desactivar cuando el acceso administrativo pase a Tailscale
  dynamic "rule" {
    for_each = var.enable_ssh ? [1] : []

    content {
      direction   = "in"
      protocol    = "tcp"
      port        = "22"
      source_ips  = var.ssh_sources
      description = "SSH for automation and administrative access"
    }
  }

  # Reglas de entrada para tráfico web
  rule {
    direction   = "in"
    protocol    = "tcp"
    port        = "80"
    source_ips  = var.web_sources
    description = "HTTP"
  }

  rule {
    direction   = "in"
    protocol    = "tcp"
    port        = "443"
    source_ips  = var.web_sources
    description = "HTTPS"
  }

  # ICMP opcional para diagnóstico de red
  dynamic "rule" {
    for_each = var.enable_icmp ? [1] : []

    content {
      direction   = "in"
      protocol    = "icmp"
      source_ips  = ["0.0.0.0/0", "::/0"]
      description = "Inbound ICMP for diagnostics"
    }
  }

  # Reglas de salida para conectividad del sistema
  rule {
    direction       = "out"
    protocol        = "tcp"
    port            = "1-65535"
    destination_ips = ["0.0.0.0/0", "::/0"]
    description     = "Outbound TCP"
  }

  rule {
    direction       = "out"
    protocol        = "udp"
    port            = "1-65535"
    destination_ips = ["0.0.0.0/0", "::/0"]
    description     = "Outbound UDP"
  }

  rule {
    direction       = "out"
    protocol        = "icmp"
    destination_ips = ["0.0.0.0/0", "::/0"]
    description     = "Outbound ICMP"
  }
}
