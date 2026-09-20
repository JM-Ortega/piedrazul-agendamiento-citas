export const environment = {
  production: false,
  apiUrl: 'http://localhost:8080/api',
  keycloak: {
    url: 'http://localhost:8180',
    realm: 'piedrazul',
    clientId: 'piedrazul-frontend',
  },
  contact: {
    whatsapp: '573173803813',
    whatsappDisplay: '317 380 3813',
    phone: '6013891234',
    phoneDisplay: '601 389 1234',
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
      default: 15,
    } as Record<string, number>,
  },
};
