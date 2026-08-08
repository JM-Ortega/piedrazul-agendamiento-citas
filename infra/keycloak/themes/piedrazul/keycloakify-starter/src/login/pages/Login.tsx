import type { JSX } from "keycloakify/tools/JSX";
import { useState } from "react";
import { kcSanitize } from "keycloakify/lib/kcSanitize";
import { useIsPasswordRevealed } from "keycloakify/tools/useIsPasswordRevealed";
import type { PageProps } from "keycloakify/login/pages/PageProps";
import type { KcContext } from "../KcContext";
import type { I18n } from "../i18n";
import { useScript } from "keycloakify/login/pages/Login.useScript";
import "./login.css";
import { kcEnvDefaults } from "../../kc.gen";

const REGISTER_URL = kcEnvDefaults.REGISTER_URL;
const HOME_URL = kcEnvDefaults.HOME_URL;

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
        messagesPerField,
        enableWebAuthnConditionalUI,
        authenticators,
        message
    } = kcContext;

    const { msg, msgStr } = i18n;

    const [isLoginButtonDisabled, setIsLoginButtonDisabled] = useState(false);

    const webAuthnButtonId = "authenticateWebAuthnButton";

    useScript({ webAuthnButtonId, kcContext, i18n });

    const hasError = messagesPerField.existsError("username", "password");
    const hasMessage = message !== undefined && message.type !== "warning";

    return (
        <div className="pz-root">
            {/* ── Panel izquierdo: branding ── */}
            <div className="pz-left">
                <div className="pz-left-top">
                    <div className="pz-brand">
                        <div className="pz-brand-icon">
                            <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                                <rect x="3" y="4" width="18" height="17" rx="2" stroke="white" strokeWidth="1.5" />
                                <path d="M8 2v4M16 2v4M3 10h18" stroke="white" strokeWidth="1.5" strokeLinecap="round" />
                                <path d="M12 13v4M10 15h4" stroke="white" strokeWidth="1.5" strokeLinecap="round" />
                            </svg>
                        </div>
                        <div>
                            <div className="pz-brand-name">Piedrazul Salud</div>
                            <div className="pz-brand-sub">Centro Médico</div>
                        </div>
                    </div>

                    {/* ── Botón Inicio ── */}
                    <a href={HOME_URL} className="pz-home-btn">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                            <path d="M3 11.5L12 4l9 7.5" />
                            <path d="M5 10v9h14v-9" />
                        </svg>
                        Volver al inicio
                    </a>
                </div>

                <div className="pz-left-center">
                    <h1 className="pz-hero-title">Bienvenido al sistema de gestión médica</h1>
                    <p className="pz-hero-desc">Accede para gestionar citas, pacientes y la agenda del centro médico de forma segura.</p>
                </div>

                <div className="pz-features">
                    <div className="pz-feature">
                        <span className="pz-feature-dot" />
                        <span>Agendamiento de citas en tiempo real</span>
                    </div>
                    <div className="pz-feature">
                        <span className="pz-feature-dot" />
                        <span>Historial clínico seguro</span>
                    </div>
                    <div className="pz-feature">
                        <span className="pz-feature-dot" />
                        <span>Acceso según rol y permisos</span>
                    </div>
                </div>
            </div>

            {/* ── Panel derecho: formulario ── */}
            <div className="pz-right">
                <div className="pz-form-container">
                    <div className="pz-form-header">
                        <span className="pz-form-eyebrow">Acceso al sistema</span>
                        <h2 className="pz-form-title">Iniciar sesión</h2>
                        <p className="pz-form-subtitle">Ingresa tus credenciales para continuar</p>
                    </div>

                    {/* Mensaje de error global */}
                    {hasMessage && (
                        <div className={`pz-alert pz-alert-${message.type}`} dangerouslySetInnerHTML={{ __html: kcSanitize(message.summary) }} />
                    )}

                    {/* Formulario principal */}
                    {realm.password && (
                        <form
                            id="kc-form-login"
                            onSubmit={() => {
                                setIsLoginButtonDisabled(true);
                                return true;
                            }}
                            action={url.loginAction}
                            method="post"
                            className="pz-form"
                        >
                            {/* Campo usuario/email */}
                            {!usernameHidden && (
                                <div className="pz-field">
                                    <label htmlFor="username" className="pz-label">
                                        Documento de identidad
                                    </label>
                                    <input
                                        tabIndex={2}
                                        id="username"
                                        className={`pz-input ${hasError ? "pz-input-error" : ""}`}
                                        name="username"
                                        defaultValue={login.username ?? ""}
                                        type="text"
                                        autoFocus
                                        autoComplete={enableWebAuthnConditionalUI ? "username webauthn" : "username"}
                                        aria-invalid={hasError}
                                        placeholder="Ej: 1234567890 o AB123456"
                                    />
                                    {messagesPerField.existsError("username") && (
                                        <span
                                            className="pz-field-error"
                                            aria-live="polite"
                                            dangerouslySetInnerHTML={{
                                                __html: kcSanitize(messagesPerField.getFirstError("username"))
                                            }}
                                        />
                                    )}
                                </div>
                            )}

                            {/* Campo contraseña */}
                            <div className="pz-field">
                                <div className="pz-field-row">
                                    <label htmlFor="password" className="pz-label">
                                        {msg("password")}
                                    </label>
                                    {realm.resetPasswordAllowed && (
                                        <a tabIndex={6} href={url.loginResetCredentialsUrl} className="pz-forgot">
                                            {msg("doForgotPassword")}
                                        </a>
                                    )}
                                </div>
                                <PasswordWrapper i18n={i18n} passwordInputId="password">
                                    <input
                                        tabIndex={3}
                                        id="password"
                                        className={`pz-input ${hasError ? "pz-input-error" : ""}`}
                                        name="password"
                                        type="password"
                                        autoComplete="current-password"
                                        aria-invalid={hasError}
                                        placeholder="••••••••"
                                    />
                                </PasswordWrapper>
                                {usernameHidden && messagesPerField.existsError("username", "password") && (
                                    <span
                                        className="pz-field-error"
                                        aria-live="polite"
                                        dangerouslySetInnerHTML={{
                                            __html: kcSanitize(messagesPerField.getFirstError("username", "password"))
                                        }}
                                    />
                                )}
                            </div>

                            {/* Recordarme */}
                            {realm.rememberMe && !usernameHidden && (
                                <div className="pz-remember">
                                    <label className="pz-checkbox-label">
                                        <input
                                            tabIndex={5}
                                            id="rememberMe"
                                            name="rememberMe"
                                            type="checkbox"
                                            className="pz-checkbox"
                                            defaultChecked={!!login.rememberMe}
                                        />
                                        <span>{msg("rememberMe")}</span>
                                    </label>
                                </div>
                            )}

                            {/* Inputs ocultos */}
                            <input type="hidden" id="id-hidden-input" name="credentialId" value={auth.selectedCredential} />

                            {/* Botón ingresar */}
                            <button tabIndex={7} disabled={isLoginButtonDisabled} className="pz-btn-primary" name="login" id="kc-login" type="submit">
                                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                                    <path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4M10 17l5-5-5-5M14 12H3" />
                                </svg>
                                {msgStr("doLogIn")}
                            </button>
                        </form>
                    )}

                    {/* Social providers */}
                    {realm.password && social?.providers !== undefined && social.providers.length !== 0 && (
                        <>
                            <div className="pz-divider">
                                <div className="pz-divider-line" />
                                <span className="pz-divider-text">{msg("identity-provider-login-label")}</span>
                                <div className="pz-divider-line" />
                            </div>
                            <ul className="pz-social-list">
                                {social.providers.map(p => (
                                    <li key={p.alias}>
                                        <a id={`social-${p.alias}`} className="pz-btn-social" href={p.loginUrl}>
                                            {p.iconClasses && <i className={p.iconClasses} aria-hidden="true" />}
                                            <span dangerouslySetInnerHTML={{ __html: kcSanitize(p.displayName) }} />
                                        </a>
                                    </li>
                                ))}
                            </ul>
                        </>
                    )}

                    {/* Registro */}
                    {realm.password && realm.registrationAllowed && !registrationDisabled && (
                        <p className="pz-register-note">
                            {msg("noAccount")}{" "}
                            <a tabIndex={8} href={REGISTER_URL} className="pz-link">
                                {msg("doRegister")}
                            </a>
                        </p>
                    )}
                </div>
            </div>

            {/* WebAuthn forms ocultos */}
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
                    <input id={webAuthnButtonId} type="button" className="pz-btn-webauthn" value={msgStr("passkey-doAuthenticate")} />
                </>
            )}
        </div>
    );
}

function PasswordWrapper(props: { i18n: I18n; passwordInputId: string; children: JSX.Element }) {
    const { i18n, passwordInputId, children } = props;
    const { msgStr } = i18n;
    const { isPasswordRevealed, toggleIsPasswordRevealed } = useIsPasswordRevealed({ passwordInputId });

    return (
        <div className="pz-password-wrapper">
            {children}
            <button
                type="button"
                className="pz-password-toggle"
                aria-label={msgStr(isPasswordRevealed ? "hidePassword" : "showPassword")}
                aria-controls={passwordInputId}
                onClick={toggleIsPasswordRevealed}
            >
                {isPasswordRevealed ? (
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                        <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94" />
                        <path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19" />
                        <line x1="1" y1="1" x2="23" y2="23" />
                    </svg>
                ) : (
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                        <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
                        <circle cx="12" cy="12" r="3" />
                    </svg>
                )}
            </button>
        </div>
    );
}
