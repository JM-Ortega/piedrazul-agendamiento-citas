import {
  provideHttpClient,
  withInterceptors,
  withXhr,
} from '@angular/common/http';
import { ApplicationConfig, provideZoneChangeDetection } from '@angular/core';
import { provideNativeDateAdapter } from '@angular/material/core';
import { provideRouter } from '@angular/router';
import {
  LucideCircleCheck,
  LucideSearch,
  LucideStethoscope,
  LucideUser,
  LucideUserSearch,
  provideLucideIcons,
} from '@lucide/angular';
import {
  createInterceptorCondition,
  INCLUDE_BEARER_TOKEN_INTERCEPTOR_CONFIG,
  includeBearerTokenInterceptor,
  provideKeycloak,
} from 'keycloak-angular';
import { environment } from '../environments/environment';
import { routes } from './app.routes';
import { errorInterceptor } from './core/interceptors/error.interceptor';

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
      withInterceptors([includeBearerTokenInterceptor, errorInterceptor])
    ),
    provideNativeDateAdapter(),
  ],
};
