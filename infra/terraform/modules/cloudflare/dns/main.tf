resource "cloudflare_dns_record" "frontend" {
  zone_id = var.zone_id
  name    = var.base_domain
  content = var.pages_hostname
  type    = "CNAME"
  ttl     = 1
  proxied = true
  comment = "Frontend"
}

resource "cloudflare_dns_record" "api" {
  zone_id = var.zone_id
  name    = "api.${var.base_domain}"
  content = var.server_ip
  type    = "A"
  ttl     = var.proxied_backend ? 1 : 300
  proxied = var.proxied_backend
  comment = "Backend API"
}

resource "cloudflare_dns_record" "auth" {
  zone_id = var.zone_id
  name    = "auth.${var.base_domain}"
  content = var.server_ip
  type    = "A"
  ttl     = var.proxied_backend ? 1 : 300
  proxied = var.proxied_backend
  comment = "Autenticación"
}

resource "cloudflare_zone_setting" "ssl" {
  zone_id    = var.zone_id
  setting_id = "ssl"
  value      = "strict"
}

resource "cloudflare_zone_setting" "always_https" {
  zone_id    = var.zone_id
  setting_id = "always_use_https"
  value      = "on"
}
