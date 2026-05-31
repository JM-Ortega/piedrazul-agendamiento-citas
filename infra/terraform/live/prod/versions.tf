terraform {
  # Fijado a 1.14.x para garantizar consistencia entre local, CI y HCP
  required_version = "~> 1.14.0"

  required_providers {
    hcloud = {
      source = "hetznercloud/hcloud"
      # Rama estable reciente, evita saltos a versiones menores nuevas
      version = "~> 1.60"
    }

    cloudflare = {
      source = "cloudflare/cloudflare"
      # v5 aún presenta inestabilidad en algunos recursos (ej. zone settings)
      # se restringe a un rango estable de parches
      version = "~> 5.18"
    }
  }

  cloud {
    organization = "Piedrazul"

    workspaces {
      name = "piedrazul-hetzner"
    }
  }
}
