variable "account_id" {
  description = "Cloudflare Account ID"
  type        = string
}

variable "project_name" {
  description = "Nombre del proyecto en Cloudflare Pages"
  type        = string
}

variable "github_owner" {
  description = "Propietario del repositorio en GitHub (usuario u organización)"
  type        = string
}

variable "github_repo" {
  description = "Nombre del repositorio en GitHub"
  type        = string
}

variable "production_branch" {
  description = "Rama de producción para despliegues automáticos"
  type        = string
  default     = "main"
}

variable "build_command" {
  description = "Comando de build del frontend"
  type        = string
  default     = "npm run build"
}

variable "output_dir" {
  description = "Directorio de salida del build"
  type        = string
  default     = "dist/frontend"
}

variable "root_dir" {
  description = "Directorio raíz del frontend dentro del repositorio"
  type        = string
  default     = "frontend"
}

variable "custom_domain" {
  description = "Dominio personalizado del proyecto Pages"
  type        = string
}
