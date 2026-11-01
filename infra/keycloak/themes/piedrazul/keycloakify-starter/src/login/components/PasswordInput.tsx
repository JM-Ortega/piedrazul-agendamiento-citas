import type { JSX } from "keycloakify/tools/JSX";
import { useIsPasswordRevealed } from "keycloakify/tools/useIsPasswordRevealed";
import type { I18n } from "../i18n";
import { EyeIcon, EyeOffIcon } from "./icons";

/**
 * Envuelve un `<input type="password">` con el botón para mostrar u ocultar su valor.
 * `useIsPasswordRevealed` cambia el tipo del input por id, así que `passwordInputId` debe
 * coincidir con el id del input hijo.
 */
export function PasswordInput(props: {
    i18n: I18n;
    passwordInputId: string;
    children: JSX.Element;
}) {
    const { i18n, passwordInputId, children } = props;

    const { msgStr } = i18n;
    const { isPasswordRevealed, toggleIsPasswordRevealed } = useIsPasswordRevealed({
        passwordInputId
    });

    return (
        <div className="pz-password">
            {children}
            <button
                type="button"
                className="pz-password-toggle"
                aria-label={msgStr(isPasswordRevealed ? "hidePassword" : "showPassword")}
                aria-controls={passwordInputId}
                onClick={toggleIsPasswordRevealed}
            >
                {isPasswordRevealed ? <EyeOffIcon size={18} /> : <EyeIcon size={18} />}
            </button>
        </div>
    );
}
