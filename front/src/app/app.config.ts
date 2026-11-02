import { ApplicationConfig, inject, provideAppInitializer, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import {
  createInterceptorCondition,
  INCLUDE_BEARER_TOKEN_INTERCEPTOR_CONFIG,
  IncludeBearerTokenCondition,
  includeBearerTokenInterceptor,
  provideKeycloak,
} from 'keycloak-angular';
import { environment } from '../environments/environment';
import { routes } from './app.routes';
import { SessionService } from './compte/session.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    // Sans initOptions : Keycloak est initialisé par SessionService, sans bloquer le démarrage de l'application.
    provideKeycloak({
      config: { url: environment.keycloakUrl, realm: 'epouvante', clientId: 'epouvante-front' },
    }),
    provideAppInitializer(() => {
      void inject(SessionService).demarrer();
    }),
    // Jeton ajouté seulement aux appels de nos API (même origine, ADR 0006), rafraîchi s'il expire bientôt.
    {
      provide: INCLUDE_BEARER_TOKEN_INTERCEPTOR_CONFIG,
      useValue: [createInterceptorCondition<IncludeBearerTokenCondition>({ urlPattern: /^\/api\// })],
    },
    provideHttpClient(withFetch(), withInterceptors([includeBearerTokenInterceptor])),
  ]
};
