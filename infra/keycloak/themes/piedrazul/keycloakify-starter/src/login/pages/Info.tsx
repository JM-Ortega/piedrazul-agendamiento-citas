import type { PageProps } from "keycloakify/login/pages/PageProps";
import type { KcContext } from "../KcContext";
import type { I18n } from "../i18n";
import { AuthLayout } from "../components/AuthLayout";
import { Alert } from "../components/Alert";
import { getFrontendUrls } from "../frontendUrl";

/**
 * Aviso de Keycloak (p. ej. "Cuenta actualizada" al terminar la recuperación). Solo ofrece
 * la continuación que Keycloak entrega, en el mismo orden de prioridad que la página por
 * defecto: ninguna con `skipLink`, luego `pageRedirectUri`, `actionUri` y `client.baseUrl`.
 * Solo si Keycloak no entrega ninguna (y no la suprime con `skipLink`) se ofrece ir al
 * frontend configurado en `FRONTEND_PUBLIC_URL`.
 */
export default function Info(props: PageProps<Extract<KcContext, { pageId: "info.ftl" }>, I18n>) {
    const { kcContext, i18n } = props;

    const { advancedMsgStr, msg, msgStr } = i18n;

    const { messageHeader, message, requiredActions, skipLink, pageRedirectUri, actionUri, client } = kcContext;

    let summary = message.summary?.trim() ?? "";

    if (requiredActions) {
        summary += ` <b>${requiredActions.map(requiredAction => advancedMsgStr(`requiredAction.${requiredAction}`)).join(", ")}</b>`;
    }

    const continuation = (() => {
        if (skipLink) {
            return undefined;
        }
        if (pageRedirectUri) {
            return { href: pageRedirectUri, label: msg("backToApplication") };
        }
        if (actionUri) {
            return { href: actionUri, label: msg("proceedWithAction") };
        }
        if (client.baseUrl) {
            return { href: client.baseUrl, label: msg("backToApplication") };
        }
        const frontendUrls = getFrontendUrls(kcContext);
        if (frontendUrls !== undefined) {
            return { href: frontendUrls.home, label: msg("pzGoToFrontend") };
        }
        return undefined;
    })();

    return (
        <AuthLayout
            kcContext={kcContext}
            i18n={i18n}
            eyebrow={msgStr("pzInfoEyebrow")}
            title={messageHeader ? advancedMsgStr(messageHeader) : msgStr("pzInfoTitle")}
            displayMessage={false}
        >
            <div id="kc-info-message">
                <Alert type={message.type} summary={summary} />
                {continuation !== undefined && (
                    <a className="pz-button pz-button-primary" href={continuation.href}>
                        {continuation.label}
                    </a>
                )}
            </div>
        </AuthLayout>
    );
}
