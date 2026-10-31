import type { KcContext } from "./KcContext";

/**
 * Cliente OIDC del frontend Angular (frontend/src/environments). El enlace de registro de
 * pacientes solo tiene sentido cuando el login lo pidió ese cliente, no la consola de cuenta
 * u otros clientes del realm.
 */
export const PATIENT_APP_CLIENT_ID = "piedrazul-frontend";

/** Ruta de registro de pacientes del frontend Angular (frontend/src/app/app.routes.ts). */
const PATIENT_REGISTRATION_ROUTE = "registro";

export type FrontendUrls = {
    /** Inicio del frontend. */
    home: string;
    /** Registro de pacientes del frontend (no el registro nativo de Keycloak). */
    patientRegistration: string;
};

/**
 * Resuelve las URL del frontend desde `FRONTEND_PUBLIC_URL`, una propiedad del tema que
 * Keycloak toma de la variable de entorno del contenedor en ejecución.
 *
 * Solo acepta una URL absoluta `http`/`https` sin credenciales, consulta ni fragmento; admite
 * una ruta base (p. ej. `https://dominio/app`). Si falta o es inválida devuelve `undefined`
 * y lo informa en la consola: las páginas ocultan esos enlaces en vez de usar otro destino.
 */
export function getFrontendUrls(
    kcContext: Pick<KcContext, "properties">
): FrontendUrls | undefined {
    const raw = (kcContext.properties.FRONTEND_PUBLIC_URL ?? "").trim();

    const base = parseFrontendBaseUrl(raw);

    if (base === undefined) {
        reportInvalidConfiguration(raw);
        return undefined;
    }

    return {
        home: base.href,
        patientRegistration: new URL(PATIENT_REGISTRATION_ROUTE, base).href
    };
}

function parseFrontendBaseUrl(raw: string): URL | undefined {
    if (raw === "") {
        return undefined;
    }

    let url: URL;

    try {
        url = new URL(raw);
    } catch {
        return undefined;
    }

    if (url.protocol !== "https:" && url.protocol !== "http:") {
        return undefined;
    }

    if (
        url.username !== "" ||
        url.password !== "" ||
        url.search !== "" ||
        url.hash !== ""
    ) {
        return undefined;
    }

    // La ruta base termina en "/" para que las rutas relativas se agreguen debajo de ella
    if (!url.pathname.endsWith("/")) {
        url.pathname = `${url.pathname}/`;
    }

    return url;
}

let isInvalidConfigurationReported = false;

function reportInvalidConfiguration(raw: string) {
    if (isInvalidConfigurationReported) {
        return;
    }
    isInvalidConfigurationReported = true;

    console.error(
        raw === ""
            ? "[piedrazul-theme] FRONTEND_PUBLIC_URL no está definida en el contenedor de Keycloak: se ocultan los enlaces al frontend."
            : `[piedrazul-theme] FRONTEND_PUBLIC_URL inválida (${JSON.stringify(raw)}): se esperaba una URL http(s) absoluta sin credenciales, consulta ni fragmento. Se ocultan los enlaces al frontend.`
    );
}
