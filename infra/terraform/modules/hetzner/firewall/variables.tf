variable "name" {
  description = "Nombre del firewall en Hetzner"
  type        = string
}

variable "ssh_sources" {
  description = "CIDRs permitidos para SSH"
  type        = list(string)
}

variable "web_sources" {
  description = "CIDRs permitidos para HTTP y HTTPS"
  type        = list(string)
}

variable "enable_ssh" {
  description = "Habilitar acceso SSH entrante"
  type        = bool
  default     = true
}

variable "enable_icmp" {
  description = "Habilitar ICMP entrante para diagnóstico"
  type        = bool
  default     = true
}
