import type { Meta, StoryObj } from "@storybook/react";
import { createKcPageStory } from "../KcPageStory";

const { KcPageStory } = createKcPageStory({ pageId: "login-update-password.ftl" });

const meta = {
    title: "login/login-update-password.ftl",
    component: KcPageStory
} satisfies Meta<typeof KcPageStory>;

export default meta;

type Story = StoryObj<typeof meta>;

/** Acción requerida UPDATE_PASSWORD tras ingresar con una contraseña temporal. */
export const MandatoryChange: Story = {
    render: () => (
        <KcPageStory
            kcContext={{
                auth: { attemptedUsername: "1061234567", showUsername: true },
                message: {
                    type: "warning",
                    summary: "Debes cambiar tu contraseña para activar tu cuenta."
                }
            }}
        />
    )
};

/** Llegada desde el enlace de recuperación: el usuario ya está identificado por el token. */
export const FromRecoveryLink: Story = {
    render: () => <KcPageStory kcContext={{ auth: { showUsername: false } }} />
};

export const WithPasswordMismatch: Story = {
    render: () => (
        <KcPageStory
            kcContext={{
                messagesPerField: {
                    existsError: (fieldName: string, ...otherFieldNames: string[]) => [fieldName, ...otherFieldNames].includes("password-confirm"),
                    get: (fieldName: string) => (fieldName === "password-confirm" ? "Las contraseñas no coinciden." : "")
                }
            }}
        />
    )
};

/** Error de política devuelto por Keycloak sobre el campo `password`. */
export const WithPolicyError: Story = {
    render: () => (
        <KcPageStory
            kcContext={{
                messagesPerField: {
                    existsError: (fieldName: string, ...otherFieldNames: string[]) => [fieldName, ...otherFieldNames].includes("password"),
                    get: (fieldName: string) => (fieldName === "password" ? "Contraseña incorrecta: longitud mínima 12." : "")
                }
            }}
        />
    )
};

/** Acción iniciada por la aplicación: única variante con botón de cancelar. */
export const AppInitiated: Story = {
    render: () => (
        <KcPageStory
            kcContext={{
                isAppInitiatedAction: true,
                message: {
                    type: "warning",
                    summary: "Este aviso no se muestra en acciones iniciadas por la aplicación."
                }
            }}
        />
    )
};
