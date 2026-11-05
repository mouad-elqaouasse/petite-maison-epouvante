import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, RouterStateSnapshot } from '@angular/router';
import { firstValueFrom, isObservable, Observable } from 'rxjs';
import { connexionGuard, DELAI_VERIFICATION_SESSION_MS } from './connexion.guard';
import { EtatSession, SessionService } from './session.service';

// US1a : les écrans des annonces sont réservés aux membres connectés (docs/backlog/US1a, « Écran Angular minimal »).
describe('connexionGuard', () => {
  const etat = signal<EtatSession>({ etat: 'verification' });
  const session = { etat: etat.asReadonly(), connecter: vi.fn() };

  beforeEach(() => {
    session.connecter.mockReset();
    TestBed.configureTestingModule({ providers: [{ provide: SessionService, useValue: session }] });
  });

  function lancer(url = '/annonces'): Promise<boolean> {
    const resultat = TestBed.runInInjectionContext(() =>
      connexionGuard({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot),
    );
    return isObservable(resultat) ? firstValueFrom(resultat as Observable<boolean>) : Promise.resolve(resultat as boolean);
  }

  it('membre connecté : accès autorisé', async () => {
    etat.set({ etat: 'connecte', nom: 'membre-demo' });

    expect(await lancer()).toBe(true);
    expect(session.connecter).not.toHaveBeenCalled();
  });

  it('non connecté : redirection vers Keycloak, retour sur la page demandée', async () => {
    etat.set({ etat: 'deconnecte' });

    expect(await lancer('/annonces/nouvelle')).toBe(false);
    expect(session.connecter).toHaveBeenCalledWith(`${window.location.origin}/annonces/nouvelle`);
  });

  // Vérification silencieuse bloquée (iframe refusée par Keycloak, réseau) : pas de page blanche sans fin.
  it('vérification trop longue : redirection vers Keycloak après le délai maximum', async () => {
    vi.useFakeTimers();
    try {
      etat.set({ etat: 'verification' });
      const decision = lancer('/annonces/nouvelle');

      await vi.advanceTimersByTimeAsync(DELAI_VERIFICATION_SESSION_MS - 1);
      expect(session.connecter).not.toHaveBeenCalled();

      await vi.advanceTimersByTimeAsync(1);
      expect(await decision).toBe(false);
      expect(session.connecter).toHaveBeenCalledWith(`${window.location.origin}/annonces/nouvelle`);
    } finally {
      vi.useRealTimers();
    }
  });

  it('attend la fin de la vérification de session avant de décider', async () => {
    etat.set({ etat: 'verification' });
    const decision = lancer();

    etat.set({ etat: 'connecte', nom: 'membre-demo' });
    TestBed.tick();

    expect(await decision).toBe(true);
  });
});
