import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { keycloakify } from "keycloakify/vite-plugin";

export default defineConfig({
    plugins: [
        react(),
        keycloakify({
            accountThemeImplementation: "none",
            keycloakifyBuildDirPath: "../../keycloak-theme",
            environmentVariables: [
                { name: "REGISTER_URL", default: "https://piedrazul.org/registro" },
                { name: "HOME_URL", default: "https://piedrazul.org" }
            ]
        })
    ]
});
