import { inject } from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import { CanActivateFn } from '@angular/router';
import { filter, map, race, take, timer } from 'rxjs';
import { EtatSession, SessionService } from './session.service';

/**
 * Délai maximum de la vérification silencieuse de session. Au-delà (iframe refusée par Keycloak, réseau lent), le
 * membre est envoyé sur la page de connexion de Keycloak, qui le ramène aussitôt s'il a déjà une session : jamais de
 * page blanche sans fin.
 */
export const DELAI_VERIFICATION_SESSION_MS = 10_000;

/**
 * Routes réservées aux membres connectés (US1a). Attend la fin de la vérification silencieuse de session, dans la
 * limite de DELAI_VERIFICATION_SESSION_MS ; sans session, redirige vers Keycloak, qui ramène sur la page demandée.
 * Confort seulement : l'autorisation est vérifiée par les services (jeton exigé sur /api/**).
 */
export const connexionGuard: CanActivateFn = (_route, etat) => {
  const session = inject(SessionService);
  const verifiee = toObservable(session.etat).pipe(
    filter((e) => e.etat !== 'verification'),
    take(1),
  );
  const tropLongue = timer(DELAI_VERIFICATION_SESSION_MS).pipe(map((): EtatSession => ({ etat: 'deconnecte' })));
  return race(verifiee, tropLongue).pipe(
    map((e) => {
      if (e.etat === 'connecte') {
        return true;
      }
      session.connecter(`${window.location.origin}${etat.url}`);
      return false;
    }),
  );
};
