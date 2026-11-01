import type { PageProps } from "keycloakify/login/pages/PageProps";
import type { KcContext } from "../KcContext";
import type { I18n } from "../i18n";
import { AuthLayout } from "../components/AuthLayout";
import { Alert } from "../components/Alert";
import { getFrontendUrls } from "../frontendUrl";

/**
 * Error de Keycloak (p. ej. enlace de recuperación usado, alterado o vencido). Muestra el
 * mensaje tal como llega y, si Keycloak la entrega, la vuelta a la aplicación; si no la
 * entrega, ofrece ir al frontend configurado. Con `skipLink` no se muestra ninguna salida.
 */
export default function Error(props: PageProps<Extract<KcContext, { pageId: "error.ftl" }>, I18n>) {
    const { kcContext, i18n } = props;

    const { message, client, skipLink } = kcContext;

    const { msg, msgStr } = i18n;

    const frontendUrls = skipLink || client?.baseUrl ? undefined : getFrontendUrls(kcContext);

    return (
        <AuthLayout kcContext={kcContext} i18n={i18n} eyebrow={msgStr("pzErrorEyebrow")} title={msgStr("errorTitle")} displayMessage={false}>
            <div id="kc-error-message">
                <Alert type="error" summary={message.summary} />
                {!skipLink && client?.baseUrl && (
                    <a id="backToApplication" className="pz-button pz-button-primary" href={client.baseUrl}>
                        {msg("backToApplication")}
                    </a>
                )}
                {frontendUrls !== undefined && (
                    <a className="pz-button pz-button-primary" href={frontendUrls.home}>
                        {msg("pzGoToFrontend")}
                    </a>
                )}
            </div>
        </AuthLayout>
    );
}
