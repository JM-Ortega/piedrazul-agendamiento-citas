locals {
  # Convención de nombres para recursos
  server_name   = "${var.project}-server"
  firewall_name = "${var.project}-firewall"
  pages_name    = var.project

  # Dominios derivados a partir de base_domain
  frontend_domain = "${var.project}.${var.base_domain}"
  api_domain      = "api.${var.project}.${var.base_domain}"
  auth_domain     = "auth.${var.project}.${var.base_domain}"
}
