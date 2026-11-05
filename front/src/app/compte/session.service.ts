import { inject, Injectable, signal } from '@angular/core';
import Keycloak from 'keycloak-js';

/** Session Keycloak : vérification silencieuse en cours, déconnecté, connecté (nom) ou serveur injoignable. */
export type EtatSession =
  | { etat: 'verification' }
  | { etat: 'deconnecte' }
  | { etat: 'connecte'; nom: string }
  | { etat: 'erreur' };

/**
 * Authorization Code + PKCE (docs/cadrage.md §1.2.9), jetons en mémoire seulement.
 * check-sso : au rechargement de la page, la session Keycloak est retrouvée par une iframe invisible
 * (silent-check-sso.html), sans redirection ni nouvelle saisie du mot de passe.
 */
@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly keycloak = inject(Keycloak);
  private readonly etatCourant = signal<EtatSession>({ etat: 'verification' });

  readonly etat = this.etatCourant.asReadonly();

  /** Lancé au démarrage sans le bloquer : les versions s'affichent même si Keycloak ne répond pas. */
  async demarrer(): Promise<void> {
    try {
      const connecte = await this.keycloak.init({
        onLoad: 'check-sso',
        silentCheckSsoRedirectUri: `${window.location.origin}/silent-check-sso.html`,
        pkceMethod: 'S256',
        // Pas d'iframe de surveillance permanente de la session : seulement la vérification au chargement.
        checkLoginIframe: false,
      });
      this.etatCourant.set(
        connecte ? { etat: 'connecte', nom: this.keycloak.tokenParsed?.['preferred_username'] ?? '' } : { etat: 'deconnecte' },
      );
    } catch {
      this.etatCourant.set({ etat: 'erreur' });
    }
  }

  /** Sans adresse de retour, Keycloak ramène sur la page courante. */
  connecter(retour?: string): void {
    void this.keycloak.login(retour ? { redirectUri: retour } : undefined);
  }

  deconnecter(): void {
    void this.keycloak.logout({ redirectUri: `${window.location.origin}/` });
  }
}
