# Authenticated Origin Pulls (Global AOP)
# Cloudflare presenta un certificado cliente al conectarse al origen.
# Traefik verifica ese certificado contra el CA de Cloudflare.
# Requiere que Traefik esté configurado con tls.options=cloudflare-aop@file
# antes de activar este setting.
resource "cloudflare_zone_setting" "tls_client_auth" {
  zone_id    = var.zone_id
  setting_id = "tls_client_auth"
  value      = var.enabled ? "on" : "off"
}
