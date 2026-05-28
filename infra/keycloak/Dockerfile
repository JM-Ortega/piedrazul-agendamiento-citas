FROM quay.io/keycloak/keycloak:26.5.6

# Incluyr los datos de importación y los proveedores dentro de la imagen
COPY infra/keycloak/realm /opt/keycloak/data/import
COPY infra/keycloak/themes/dist/keycloak-theme/keycloak-theme-for-kc-all-other-versions.jar /opt/keycloak/providers/keycloak-theme.jar