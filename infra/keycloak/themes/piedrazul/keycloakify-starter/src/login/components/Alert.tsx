import { kcSanitize } from "keycloakify/lib/kcSanitize";
import { AlertCircleIcon, AlertTriangleIcon, CheckCircleIcon, InfoIcon } from "./icons";

const alertIcons = {
    success: CheckCircleIcon,
    warning: AlertTriangleIcon,
    error: AlertCircleIcon,
    info: InfoIcon
} as const;

/** Mensaje de Keycloak. `summary` puede traer HTML de Keycloak: siempre se sanea. */
export function Alert(props: { type: keyof typeof alertIcons; summary: string }) {
    const { type, summary } = props;

    const Icon = alertIcons[type];

    return (
        <div
            className={`pz-alert pz-alert-${type}`}
            role={type === "error" ? "alert" : "status"}
        >
            <Icon size={18} />
            <span
                className="pz-alert-text"
                dangerouslySetInnerHTML={{ __html: kcSanitize(summary) }}
            />
        </div>
    );
}
