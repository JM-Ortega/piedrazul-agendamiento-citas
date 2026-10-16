import type { PageProps } from "keycloakify/login/pages/PageProps";
import type { KcContext } from "../KcContext";
import type { I18n } from "../i18n";
import { AuthLayout } from "../components/AuthLayout";
import { RotateCcwIcon } from "../components/icons";

/**
 * Formulario vencido (volver atrás o reenviar tras avanzar el flujo). Ofrece las dos salidas
 * de Keycloak: continuar el flujo actual (`url.loginAction`) o reiniciarlo
 * (`url.loginRestartFlowUrl`).
 */
export default function LoginPageExpired(props: PageProps<Extract<KcContext, { pageId: "login-page-expired.ftl" }>, I18n>) {
    const { kcContext, i18n } = props;

    const { url } = kcContext;

    const { msg, msgStr } = i18n;

    return (
        <AuthLayout
            kcContext={kcContext}
            i18n={i18n}
            eyebrow={msgStr("pzLoginEyebrow")}
            title={msgStr("pageExpiredTitle")}
            subtitle={msg("pzExpiredSubtitle")}
        >
            <div className="pz-actions">
                <a id="loginContinueLink" className="pz-button pz-button-primary" href={url.loginAction}>
                    {msg("pzExpiredContinue")}
                </a>
                <a id="loginRestartLink" className="pz-button pz-button-secondary" href={url.loginRestartFlowUrl}>
                    <RotateCcwIcon />
                    {msg("restartLoginTooltip")}
                </a>
            </div>
        </AuthLayout>
    );
}
