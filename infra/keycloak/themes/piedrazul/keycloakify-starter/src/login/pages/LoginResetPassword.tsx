import type { PageProps } from "keycloakify/login/pages/PageProps";
import type { KcContext } from "../KcContext";
import { getUsernameLabel, type I18n } from "../i18n";
import { AuthLayout } from "../components/AuthLayout";
import { FormField } from "../components/FormField";
import { getFieldErrorId } from "../components/fieldErrorId";
import { ArrowLeftIcon } from "../components/icons";

/**
 * Solicitud de recuperación. Keycloak responde lo mismo exista o no la cuenta (vuelve al
 * login con el mensaje de correo enviado), así que la página no debe insinuar lo contrario.
 */
export default function LoginResetPassword(props: PageProps<Extract<KcContext, { pageId: "login-reset-password.ftl" }>, I18n>) {
    const { kcContext, i18n } = props;

    const { url, realm, auth, messagesPerField } = kcContext;

    const { msg, msgStr } = i18n;

    const hasError = messagesPerField.existsError("username");

    // Con documento como usuario, las instrucciones de Keycloak ("usuario o email") no aplican
    const instruction = !realm.loginWithEmailAllowed
        ? msg("pzResetInstructionDocument")
        : realm.duplicateEmailsAllowed
          ? msg("emailInstructionUsername")
          : msg("emailInstruction");

    return (
        <AuthLayout
            kcContext={kcContext}
            i18n={i18n}
            eyebrow={msgStr("pzResetEyebrow")}
            title={msgStr("emailForgotTitle")}
            subtitle={instruction}
            displayMessage={!hasError}
        >
            <form id="kc-reset-password-form" className="pz-form" action={url.loginAction} method="post">
                <FormField inputId="username" label={getUsernameLabel(realm, i18n)} error={hasError ? messagesPerField.get("username") : undefined}>
                    <input
                        type="text"
                        id="username"
                        name="username"
                        className="pz-input"
                        autoFocus
                        autoComplete="username"
                        defaultValue={auth.attemptedUsername ?? ""}
                        aria-invalid={hasError}
                        aria-describedby={hasError ? getFieldErrorId("username") : undefined}
                        placeholder={realm.loginWithEmailAllowed ? undefined : msgStr("pzDocumentPlaceholder")}
                    />
                </FormField>

                <button className="pz-button pz-button-primary" type="submit">
                    {msgStr("pzResetSubmit")}
                </button>
            </form>

            <p className="pz-form-footer">
                <a href={url.loginUrl} className="pz-link pz-back-link">
                    <ArrowLeftIcon />
                    {msg("pzBackToLogin")}
                </a>
            </p>
        </AuthLayout>
    );
}
