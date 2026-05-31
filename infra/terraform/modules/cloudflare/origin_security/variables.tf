variable "zone_id" {
  description = "ID de la zona en Cloudflare"
  type        = string
}

variable "enabled" {
  description = "Activar o desactivar Authenticated Origin Pulls"
  type        = bool
  default     = true
}
