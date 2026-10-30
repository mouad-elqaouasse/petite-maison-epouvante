import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Versions } from './versions';

// Critères d'acceptation de SQ-03 : affichage des versions ; « indisponible » si une API répond en erreur.
describe('Versions', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [Versions],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function afficher() {
    const fixture = TestBed.createComponent(Versions);
    await fixture.whenStable();
    return fixture;
  }

  function ligne(element: HTMLElement, composant: string): string {
    return element.querySelector(`[data-composant="${composant}"]`)?.textContent ?? '';
  }

  const SHA_TROC = '9c3f132aa645b147e1da3a5953d81188c1b4dc69';
  const SHA_NOTIF = '0f7233f63689290969b9e2549e739dc2077748ab';
  const SHA_FRONT = '762f2801d2c4e5f6a7b8c9d0e1f2a3b4c5d6e7f8';

  it('affiche « Lecture… » tant que les versions ne sont pas arrivées', async () => {
    const fixture = await afficher();
    const page: HTMLElement = fixture.nativeElement;

    expect(ligne(page, 'troc-service')).toContain('Lecture…');
    expect(ligne(page, 'notification-service')).toContain('Lecture…');
    expect(ligne(page, 'front')).toContain('Lecture…');

    http.match(() => true).forEach((requete) => requete.flush({}));
  });

  it('affiche le SHA court des deux services et du front, et le SHA complet en titre', async () => {
    const fixture = await afficher();

    http.expectOne('/api/troc/version').flush({ service: 'troc-service', version: SHA_TROC });
    http.expectOne('/api/notifications/version').flush({ service: 'notification-service', version: SHA_NOTIF });
    http.expectOne('/version.json').flush({ service: 'front', version: SHA_FRONT });
    await fixture.whenStable();

    const page: HTMLElement = fixture.nativeElement;
    expect(ligne(page, 'troc-service')).toContain('9c3f132');
    expect(ligne(page, 'notification-service')).toContain('0f7233f');
    expect(ligne(page, 'front')).toContain('762f280');
    expect(page.querySelector('[data-composant="troc-service"] [title]')?.getAttribute('title')).toBe(SHA_TROC);
  });

  it('affiche « indisponible » pour le service qui répond en erreur, et les autres versions normalement', async () => {
    const fixture = await afficher();

    http.expectOne('/api/troc/version').flush('erreur', { status: 503, statusText: 'Service Unavailable' });
    http.expectOne('/api/notifications/version').flush({ service: 'notification-service', version: SHA_NOTIF });
    http.expectOne('/version.json').flush({ service: 'front', version: SHA_FRONT });
    await fixture.whenStable();

    const page: HTMLElement = fixture.nativeElement;
    expect(ligne(page, 'troc-service')).toContain('indisponible');
    expect(ligne(page, 'troc-service')).toContain('503');
    expect(ligne(page, 'notification-service')).toContain('0f7233f');
  });

  it('affiche « indisponible » si le service est injoignable', async () => {
    const fixture = await afficher();

    http.expectOne('/api/troc/version').flush({ service: 'troc-service', version: SHA_TROC });
    http.expectOne('/api/notifications/version').error(new ProgressEvent('error'));
    http.expectOne('/version.json').flush({ service: 'front', version: SHA_FRONT });
    await fixture.whenStable();

    expect(ligne(fixture.nativeElement, 'notification-service')).toContain('indisponible');
  });

  it('affiche « indisponible » si la réponse ne contient pas de version', async () => {
    const fixture = await afficher();

    http.expectOne('/api/troc/version').flush({ service: 'troc-service', version: SHA_TROC });
    http.expectOne('/api/notifications/version').flush({ service: 'notification-service', version: SHA_NOTIF });
    http.expectOne('/version.json').flush('<html></html>');
    await fixture.whenStable();

    expect(ligne(fixture.nativeElement, 'front')).toContain('indisponible');
  });

  it('annonce les résultats aux lecteurs d\'écran une fois toutes les réponses reçues', async () => {
    const fixture = await afficher();
    const page: HTMLElement = fixture.nativeElement;
    const liste = page.querySelector('[aria-live="polite"]');

    expect(liste?.getAttribute('aria-busy')).toBe('true');

    http.expectOne('/api/troc/version').flush({ service: 'troc-service', version: SHA_TROC });
    http.expectOne('/api/notifications/version').flush({ service: 'notification-service', version: SHA_NOTIF });
    http.expectOne('/version.json').flush({ service: 'front', version: SHA_FRONT });
    await fixture.whenStable();

    expect(liste?.getAttribute('aria-busy')).toBe('false');
  });
});
