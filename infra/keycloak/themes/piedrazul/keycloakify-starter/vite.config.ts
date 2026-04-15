import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { keycloakify } from "keycloakify/vite-plugin";

export default defineConfig({
    plugins: [
        react(),
        keycloakify({
            accountThemeImplementation: "none",
            keycloakifyBuildDirPath: "../../dist/keycloak-theme",
            environmentVariables: [
              { name: "REGISTER_URL", default: "http://localhost:4200/registro" }
]
        })
    ]
});
