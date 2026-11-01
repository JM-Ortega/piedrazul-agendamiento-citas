import type { ReactNode } from "react";

/**
 * Íconos de trazo con la geometría de Lucide (la librería de íconos del frontend Angular),
 * copiados como SVG para no agregar dependencias al tema. Son decorativos: el texto
 * accesible lo aporta siempre el elemento que los contiene.
 */
function Icon(props: { size?: number; children: ReactNode }) {
    const { size = 16, children } = props;

    return (
        <svg
            className="pz-icon"
            width={size}
            height={size}
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth={2}
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
            focusable="false"
        >
            {children}
        </svg>
    );
}

export function CalendarIcon(props: { size?: number }) {
    return (
        <Icon {...props}>
            <path d="M8 2v4" />
            <path d="M16 2v4" />
            <rect width="18" height="18" x="3" y="4" rx="2" />
            <path d="M3 10h18" />
        </Icon>
    );
}

export function HomeIcon(props: { size?: number }) {
    return (
        <Icon {...props}>
            <path d="M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8" />
            <path d="M3 10a2 2 0 0 1 .709-1.528l7-5.999a2 2 0 0 1 2.582 0l7 5.999A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
        </Icon>
    );
}

export function LogInIcon(props: { size?: number }) {
    return (
        <Icon {...props}>
            <path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4" />
            <path d="m10 17 5-5-5-5" />
            <path d="M15 12H3" />
        </Icon>
    );
}

export function ArrowLeftIcon(props: { size?: number }) {
    return (
        <Icon {...props}>
            <path d="m12 19-7-7 7-7" />
            <path d="M19 12H5" />
        </Icon>
    );
}

export function EyeIcon(props: { size?: number }) {
    return (
        <Icon {...props}>
            <path d="M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0" />
            <circle cx="12" cy="12" r="3" />
        </Icon>
    );
}

export function EyeOffIcon(props: { size?: number }) {
    return (
        <Icon {...props}>
            <path d="M10.733 5.076a10.744 10.744 0 0 1 11.205 6.575 1 1 0 0 1 0 .696 10.747 10.747 0 0 1-1.444 2.49" />
            <path d="M14.084 14.158a3 3 0 0 1-4.242-4.242" />
            <path d="M17.479 17.499a10.75 10.75 0 0 1-15.417-5.151 1 1 0 0 1 0-.696 10.75 10.75 0 0 1 4.446-5.143" />
            <path d="m2 2 20 20" />
        </Icon>
    );
}

export function UserIcon(props: { size?: number }) {
    return (
        <Icon {...props}>
            <path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2" />
            <circle cx="12" cy="7" r="4" />
        </Icon>
    );
}

export function RotateCcwIcon(props: { size?: number }) {
    return (
        <Icon {...props}>
            <path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8" />
            <path d="M3 3v5h5" />
        </Icon>
    );
}

export function AlertCircleIcon(props: { size?: number }) {
    return (
        <Icon {...props}>
            <circle cx="12" cy="12" r="10" />
            <line x1="12" x2="12" y1="8" y2="12" />
            <line x1="12" x2="12.01" y1="16" y2="16" />
        </Icon>
    );
}

export function AlertTriangleIcon(props: { size?: number }) {
    return (
        <Icon {...props}>
            <path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3" />
            <path d="M12 9v4" />
            <path d="M12 17h.01" />
        </Icon>
    );
}

export function CheckCircleIcon(props: { size?: number }) {
    return (
        <Icon {...props}>
            <circle cx="12" cy="12" r="10" />
            <path d="m9 12 2 2 4-4" />
        </Icon>
    );
}

export function InfoIcon(props: { size?: number }) {
    return (
        <Icon {...props}>
            <circle cx="12" cy="12" r="10" />
            <path d="M12 16v-4" />
            <path d="M12 8h.01" />
        </Icon>
    );
}
