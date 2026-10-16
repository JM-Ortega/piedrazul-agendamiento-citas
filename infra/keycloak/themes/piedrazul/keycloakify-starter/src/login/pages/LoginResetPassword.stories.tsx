import type { Meta, StoryObj } from "@storybook/react";
import { createKcPageStory } from "../KcPageStory";

const { KcPageStory } = createKcPageStory({ pageId: "login-reset-password.ftl" });

const meta = {
    title: "login/login-reset-password.ftl",
    component: KcPageStory
} satisfies Meta<typeof KcPageStory>;

export default meta;

type Story = StoryObj<typeof meta>;

/** Configuración actual del realm: el usuario es el documento de identidad. */
export const Default: Story = {
    render: () => <KcPageStory kcContext={{ realm: { loginWithEmailAllowed: false } }} />
};

export const WithPrefilledUsername: Story = {
    render: () => (
        <KcPageStory
            kcContext={{
                realm: { loginWithEmailAllowed: false },
                auth: { attemptedUsername: "1061234567" }
            }}
        />
    )
};

export const WithMissingUsername: Story = {
    render: () => (
        <KcPageStory
            kcContext={{
                realm: { loginWithEmailAllowed: false },
                messagesPerField: {
                    existsError: (fieldName: string, ...otherFieldNames: string[]) => [fieldName, ...otherFieldNames].includes("username"),
                    get: (fieldName: string) => (fieldName === "username" ? "Por favor indica tu usuario." : "")
                }
            }}
        />
    )
};

export const WithErrorMessage: Story = {
    render: () => (
        <KcPageStory
            kcContext={{
                realm: { loginWithEmailAllowed: false },
                message: {
                    type: "error",
                    summary: "Falló el envío del email, por favor inténtalo de nuevo más tarde."
                }
            }}
        />
    )
};

/** Si el realm vuelve a permitir correo, la etiqueta e instrucciones son las de Keycloak. */
export const WithEmailLoginAllowed: Story = {
    render: () => <KcPageStory kcContext={{ realm: { loginWithEmailAllowed: true } }} />
};
