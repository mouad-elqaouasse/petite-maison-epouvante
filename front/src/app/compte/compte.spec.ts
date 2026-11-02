import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Compte } from './compte';
import { EtatSession, SessionService } from './session.service';

// Ticket SQ-06 : connexion PKCE, puis nom et rôles tels que vus par chaque service (/moi, ADR 0011).
describe('Compte', () => {
  let http: HttpTestingController;
  const etat = signal<EtatSession>({ etat: 'verification' });
  const session = { etat: etat.asReadonly(), connecter: vi.fn(), deconnecter: vi.fn() };

  beforeEach(() => {
    etat.set({ etat: 'verification' });
    session.connecter.mockReset();
    session.deconnecter.mockReset();
    TestBed.configureTestingModule({
      imports: [Compte],
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: SessionService, useValue: session }],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function afficher() {
    const fixture = TestBed.createComponent(Compte);
    await fixture.whenStable();
    return fixture;
  }

  function bouton(page: HTMLElement, libelle: string): HTMLButtonElement | undefined {
    return [...page.querySelectorAll('button')].find((b) => b.textContent?.trim() === libelle);
  }

  function ligne(page: HTMLElement, service: string): string {
    return page.querySelector(`[data-service="${service}"]`)?.textContent ?? '';
  }

  it('annonce la vérification de la session sans appeler les services', async () => {
    const fixture = await afficher();

    expect(fixture.nativeElement.querySelector('[role="status"]')?.textContent).toContain('Vérification de la session');
    http.expectNone(() => true);
  });

  it('déconnecté : bouton « Se connecter » qui lance la connexion', async () => {
    etat.set({ etat: 'deconnecte' });
    const fixture = await afficher();
    const page: HTMLElement = fixture.nativeElement;

    bouton(page, 'Se connecter')?.click();

    expect(session.connecter).toHaveBeenCalledOnce();
    expect(bouton(page, 'Se déconnecter')).toBeUndefined();
    http.expectNone(() => true);
  });

  it('serveur de connexion injoignable : message d\'erreur annoncé et connexion toujours proposée', async () => {
    etat.set({ etat: 'erreur' });
    const fixture = await afficher();
    const page: HTMLElement = fixture.nativeElement;

    expect(page.querySelector('[role="alert"]')?.textContent).toContain('injoignable');
    expect(bouton(page, 'Se connecter')).toBeDefined();
  });

  it('connecté : nom, puis rôles renvoyés par chacun des deux /moi', async () => {
    etat.set({ etat: 'connecte', nom: 'moderateur-demo' });
    const fixture = await afficher();
    const page: HTMLElement = fixture.nativeElement;

    expect(page.textContent).toContain('moderateur-demo');
    expect(ligne(page, 'troc-service')).toContain('Lecture…');

    http.expectOne('/api/troc/moi').flush({ sub: 'a', nom: 'moderateur-demo', roles: ['MODERATEUR', 'USER'] });
    http.expectOne('/api/notifications/moi').flush({ sub: 'a', nom: 'moderateur-demo', roles: ['USER'] });
    await fixture.whenStable();

    expect(ligne(page, 'troc-service')).toContain('MODERATEUR');
    expect(ligne(page, 'troc-service')).toContain('USER');
    expect(ligne(page, 'notification-service')).toContain('USER');
    expect(ligne(page, 'notification-service')).not.toContain('MODERATEUR');
  });

  it('connecté : « indisponible » avec le code HTTP si un service refuse le jeton', async () => {
    etat.set({ etat: 'connecte', nom: 'membre-demo' });
    const fixture = await afficher();
    const page: HTMLElement = fixture.nativeElement;

    http.expectOne('/api/troc/moi').flush('', { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/notifications/moi').flush({ sub: 'b', nom: 'membre-demo', roles: [] });
    await fixture.whenStable();

    expect(ligne(page, 'troc-service')).toContain('indisponible');
    expect(ligne(page, 'troc-service')).toContain('401');
    expect(ligne(page, 'notification-service')).toContain('aucun rôle');
  });

  it('connecté : bouton « Se déconnecter » qui lance la déconnexion', async () => {
    etat.set({ etat: 'connecte', nom: 'membre-demo' });
    const fixture = await afficher();
    http.match(() => true).forEach((requete) => requete.flush({ roles: ['USER'] }));

    bouton(fixture.nativeElement, 'Se déconnecter')?.click();

    expect(session.deconnecter).toHaveBeenCalledOnce();
  });
});
