variable "zone_id" {
  description = "Zone ID del dominio en Cloudflare"
  type        = string
}

variable "server_ip" {
  description = "IP pública del servidor para los registros A"
  type        = string
}

variable "project" {
  description = "Nombre del proyecto — usado para construir los subdominios"
  type        = string
}

variable "base_domain" {
  description = "Dominio base para los registros DNS"
  type        = string
}

variable "pages_hostname" {
  description = "Hostname pages.dev del proyecto frontend (ej. piedrazul.pages.dev)"
  type        = string
}

variable "proxied_backend" {
  description = "Si los registros api y auth usan el proxy de Cloudflare"
  type        = bool
  default     = false
}
