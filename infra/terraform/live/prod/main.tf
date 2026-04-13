module "firewall" {
  source = "../../modules/firewall"

  name        = local.firewall_name
  ssh_sources = ["0.0.0.0/0", "::/0"]
  web_sources = ["0.0.0.0/0", "::/0"]
}

module "server" {
  source = "../../modules/server"

  name               = local.server_name
  server_type        = var.server_type
  location           = var.location
  image              = var.image
  ssh_public_keys    = [var.ansible_ssh_public_key]
  ops_ssh_public_key = var.ops_ssh_public_key
  firewall_ids       = [module.firewall.firewall_id]
}

module "pages" {
  source = "../../modules/pages"

  account_id        = var.cloudflare_account_id
  project_name      = local.pages_name
  github_owner      = var.github_owner
  github_repo       = var.github_repo
  production_branch = "main"
  build_command     = "npm run build"
  output_dir        = "dist/frontend"
  root_dir          = "frontend"
  custom_domain     = local.frontend_domain
}

module "dns" {
  source = "../../modules/dns"

  zone_id         = var.cloudflare_zone_id
  server_ip       = module.server.ipv4_address
  project         = var.project
  base_domain     = var.base_domain
  pages_hostname  = module.pages.pages_hostname
  proxied_backend = false
}
