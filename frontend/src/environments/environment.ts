export const environment = {
  production: true,
  apiUrl: 'https://api.piedrazul.org/api',
  keycloak: {
    url: 'https://auth.piedrazul.org',
    realm: 'piedrazul',
    clientId: 'piedrazul-frontend',
  },
  contact: {
    whatsapp: '3206597038',
    whatsappDisplay: '320 659 7038',
    phone: '3126069041',
    phoneDisplay: '312 606 9041',
  },
  session: {
    /** Minutos de inactividad permitidos por rol.
     * `default` aplica si el rol es desconocido.
     */
    inactivityMinutes: {
      PATIENT: 15,
      SCHEDULER: 480,
      DOCTOR: 480,
      ADMIN: 480,
      AUDITOR: 15,
      default: 15,
    } as Record<string, number>,
  },
};
