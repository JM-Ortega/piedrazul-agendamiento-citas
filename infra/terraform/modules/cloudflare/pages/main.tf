resource "cloudflare_pages_project" "main" {
  account_id        = var.account_id
  name              = var.project_name
  production_branch = var.production_branch

  source = {
    type = "github"
    config = {
      owner                          = var.github_owner
      repo_name                      = var.github_repo
      production_branch              = var.production_branch
      production_deployments_enabled = true
      preview_deployment_setting     = "all"
      pr_comments_enabled            = true
      path_includes                  = ["frontend/**"]
    }
  }

  build_config = {
    build_caching   = true
    build_command   = var.build_command
    destination_dir = var.output_dir
    root_dir        = var.root_dir
  }

  lifecycle {
    ignore_changes = [source]
  }
}

resource "cloudflare_pages_domain" "main" {
  count        = var.custom_domain != null && var.custom_domain != "" ? 1 : 0
  account_id   = var.account_id
  project_name = cloudflare_pages_project.main.name
  name         = var.custom_domain
}
