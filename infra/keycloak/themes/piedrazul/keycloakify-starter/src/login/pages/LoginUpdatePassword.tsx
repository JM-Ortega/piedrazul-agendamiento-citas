import type { PageProps } from "keycloakify/login/pages/PageProps";
import type { KcContext } from "../KcContext";
import type { I18n } from "../i18n";
import { AuthLayout } from "../components/AuthLayout";
import { FormField } from "../components/FormField";
import { getFieldErrorId } from "../components/fieldErrorId";
import { PasswordInput } from "../components/PasswordInput";

/**
 * Cambio de contraseña: lo usan la acción requerida UPDATE_PASSWORD (contraseña temporal),
 * el enlace de recuperación y la acción iniciada por la aplicación (`isAppInitiatedAction`,
 * única que ofrece cancelar). Keycloak valida en el servidor (vacía, no coincide, política)
 * y devuelve el error en `password` o `password-confirm`.
 */
export default function LoginUpdatePassword(props: PageProps<Extract<KcContext, { pageId: "login-update-password.ftl" }>, I18n>) {
    const { kcContext, i18n } = props;

    const { url, messagesPerField, isAppInitiatedAction } = kcContext;

    const { msg, msgStr } = i18n;

    const hasError = messagesPerField.existsError("password", "password-confirm");
    const passwordError = messagesPerField.existsError("password") ? messagesPerField.get("password") : undefined;
    const confirmError = messagesPerField.existsError("password-confirm") ? messagesPerField.get("password-confirm") : undefined;

    return (
        <AuthLayout
            kcContext={kcContext}
            i18n={i18n}
            eyebrow={msgStr("pzUpdateEyebrow")}
            title={msgStr("pzUpdateTitle")}
            subtitle={msg("pzUpdateSubtitle")}
            displayMessage={!hasError}
        >
            <form id="kc-passwd-update-form" className="pz-form" action={url.loginAction} method="post">
                <FormField inputId="password-new" label={msgStr("passwordNew")} error={passwordError}>
                    <PasswordInput i18n={i18n} passwordInputId="password-new">
                        <input
                            type="password"
                            id="password-new"
                            name="password-new"
                            className="pz-input"
                            autoFocus
                            autoComplete="new-password"
                            aria-invalid={passwordError !== undefined}
                            aria-describedby={passwordError !== undefined ? getFieldErrorId("password-new") : undefined}
                        />
                    </PasswordInput>
                </FormField>

                <FormField inputId="password-confirm" label={msgStr("passwordConfirm")} error={confirmError}>
                    <PasswordInput i18n={i18n} passwordInputId="password-confirm">
                        <input
                            type="password"
                            id="password-confirm"
                            name="password-confirm"
                            className="pz-input"
                            autoComplete="new-password"
                            aria-invalid={confirmError !== undefined}
                            aria-describedby={confirmError !== undefined ? getFieldErrorId("password-confirm") : undefined}
                        />
                    </PasswordInput>
                </FormField>

                <label className="pz-checkbox">
                    <input type="checkbox" id="logout-sessions" name="logout-sessions" value="on" defaultChecked={true} />
                    <span>{msg("logoutOtherSessions")}</span>
                </label>

                <div className="pz-actions">
                    <button className="pz-button pz-button-primary" type="submit">
                        {msgStr("pzUpdateSubmit")}
                    </button>
                    {isAppInitiatedAction && (
                        <button className="pz-button pz-button-secondary" type="submit" name="cancel-aia" value="true">
                            {msg("doCancel")}
                        </button>
                    )}
                </div>
            </form>
        </AuthLayout>
    );
}
