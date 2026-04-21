# ── Red — primero, expone default_security_list_id ───────────────────────────

module "network" {
  source         = "../../modules/oci/network"
  compartment_id = var.oci_compartment_id
  project        = var.project
}

# ── Seguridad — modifica la default security list de la VCN ──────────────────

module "security" {
  source                   = "../../modules/oci/security"
  project                  = var.project
  default_security_list_id = module.network.default_security_list_id
  ssh_sources              = local.ssh_sources
  web_sources              = local.web_sources
  enable_ssh               = true
  enable_icmp              = true
}

# ── Servidor ARM ──────────────────────────────────────────────────────────────

module "server" {
  source = "../../modules/oci/server"

  compartment_id      = var.oci_compartment_id
  project             = var.project
  subnet_id           = module.network.subnet_id
  availability_domain = local.availability_domain

  ssh_public_keys = [var.ansible_ssh_public_key]

  user_data = templatefile("${path.module}/../../modules/shared/templates/cloud-init.tftpl", {
    ssh_public_keys    = [var.ansible_ssh_public_key]
    ops_ssh_public_key = var.ops_ssh_public_key
  })
}

# ── DNS — apunta los subdominios al servidor OCI ──────────────────────────────

module "dns" {
  source = "../../modules/dns"

  zone_id        = var.cloudflare_zone_id
  project        = "${var.project}-oci"
  base_domain    = var.base_domain
  server_ip      = module.server.ipv4_address
  pages_hostname = module.pages.pages_hostname
}

# ── Pages — frontend en Cloudflare ───────────────────────────────────────────

module "pages" {
  source = "../../modules/pages"

  account_id    = var.cloudflare_account_id
  project_name  = "${var.project}-oci"
  github_owner  = var.github_owner
  github_repo   = var.github_repo
  custom_domain = local.frontend_domain
}
