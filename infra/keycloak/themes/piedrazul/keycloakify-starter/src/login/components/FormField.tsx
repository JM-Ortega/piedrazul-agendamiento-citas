import type { ReactNode } from "react";
import { kcSanitize } from "keycloakify/lib/kcSanitize";
import { AlertCircleIcon } from "./icons";
import { getFieldErrorId } from "./fieldErrorId";

/**
 * Etiqueta + control + error de un campo. El control lo provee la página (conserva sus
 * atributos de Keycloak) y debe apuntar `aria-describedby` a `getFieldErrorId(inputId)`
 * cuando hay error. El error llega de Keycloak y se sanea antes de insertarlo.
 */
export function FormField(props: {
    inputId: string;
    label: string;
    labelAside?: ReactNode;
    error?: string;
    children: ReactNode;
}) {
    const { inputId, label, labelAside, error, children } = props;

    return (
        <div className="pz-field">
            <div className="pz-field-label-row">
                <label htmlFor={inputId} className="pz-label">
                    {label}
                </label>
                {labelAside}
            </div>
            {children}
            {error !== undefined && error !== "" && (
                <p
                    className="pz-field-error"
                    id={getFieldErrorId(inputId)}
                    aria-live="polite"
                >
                    <AlertCircleIcon size={14} />
                    <span dangerouslySetInnerHTML={{ __html: kcSanitize(error) }} />
                </p>
            )}
        </div>
    );
}
