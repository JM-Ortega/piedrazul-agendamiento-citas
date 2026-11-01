import type { Meta, StoryObj } from "@storybook/react";
import { createKcPageStory } from "../KcPageStory";

const { KcPageStory } = createKcPageStory({ pageId: "error.ftl" });

const meta = {
    title: "login/error.ftl",
    component: KcPageStory
} satisfies Meta<typeof KcPageStory>;

export default meta;

type Story = StoryObj<typeof meta>;

/** Enlace de recuperación ya usado (mensaje real de Keycloak 26.8.1); sin baseUrl se ofrece el frontend. */
export const RecoveryLinkAlreadyUsed: Story = {
    render: () => (
        <KcPageStory
            kcContext={{
                message: { type: "error", summary: "Acción caducada. Continúe con el inicio de sesión ahora." },
                client: { baseUrl: "" }
            }}
        />
    )
};

export const RecoveryLinkExpired: Story = {
    render: () => (
        <KcPageStory
            kcContext={{
                message: { type: "error", summary: "Acción expirada." },
                client: { baseUrl: "" }
            }}
        />
    )
};

export const WithBackToApplication: Story = {
    render: () => (
        <KcPageStory
            kcContext={{
                message: { type: "error", summary: "El reinicio de las credenciales no está permitido" },
                client: { baseUrl: "https://piedrazul.org" }
            }}
        />
    )
};

/** El contenido de Keycloak se sanea: el script no debe ejecutarse ni aparecer. */
export const WithUnsafeMarkup: Story = {
    render: () => (
        <KcPageStory
            kcContext={{
                message: {
                    type: "error",
                    summary: 'Mensaje con <b>formato</b><img src=x onerror="window.__pzXss=1"><script>window.__pzXss=1</script>'
                }
            }}
        />
    )
};

/** Keycloak suprime las salidas: ni la aplicación ni el frontend. */
export const WithSkipLink: Story = {
    render: () => (
        <KcPageStory
            kcContext={{
                message: { type: "error", summary: "Acción expirada." },
                skipLink: true,
                client: { baseUrl: "" }
            }}
        />
    )
};

export const WithoutFrontendUrl: Story = {
    render: () => (
        <KcPageStory
            kcContext={{
                message: { type: "error", summary: "Acción expirada." },
                client: { baseUrl: "" },
                properties: { FRONTEND_PUBLIC_URL: "" }
            }}
        />
    )
};
