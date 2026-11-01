# Proyecto de Pages de subida directa (Direct Upload): sin `source` (sin integración
# Git, sin builds de Cloudflare ni previews automáticas) y sin `build_config` (Cloudflare
# no construye nada). El único publicador es wrangler desde la cadena de producción
# (.github/scripts/pages-release.sh), que sube el artefacto ya validado.
# Cloudflare no permite convertir después un proyecto de subida directa en uno con Git
# (ni al revés): el tipo queda fijado al crearlo.
resource "cloudflare_pages_project" "main" {
  account_id        = var.account_id
  name              = var.project_name
  production_branch = var.production_branch
}

# Asociación del dominio propio. Va después del registro DNS que la sirve (CNAME del
# dominio al subdominio *.pages.dev del proyecto, del que es dueño el módulo dns): así la
# API de Pages encuentra el registro ya creado y Terraform nunca compite con un registro
# que la propia asociación pudiera crear. La precondición referencia el id del registro:
# esa referencia es la que ordena el grafo (proyecto → registro DNS → asociación).
resource "cloudflare_pages_domain" "main" {
  account_id   = var.account_id
  project_name = cloudflare_pages_project.main.name
  name         = var.custom_domain

  lifecycle {
    precondition {
      condition     = var.custom_domain_dns_record_id != ""
      error_message = "La asociación del dominio propio requiere el registro DNS que lo apunta al proyecto."
    }
  }
}
