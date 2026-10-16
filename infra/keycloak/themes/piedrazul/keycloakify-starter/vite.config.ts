import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { keycloakify } from "keycloakify/vite-plugin";

export default defineConfig({
    plugins: [
        react(),
        keycloakify({
            accountThemeImplementation: "none",
            keycloakifyBuildDirPath: "../../keycloak-theme",
            // Dirección pública del frontend Angular. Keycloak la resuelve en ejecución desde la
            // variable de entorno del contenedor (theme.properties: ${env.FRONTEND_PUBLIC_URL:}),
            // así el mismo JAR sirve en desarrollo y producción. Sin valor por defecto: si falta,
            // el tema oculta los enlaces al frontend en vez de inventar un destino.
            environmentVariables: [{ name: "FRONTEND_PUBLIC_URL", default: "" }]
        })
    ]
});
