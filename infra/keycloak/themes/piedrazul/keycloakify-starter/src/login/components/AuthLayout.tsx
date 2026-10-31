import { useEffect, type ReactNode } from "react";
import { useInitialize } from "keycloakify/login/Template.useInitialize";
import type { KcContext } from "../KcContext";
import type { I18n } from "../i18n";
import { getFrontendUrls } from "../frontendUrl";
import { Alert } from "./Alert";
import { CalendarIcon, HomeIcon, RotateCcwIcon, UserIcon } from "./icons";
import "../styles/auth.css";

/**
 * Estructura común de las páginas de autenticación: panel de marca + columna del formulario.
 *
 * Reemplaza a `keycloakify/login/Template` en las páginas propias y conserva su contrato:
 * `useInitialize` (authChecker.js con sondeo de sesión entre pestañas y `kcContext.scripts`),
 * título del documento, mensaje global de Keycloak (los `warning` se ocultan solo en acciones
 * iniciadas por la aplicación), usuario intentado con "reiniciar inicio de sesión" y
 * "probar de otra manera".
 */
export function AuthLayout(props: {
    kcContext: KcContext;
    i18n: I18n;
    eyebrow: string;
    title: string;
    subtitle?: ReactNode;
    /** Falso cuando la página muestra el error junto a su campo (evita duplicarlo). */
    displayMessage?: boolean;
    children: ReactNode;
}) {
    const {
        kcContext,
        i18n,
        eyebrow,
        title,
        subtitle,
        displayMessage = true,
        children
    } = props;

    const { msg, msgStr } = i18n;
    const { auth, url, message, isAppInitiatedAction } = kcContext;

    useEffect(() => {
        document.title = `${title} · ${msgStr("pzBrandName")}`;
    }, [title]);

    const { isReadyToRender } = useInitialize({ kcContext, doUseDefaultCss: false });

    if (!isReadyToRender) {
        return null;
    }

    const frontendUrls = getFrontendUrls(kcContext);
    const showMessage =
        displayMessage &&
        message !== undefined &&
        (message.type !== "warning" || !isAppInitiatedAction);
    const showAttemptedUsername =
        auth?.showUsername === true && !auth.showResetCredentials;

    return (
        <div className="pz-auth">
            <header className="pz-brand-panel">
                <div className="pz-brand-bar">
                    <div className="pz-brand">
                        <span className="pz-brand-mark">
                            <CalendarIcon size={24} />
                        </span>
                        <span className="pz-brand-text">
                            <span className="pz-brand-name">{msg("pzBrandName")}</span>
                            <span className="pz-brand-descriptor">
                                {msg("pzBrandDescriptor")}
                            </span>
                        </span>
                    </div>

                    {frontendUrls !== undefined && (
                        <a href={frontendUrls.home} className="pz-home-link">
                            <HomeIcon />
                            <span className="pz-home-link-text">{msg("pzHomeLink")}</span>
                        </a>
                    )}
                </div>

                <div className="pz-hero">
                    <p className="pz-hero-title">{msg("pzHeroTitle")}</p>
                    <p className="pz-hero-description">{msg("pzHeroDescription")}</p>
                </div>

                <ul className="pz-features">
                    <li>{msg("pzFeatureScheduling")}</li>
                    <li>{msg("pzFeatureRecords")}</li>
                    <li>{msg("pzFeatureRoles")}</li>
                </ul>
            </header>

            <main className="pz-form-panel">
                <div className="pz-form-container">
                    <div className="pz-form-header">
                        <p className="pz-eyebrow">{eyebrow}</p>
                        <h1 className="pz-title" id="kc-page-title">
                            {title}
                        </h1>
                        {subtitle !== undefined && (
                            <p className="pz-subtitle">{subtitle}</p>
                        )}
                    </div>

                    {showAttemptedUsername && (
                        <div id="kc-username" className="pz-attempted-user">
                            <UserIcon />
                            <span
                                id="kc-attempted-username"
                                className="pz-attempted-user-name"
                            >
                                {auth.attemptedUsername}
                            </span>
                            <a
                                id="reset-login"
                                className="pz-link pz-attempted-user-restart"
                                href={url.loginRestartFlowUrl}
                            >
                                <RotateCcwIcon size={14} />
                                {msg("restartLoginTooltip")}
                            </a>
                        </div>
                    )}

                    {showMessage && (
                        <Alert type={message.type} summary={message.summary} />
                    )}

                    {children}

                    {auth?.showTryAnotherWayLink && (
                        <form
                            id="kc-select-try-another-way-form"
                            className="pz-try-another-way"
                            action={url.loginAction}
                            method="post"
                        >
                            <input type="hidden" name="tryAnotherWay" value="on" />
                            <button
                                type="submit"
                                id="try-another-way"
                                className="pz-link-button"
                            >
                                {msg("doTryAnotherWay")}
                            </button>
                        </form>
                    )}
                </div>
            </main>
        </div>
    );
}
