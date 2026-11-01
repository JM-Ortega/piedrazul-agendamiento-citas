/* eslint-disable @typescript-eslint/no-unused-vars */
import { i18nBuilder } from "keycloakify/login";
import type { ThemeName } from "../kc.gen";

/**
 * Textos propios del tema (prefijo `pz`). Las etiquetas, errores y mensajes que entrega
 * Keycloak siguen usando sus claves estándar (`password`, `doLogIn`, `emailForgotTitle`…).
 * El realm solo habilita `es`; `en` existe porque es el idioma de respaldo de Keycloakify.
 *
 * @see: https://docs.keycloakify.dev/features/i18n
 */
const { useI18n, ofTypeI18n } = i18nBuilder
    .withThemeName<ThemeName>()
    .withCustomTranslations({
        en: {
            pzBrandName: "Piedra Azul Salud",
            pzBrandDescriptor: "Medical Center",
            pzHomeLink: "Back to home",
            pzHeroTitle: "Welcome to the medical management system",
            pzHeroDescription:
                "Sign in to manage appointments, patients and the medical center's schedule securely.",
            pzFeatureScheduling: "Real-time appointment scheduling",
            pzFeatureRecords: "Secure clinical history",
            pzFeatureRoles: "Access based on role and permissions",
            pzDocumentLabel: "Identity document",
            pzDocumentPlaceholder: "e.g. 1234567890 or AB123456",
            pzLoginEyebrow: "System access",
            pzLoginSubtitle: "Enter your credentials to continue",
            pzResetEyebrow: "Access recovery",
            pzResetInstructionDocument:
                "Enter your identity document. If it belongs to an account with a registered email, we will send you instructions to create a new password.",
            pzResetSubmit: "Send instructions",
            pzBackToLogin: "Back to sign in",
            pzUpdateEyebrow: "Account security",
            pzUpdateTitle: "Create a new password",
            pzUpdateSubtitle: "Enter and confirm your new password.",
            pzUpdateSubmit: "Save password",
            pzInfoEyebrow: "Notice",
            pzInfoTitle: "Information",
            pzErrorEyebrow: "Could not continue",
            pzExpiredSubtitle:
                "You can continue where you left off or start signing in again.",
            pzExpiredContinue: "Continue",
            pzPatientRegistrationPrompt: "New patient?",
            pzPatientRegistrationLink: "Register",
            pzGoToFrontend: "Go to Piedra Azul Salud"
        },
        es: {
            pzBrandName: "Piedra Azul Salud",
            pzBrandDescriptor: "Centro Médico",
            pzHomeLink: "Volver al inicio",
            pzHeroTitle: "Bienvenido al sistema de gestión médica",
            pzHeroDescription:
                "Accede para gestionar citas, pacientes y la agenda del centro médico de forma segura.",
            pzFeatureScheduling: "Agendamiento de citas en tiempo real",
            pzFeatureRecords: "Historial clínico seguro",
            pzFeatureRoles: "Acceso según rol y permisos",
            pzDocumentLabel: "Documento de identidad",
            pzDocumentPlaceholder: "Ej: 1234567890 o AB123456",
            pzLoginEyebrow: "Acceso al sistema",
            pzLoginSubtitle: "Ingresa tus credenciales para continuar",
            pzResetEyebrow: "Recuperación de acceso",
            pzResetInstructionDocument:
                "Ingresa tu documento de identidad. Si corresponde a una cuenta con correo registrado, te enviaremos instrucciones para crear una nueva contraseña.",
            pzResetSubmit: "Enviar instrucciones",
            pzBackToLogin: "Volver a iniciar sesión",
            pzUpdateEyebrow: "Seguridad de la cuenta",
            pzUpdateTitle: "Crea una nueva contraseña",
            pzUpdateSubtitle: "Escribe y confirma tu nueva contraseña.",
            pzUpdateSubmit: "Guardar contraseña",
            pzInfoEyebrow: "Aviso",
            pzInfoTitle: "Información",
            pzErrorEyebrow: "No se pudo continuar",
            pzExpiredSubtitle:
                "Puedes continuar donde quedaste o volver a iniciar sesión desde el principio.",
            pzExpiredContinue: "Continuar",
            pzPatientRegistrationPrompt: "¿Eres paciente nuevo?",
            pzPatientRegistrationLink: "Regístrate",
            pzGoToFrontend: "Ir a Piedra Azul Salud"
        }
    })
    .build();

type I18n = typeof ofTypeI18n;

/**
 * Etiqueta del identificador según el realm: con `loginWithEmailAllowed=false` (configuración
 * actual) el usuario es el documento de identidad; si el realm cambia, vuelven las claves de
 * Keycloak.
 */
function getUsernameLabel(
    realm: { loginWithEmailAllowed: boolean; registrationEmailAsUsername: boolean },
    i18n: I18n
): string {
    const { msgStr } = i18n;

    if (!realm.loginWithEmailAllowed) {
        return msgStr("pzDocumentLabel");
    }

    return realm.registrationEmailAsUsername
        ? msgStr("email")
        : msgStr("usernameOrEmail");
}

export { useI18n, getUsernameLabel, type I18n };
