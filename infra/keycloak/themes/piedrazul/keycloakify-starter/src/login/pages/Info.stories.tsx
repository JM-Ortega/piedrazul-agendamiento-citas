import type { Meta, StoryObj } from "@storybook/react";
import { createKcPageStory } from "../KcPageStory";

const { KcPageStory } = createKcPageStory({ pageId: "info.ftl" });

const meta = {
    title: "login/info.ftl",
    component: KcPageStory
} satisfies Meta<typeof KcPageStory>;

export default meta;

type Story = StoryObj<typeof meta>;

/**
 * Lo que Keycloak 26.8.1 entrega al terminar la recuperación en este realm: sin enlace propio,
 * así que se ofrece ir al frontend configurado.
 */
export const AccountUpdatedAfterRecovery: Story = {
    render: () => (
        <KcPageStory
            kcContext={{
                messageHeader: "accountUpdatedTitle",
                "x-keycloakify": { messages: { accountUpdatedTitle: "Cuenta actualizada" } },
                message: { type: "success", summary: "Tu cuenta se ha actualizado." },
                actionUri: "",
                client: { baseUrl: "" }
            }}
        />
    )
};

export const WithBackToApplication: Story = {
    render: () => (
        <KcPageStory
            kcContext={{
                messageHeader: "accountUpdatedTitle",
                "x-keycloakify": { messages: { accountUpdatedTitle: "Cuenta actualizada" } },
                message: { type: "success", summary: "Tu cuenta se ha actualizado." },
                actionUri: "",
                client: { baseUrl: "https://piedrazul.org" }
            }}
        />
    )
};

/** Acciones pendientes con enlace para continuar (`actionUri`). */
export const WithRequiredActionsAndProceed: Story = {
    render: () => (
        <KcPageStory
            kcContext={{
                messageHeader: "",
                message: {
                    type: "info",
                    summary: "Debes realizar las siguientes acciones:"
                },
                requiredActions: ["UPDATE_PASSWORD"],
                actionUri: "#proceed"
            }}
        />
    )
};

export const WithSkipLink: Story = {
    render: () => (
        <KcPageStory
            kcContext={{
                messageHeader: "",
                message: { type: "info", summary: "Puedes cerrar esta ventana." },
                skipLink: true,
                client: { baseUrl: "https://piedrazul.org" }
            }}
        />
    )
};

/** Sin enlace de Keycloak y sin FRONTEND_PUBLIC_URL: no se ofrece ninguna salida inventada. */
export const WithoutContinuationNorFrontendUrl: Story = {
    render: () => (
        <KcPageStory
            kcContext={{
                messageHeader: "accountUpdatedTitle",
                "x-keycloakify": { messages: { accountUpdatedTitle: "Cuenta actualizada" } },
                message: { type: "success", summary: "Tu cuenta se ha actualizado." },
                actionUri: "",
                client: { baseUrl: "" },
                properties: { FRONTEND_PUBLIC_URL: "" }
            }}
        />
    )
};
