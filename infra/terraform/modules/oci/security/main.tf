resource "oci_core_default_security_list" "main" {
  manage_default_resource_id = var.default_security_list_id
  display_name               = "${var.project}-security-list"

  # SSH - opcional, desactivar en Fase 3 con Tailscale
  dynamic "ingress_security_rules" {
    for_each = var.enable_ssh ? var.ssh_sources : []
    content {
      protocol    = "6" # TCP (IANA protocol number)
      source      = ingress_security_rules.value
      source_type = "CIDR_BLOCK"
      stateless   = false

      tcp_options {
        min = 22
        max = 22
      }
    }
  }

  # HTTP - Fase 1 abierto, Fase 2 solo Cloudflare
  dynamic "ingress_security_rules" {
    for_each = var.web_sources
    content {
      protocol    = "6" # TCP (IANA protocol number)
      source      = ingress_security_rules.value
      source_type = "CIDR_BLOCK"
      stateless   = false

      tcp_options {
        min = 80
        max = 80
      }
    }
  }

  # HTTPS - Fase 1 abierto, Fase 2 solo Cloudflare
  dynamic "ingress_security_rules" {
    for_each = var.web_sources
    content {
      protocol    = "6" # TCP (IANA protocol number)
      source      = ingress_security_rules.value
      source_type = "CIDR_BLOCK"
      stateless   = false

      tcp_options {
        min = 443
        max = 443
      }
    }
  }

  # ICMP entrante - opcional para diagnóstico
  dynamic "ingress_security_rules" {
    for_each = var.enable_icmp ? [1] : []
    content {
      protocol    = "1" # ICMP (IANA protocol number
      source      = "0.0.0.0/0"
      source_type = "CIDR_BLOCK"
      stateless   = false
    }
  }

  # Egress - todo el tráfico saliente abierto
  # necesario para apt, Docker, APIs externas
  # stateful: no requiere reglas de retorno para ingress
  egress_security_rules {
    protocol         = "all" # todos los protocolos
    destination      = "0.0.0.0/0"
    destination_type = "CIDR_BLOCK"
    stateless        = false
  }
}
