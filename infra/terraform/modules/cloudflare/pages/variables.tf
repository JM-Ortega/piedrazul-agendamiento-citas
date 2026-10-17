variable "account_id" {
  description = "Cloudflare Account ID"
  type        = string
}

variable "project_name" {
  description = "Nombre del proyecto en Cloudflare Pages"
  type        = string
}

variable "production_branch" {
  description = "Rama de producción del proyecto (la que wrangler publica como producción)"
  type        = string
  default     = "main"
}

variable "custom_domain" {
  description = "Dominio propio del proyecto Pages"
  type        = string

  validation {
    condition     = can(regex("^([a-z0-9-]+\\.)+[a-z]{2,}$", var.custom_domain))
    error_message = "custom_domain debe ser un nombre de dominio."
  }
}

variable "custom_domain_dns_record_id" {
  description = "ID del registro DNS (módulo dns) que apunta custom_domain al subdominio *.pages.dev; ordena la asociación después del registro"
  type        = string
}
