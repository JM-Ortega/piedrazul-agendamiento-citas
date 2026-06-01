variable "project" {
  description = "Nombre del proyecto — usado para nombrar los recursos"
  type        = string
}

variable "default_security_list_id" {
  description = "ID de la security list por defecto de la VCN a modificar"
  type        = string
}

variable "ssh_sources" {
  description = "CIDRs permitidos para SSH — abierto en Fase 1, restringido en Fase 3"
  type        = list(string)
}

variable "web_sources" {
  description = "CIDRs permitidos para tráfico HTTP y HTTPS (puertos 80 y 443)"
  type        = list(string)
}

variable "enable_ssh" {
  description = "Habilitar acceso SSH entrante — desactivar en Fase 3 con Tailscale"
  type        = bool
  default     = true
}

variable "enable_icmp" {
  description = "Habilitar ICMP entrante para diagnóstico"
  type        = bool
  default     = true
}
