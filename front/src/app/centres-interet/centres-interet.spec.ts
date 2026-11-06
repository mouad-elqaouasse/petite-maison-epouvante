import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { CentresInteret } from './centres-interet';

// US2, écran « Mes centres d'intérêt » : une case par catégorie (jamais codée en dur), cochée si suivie ;
// cocher = PUT, décocher = DELETE ; messages annoncés (aria-live) ; états chargement, erreur, aucune catégorie suivie.
describe('CentresInteret', () => {
  let http: HttpTestingController;
  const CATEGORIES = ['Figurines', 'Films', 'BD', 'Jeux de société', 'Goodies'];
  const URL_ABONNEMENTS = '/api/notifications/abonnements';

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [CentresInteret],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function afficher() {
    const fixture = TestBed.createComponent(CentresInteret);
    await fixture.whenStable();
    return fixture;
  }

  async function afficherAvec(suivies: string[]) {
    const fixture = await afficher();
    http.expectOne('/api/troc/categories').flush(CATEGORIES);
    http.expectOne(URL_ABONNEMENTS).flush(suivies);
    await fixture.whenStable();
    return fixture;
  }

  function cases(page: HTMLElement): HTMLInputElement[] {
    return Array.from(page.querySelectorAll<HTMLInputElement>('input[type="checkbox"]'));
  }

  function caseDe(page: HTMLElement, libelle: string): HTMLInputElement {
    const label = Array.from(page.querySelectorAll('label')).find((l) => l.textContent?.trim() === libelle);
    return page.querySelector<HTMLInputElement>(`#${label?.getAttribute('for')}`)!;
  }

  function statut(page: HTMLElement): string {
    return page.querySelector('[aria-live]')?.textContent?.trim() ?? '';
  }

  it('affiche le chargement tant que les listes ne sont pas reçues', async () => {
    const fixture = await afficher();
    const page: HTMLElement = fixture.nativeElement;

    expect(page.textContent).toContain('Chargement des catégories…');
    expect(cases(page)).toHaveLength(0);

    http.expectOne('/api/troc/categories').flush(CATEGORIES);
    http.expectOne(URL_ABONNEMENTS).flush([]);
  });

  it('affiche une case par catégorie, avec son libellé, dans un groupe nommé', async () => {
    const page: HTMLElement = (await afficherAvec([])).nativeElement;

    expect(page.querySelector('fieldset legend')?.textContent).toContain('Catégories suivies');
    expect(cases(page)).toHaveLength(5);
    for (const categorie of CATEGORIES) {
      expect(caseDe(page, categorie)).toBeTruthy();
    }
  });

  it('coche les catégories déjà suivies', async () => {
    const page: HTMLElement = (await afficherAvec(['BD', 'Films'])).nativeElement;

    expect(caseDe(page, 'BD').checked).toBe(true);
    expect(caseDe(page, 'Films').checked).toBe(true);
    expect(caseDe(page, 'Goodies').checked).toBe(false);
  });

  it('invite à choisir quand aucune catégorie n’est suivie', async () => {
    const page: HTMLElement = (await afficherAvec([])).nativeElement;

    expect(page.textContent).toContain('Vous ne suivez aucune catégorie');
  });

  it('cocher une case ajoute l’abonnement avec PUT et l’annonce', async () => {
    const fixture = await afficherAvec([]);
    const page: HTMLElement = fixture.nativeElement;

    caseDe(page, 'Jeux de société').click();
    await fixture.whenStable();
    expect(caseDe(page, 'Jeux de société').getAttribute('aria-busy')).toBe('true');

    const requete = http.expectOne(`${URL_ABONNEMENTS}/Jeux%20de%20soci%C3%A9t%C3%A9`);
    expect(requete.request.method).toBe('PUT');
    requete.flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();

    expect(caseDe(page, 'Jeux de société').checked).toBe(true);
    expect(caseDe(page, 'Jeux de société').getAttribute('aria-busy')).toBeNull();
    expect(statut(page)).toBe('Abonnement à Jeux de société ajouté.');
    expect(page.textContent).not.toContain('Vous ne suivez aucune catégorie');
  });

  // La case n'est pas désactivée pendant l'appel (elle perdrait le focus clavier) : un second clic est ignoré.
  it('pendant l’enregistrement, la case garde le focus et un second clic est ignoré', async () => {
    const fixture = await afficherAvec([]);
    const page: HTMLElement = fixture.nativeElement;
    const bd = caseDe(page, 'BD');
    bd.focus();

    bd.click();
    await fixture.whenStable();
    bd.click();
    await fixture.whenStable();

    expect(document.activeElement).toBe(bd);
    expect(bd.disabled).toBe(false);
    expect(bd.checked).toBe(true);
    http.expectOne(`${URL_ABONNEMENTS}/BD`).flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();
    expect(caseDe(page, 'BD').checked).toBe(true);
  });

  it('décocher une case retire l’abonnement avec DELETE et l’annonce', async () => {
    const fixture = await afficherAvec(['BD']);
    const page: HTMLElement = fixture.nativeElement;

    caseDe(page, 'BD').click();
    await fixture.whenStable();

    const requete = http.expectOne(`${URL_ABONNEMENTS}/BD`);
    expect(requete.request.method).toBe('DELETE');
    requete.flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();

    expect(caseDe(page, 'BD').checked).toBe(false);
    expect(statut(page)).toBe('Abonnement à BD retiré.');
  });

  it('en cas d’échec, la case revient à son état et l’erreur est annoncée', async () => {
    const fixture = await afficherAvec([]);
    const page: HTMLElement = fixture.nativeElement;

    caseDe(page, 'BD').click();
    await fixture.whenStable();
    http.expectOne(`${URL_ABONNEMENTS}/BD`).flush('', { status: 503, statusText: 'Service Unavailable' });
    await fixture.whenStable();

    expect(caseDe(page, 'BD').checked).toBe(false);
    expect(caseDe(page, 'BD').getAttribute('aria-busy')).toBeNull();
    expect(statut(page)).toBe('L’abonnement à BD n’a pas pu être modifié. Réessayez.');
  });

  it('si une des listes ne se charge pas, affiche l’erreur et permet de réessayer', async () => {
    const fixture = await afficher();
    const page: HTMLElement = fixture.nativeElement;
    http.expectOne('/api/troc/categories').flush(CATEGORIES);
    http.expectOne(URL_ABONNEMENTS).flush('', { status: 503, statusText: 'Service Unavailable' });
    await fixture.whenStable();

    expect(page.querySelector('[role="alert"]')?.textContent).toContain('Vos centres d’intérêt n’ont pas pu être chargés.');
    expect(cases(page)).toHaveLength(0);

    const reessayer = Array.from(page.querySelectorAll('button')).find((b) => b.textContent?.includes('Réessayer'));
    reessayer!.click();
    await fixture.whenStable();
    http.expectOne('/api/troc/categories').flush(CATEGORIES);
    http.expectOne(URL_ABONNEMENTS).flush(['BD']);
    await fixture.whenStable();

    expect(page.querySelector('[role="alert"]')).toBeNull();
    expect(caseDe(page, 'BD').checked).toBe(true);
  });
});
