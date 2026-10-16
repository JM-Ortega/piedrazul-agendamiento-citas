import { useState } from "react";
import { kcSanitize } from "keycloakify/lib/kcSanitize";
import type { PageProps } from "keycloakify/login/pages/PageProps";
import { useScript } from "keycloakify/login/pages/Login.useScript";
import type { KcContext } from "../KcContext";
import { getUsernameLabel, type I18n } from "../i18n";
import { AuthLayout } from "../components/AuthLayout";
import { FormField } from "../components/FormField";
import { getFieldErrorId } from "../components/fieldErrorId";
import { PasswordInput } from "../components/PasswordInput";
import { LogInIcon } from "../components/icons";
import { getFrontendUrls, PATIENT_APP_CLIENT_ID } from "../frontendUrl";

export default function Login(props: PageProps<Extract<KcContext, { pageId: "login.ftl" }>, I18n>) {
    const { kcContext, i18n } = props;

    const {
        social,
        realm,
        url,
        usernameHidden,
        login,
        auth,
        registrationDisabled,
        client,
        messagesPerField,
        enableWebAuthnConditionalUI,
        authenticators
    } = kcContext;

    const { msg, msgStr } = i18n;

    const patientRegistrationUrl = getFrontendUrls(kcContext)?.patientRegistration;

    const [isLoginButtonDisabled, setIsLoginButtonDisabled] = useState(false);

    const webAuthnButtonId = "authenticateWebAuthnButton";

    useScript({ webAuthnButtonId, kcContext, i18n });

    // Igual que la página por defecto: el error de credenciales se muestra junto al campo
    // (bajo el usuario, o bajo la contraseña si el usuario está oculto) y no como alerta.
    const hasCredentialError = messagesPerField.existsError("username", "password");
    const credentialError = hasCredentialError ? messagesPerField.getFirstError("username", "password") : undefined;

    return (
        <AuthLayout
            kcContext={kcContext}
            i18n={i18n}
            eyebrow={msgStr("pzLoginEyebrow")}
            title={msgStr("doLogIn")}
            subtitle={msg("pzLoginSubtitle")}
            displayMessage={!hasCredentialError}
        >
            {realm.password && (
                <form
                    id="kc-form-login"
                    className="pz-form"
                    onSubmit={() => {
                        setIsLoginButtonDisabled(true);
                        return true;
                    }}
                    action={url.loginAction}
                    method="post"
                >
                    {!usernameHidden && (
                        <FormField inputId="username" label={getUsernameLabel(realm, i18n)} error={credentialError}>
                            <input
                                id="username"
                                className="pz-input"
                                name="username"
                                defaultValue={login.username ?? ""}
                                type="text"
                                autoFocus
                                autoComplete={enableWebAuthnConditionalUI ? "username webauthn" : "username"}
                                aria-invalid={hasCredentialError}
                                aria-describedby={hasCredentialError ? getFieldErrorId("username") : undefined}
                                placeholder={realm.loginWithEmailAllowed ? undefined : msgStr("pzDocumentPlaceholder")}
                            />
                        </FormField>
                    )}

                    <FormField
                        inputId="password"
                        label={msgStr("password")}
                        labelAside={
                            realm.resetPasswordAllowed && (
                                <a href={url.loginResetCredentialsUrl} className="pz-link pz-link-small">
                                    {msg("doForgotPassword")}
                                </a>
                            )
                        }
                        error={usernameHidden ? credentialError : undefined}
                    >
                        <PasswordInput i18n={i18n} passwordInputId="password">
                            <input
                                id="password"
                                className="pz-input"
                                name="password"
                                type="password"
                                autoComplete="current-password"
                                autoFocus={usernameHidden}
                                aria-invalid={hasCredentialError}
                                aria-describedby={usernameHidden && hasCredentialError ? getFieldErrorId("password") : undefined}
                            />
                        </PasswordInput>
                    </FormField>

                    {realm.rememberMe && !usernameHidden && (
                        <label className="pz-checkbox">
                            <input id="rememberMe" name="rememberMe" type="checkbox" defaultChecked={!!login.rememberMe} />
                            <span>{msg("rememberMe")}</span>
                        </label>
                    )}

                    <input type="hidden" id="id-hidden-input" name="credentialId" value={auth.selectedCredential} />

                    <button disabled={isLoginButtonDisabled} className="pz-button pz-button-primary" name="login" id="kc-login" type="submit">
                        <LogInIcon size={18} />
                        {msgStr("doLogIn")}
                    </button>
                </form>
            )}

            {enableWebAuthnConditionalUI && (
                <>
                    <form id="webauth" action={url.loginAction} method="post">
                        <input type="hidden" id="clientDataJSON" name="clientDataJSON" />
                        <input type="hidden" id="authenticatorData" name="authenticatorData" />
                        <input type="hidden" id="signature" name="signature" />
                        <input type="hidden" id="credentialId" name="credentialId" />
                        <input type="hidden" id="userHandle" name="userHandle" />
                        <input type="hidden" id="error" name="error" />
                    </form>
                    {authenticators !== undefined && authenticators.authenticators.length !== 0 && (
                        <form id="authn_select">
                            {authenticators.authenticators.map((authenticator, i) => (
                                <input key={i} type="hidden" name="authn_use_chk" readOnly value={authenticator.credentialId} />
                            ))}
                        </form>
                    )}
                    <input
                        id={webAuthnButtonId}
                        type="button"
                        className="pz-button pz-button-secondary pz-webauthn"
                        value={msgStr("passkey-doAuthenticate")}
                    />
                </>
            )}

            {realm.password && social?.providers !== undefined && social.providers.length !== 0 && (
                <div id="kc-social-providers">
                    <div className="pz-divider">
                        <span>{msg("identity-provider-login-label")}</span>
                    </div>
                    <ul className="pz-social-list">
                        {social.providers.map(p => (
                            <li key={p.alias}>
                                <a id={`social-${p.alias}`} className="pz-button pz-button-secondary" href={p.loginUrl}>
                                    {p.iconClasses && <i className={p.iconClasses} aria-hidden="true" />}
                                    <span dangerouslySetInnerHTML={{ __html: kcSanitize(p.displayName) }} />
                                </a>
                            </li>
                        ))}
                    </ul>
                </div>
            )}

            {/* Registro de pacientes del frontend (Angular + backend), independiente del registro
                nativo de Keycloak (registrationAllowed), que permanece deshabilitado. */}
            {realm.password && !registrationDisabled && client.clientId === PATIENT_APP_CLIENT_ID && patientRegistrationUrl !== undefined && (
                <p className="pz-form-footer">
                    {msg("pzPatientRegistrationPrompt")}{" "}
                    <a href={patientRegistrationUrl} className="pz-link">
                        {msg("pzPatientRegistrationLink")}
                    </a>
                </p>
            )}
        </AuthLayout>
    );
}
