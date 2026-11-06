import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { Notifications } from './notifications';
import { Notification } from './notifications.service';

// US3, écran « Mes notifications » : lecture seule, plus récentes d'abord (ordre de l'API) ; type, texte, titre de
// l'annonce, date ; états chargement, erreur (Réessayer), aucune notification, liste. Pas de compteur avant US4.
describe('Notifications', () => {
  let http: HttpTestingController;
  const URL = '/api/notifications';

  const ALERTE: Notification = {
    type: 'ALERTE',
    texte: 'Nouvelle annonce',
    annonceId: '229ca86c-826c-4114-82f4-22ed6669b34b',
    titreAnnonce: 'Affiche Halloween 1978',
    creeLe: '2026-11-05T16:17:55.262Z',
  };
  const CONFIRMATION: Notification = {
    type: 'CONFIRMATION',
    texte: 'Votre annonce est publiée',
    annonceId: '5496c496-6b6e-4ad2-bb10-a54b82bf2416',
    titreAnnonce: 'Figurine Chucky 1988',
    creeLe: '2026-11-05T13:14:05.797Z',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [Notifications],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function afficher() {
    const fixture = TestBed.createComponent(Notifications);
    await fixture.whenStable();
    return fixture;
  }

  async function afficherAvec(notifications: Notification[]) {
    const fixture = await afficher();
    http.expectOne(URL).flush(notifications);
    await fixture.whenStable();
    return fixture;
  }

  function elements(page: HTMLElement): HTMLElement[] {
    return Array.from(page.querySelectorAll<HTMLElement>('.notifications-liste > li'));
  }

  it('affiche le chargement, annoncé, tant que la liste n’est pas reçue', async () => {
    const page: HTMLElement = (await afficher()).nativeElement;

    expect(page.querySelector('[role="status"]')?.textContent).toContain('Chargement des notifications…');

    http.expectOne(URL).flush([]);
  });

  it('affiche chaque notification avec son type, son texte, le titre de l’annonce et la date', async () => {
    const page: HTMLElement = (await afficherAvec([ALERTE, CONFIRMATION])).nativeElement;

    const [premiere, seconde] = elements(page);
    expect(elements(page)).toHaveLength(2);
    expect(premiere.textContent).toContain('Alerte');
    expect(premiere.textContent).toContain('Nouvelle annonce');
    expect(premiere.textContent).toContain('Affiche Halloween 1978');
    expect(premiere.querySelector('time')?.getAttribute('datetime')).toBe(ALERTE.creeLe);
    expect(premiere.querySelector('time')?.textContent).toContain('5 novembre 2026');
    expect(seconde.textContent).toContain('Confirmation');
    expect(seconde.textContent).toContain('Votre annonce est publiée');
    expect(seconde.textContent).toContain('Figurine Chucky 1988');
  });

  it('garde l’ordre de l’API : la plus récente d’abord', async () => {
    const page: HTMLElement = (await afficherAvec([ALERTE, CONFIRMATION])).nativeElement;

    expect(elements(page).map((e) => e.querySelector('.notification-annonce')?.textContent?.trim())).toEqual([
      'Affiche Halloween 1978',
      'Figurine Chucky 1988',
    ]);
  });

  it('sans notification, invite à suivre des catégories', async () => {
    const page: HTMLElement = (await afficherAvec([])).nativeElement;

    expect(elements(page)).toHaveLength(0);
    expect(page.textContent).toContain('Aucune notification pour l’instant.');
    expect(page.querySelector<HTMLAnchorElement>('a[href="/centres-interet"]')?.textContent).toContain(
      'Choisir mes centres d’intérêt',
    );
  });

  it('en cas d’échec, annonce l’erreur et permet de réessayer', async () => {
    const fixture = await afficher();
    const page: HTMLElement = fixture.nativeElement;
    http.expectOne(URL).flush(null, { status: 503, statusText: 'Service Unavailable' });
    await fixture.whenStable();

    expect(page.querySelector('[role="alert"]')?.textContent).toContain('Vos notifications n’ont pas pu être chargées.');

    const reessayer = Array.from(page.querySelectorAll('button')).find((b) => b.textContent?.includes('Réessayer'))!;
    reessayer.click();
    await fixture.whenStable();
    http.expectOne(URL).flush([ALERTE]);
    await fixture.whenStable();

    expect(elements(page)).toHaveLength(1);
  });

  it('le bouton Actualiser recharge la liste', async () => {
    const fixture = await afficherAvec([CONFIRMATION]);
    const page: HTMLElement = fixture.nativeElement;

    const actualiser = Array.from(page.querySelectorAll('button')).find((b) => b.textContent?.includes('Actualiser'))!;
    actualiser.click();
    await fixture.whenStable();
    http.expectOne(URL).flush([ALERTE, CONFIRMATION]);
    await fixture.whenStable();

    expect(elements(page)).toHaveLength(2);
  });

  it('n’affiche aucun compteur (il arrive avec le marquage « lue », US4)', async () => {
    const page: HTMLElement = (await afficherAvec([ALERTE, CONFIRMATION])).nativeElement;

    expect(page.querySelector('h1')?.textContent?.trim()).toBe('Mes notifications');
  });
});
