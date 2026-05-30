terraform {
  required_version = ">= 1.9"

  cloud {
    organization = "Piedrazul"
    workspaces {
      name = "piedrazul-oci"
    }
  }

  required_providers {
    oci = {
      source  = "oracle/oci"
      version = "~> 8.9"
    }

    cloudflare = {
      source = "cloudflare/cloudflare"
      # v5 aún presenta inestabilidad en algunos recursos (ej. zone settings)
      # se restringe a un rango estable de parches
      version = "~> 5.18"
    }
  }
}
