import {
  provideHttpClient,
  withInterceptors,
  withXhr,
} from '@angular/common/http';
import { provideNativeDateAdapter } from '@angular/material/core';
import { ApplicationConfig, provideZoneChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
import { environment } from '../environments/environment';
import {
  provideLucideIcons,
  LucideSearch,
  LucideCircleCheck,
  LucideUser,
  LucideStethoscope,
  LucideUserSearch,
} from '@lucide/angular';
import {
  provideKeycloak,
  includeBearerTokenInterceptor,
  createInterceptorCondition,
  INCLUDE_BEARER_TOKEN_INTERCEPTOR_CONFIG,
} from 'keycloak-angular';

function escapeRegex(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

const apiBasePattern = escapeRegex(environment.apiUrl);
const apiUrlCondition = createInterceptorCondition({
  urlPattern: new RegExp(`^${apiBasePattern}(\\/.*)?$`, 'i'),
  bearerPrefix: 'Bearer',
});

export const appConfig: ApplicationConfig = {
  providers: [
    provideLucideIcons(
      LucideSearch,
      LucideCircleCheck,
      LucideUser,
      LucideStethoscope,
      LucideUserSearch
    ),

    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),

    provideKeycloak({
      config: {
        url: environment.keycloak.url,
        realm: environment.keycloak.realm,
        clientId: environment.keycloak.clientId,
      },
      initOptions: {
        onLoad: 'check-sso',
        silentCheckSsoRedirectUri:
          window.location.origin + '/silent-check-sso.html',
        pkceMethod: 'S256',
        checkLoginIframe: false,
      },
    }),

    {
      provide: INCLUDE_BEARER_TOKEN_INTERCEPTOR_CONFIG,
      useValue: [apiUrlCondition],
    },

    provideHttpClient(
      withXhr(),
      withInterceptors([includeBearerTokenInterceptor])
    ),
    provideNativeDateAdapter(),
  ],
};
