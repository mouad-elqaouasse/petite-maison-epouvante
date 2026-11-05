import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { AnnoncesService } from './annonces.service';
import { ListeAnnonces } from './liste-annonces';

// US1a, écran « Annonces » : liste des annonces publiées (photo, titre, catégorie, type, date) et ses états.
describe('ListeAnnonces', () => {
  let http: HttpTestingController;

  const chucky = {
    id: 'a1',
    titre: 'Figurine Chucky 1988',
    description: 'Bon état',
    categorie: 'Figurines',
    type: 'ECHANGE',
    statut: 'PUBLIEE',
    auteurId: 'sub-alice',
    photoUrl: 'https://res.cloudinary.com/demo/image/upload/chucky.jpg',
    creeLe: '2026-11-05T10:15:30Z',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ListeAnnonces],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function afficher() {
    const fixture = TestBed.createComponent(ListeAnnonces);
    await fixture.whenStable();
    return fixture;
  }

  it('annonce le chargement de la liste', async () => {
    const fixture = await afficher();

    expect(fixture.nativeElement.querySelector('[role="status"]')?.textContent).toContain('Chargement des annonces');
    http.expectOne('/api/troc/annonces');
  });

  it('affiche chaque annonce avec sa photo, son titre, sa catégorie, son type et sa date', async () => {
    const fixture = await afficher();
    http.expectOne('/api/troc/annonces').flush([chucky]);
    await fixture.whenStable();
    const page: HTMLElement = fixture.nativeElement;

    const carte = page.querySelector('article');
    expect(carte?.querySelector('h2')?.textContent).toContain('Figurine Chucky 1988');
    const photo = carte?.querySelector('img');
    expect(photo?.getAttribute('src')).toContain('/image/upload/c_fill,ar_4:3,w_600,f_auto,q_auto/');
    expect(photo?.getAttribute('alt')).toBe('Figurine Chucky 1988');
    expect(carte?.textContent).toContain('Figurines');
    expect(carte?.textContent).toContain('Échange');
    expect(carte?.textContent).toContain('5 novembre 2026');
  });

  it('photo Cloudinary : src transformée en 4:3 sur 600 px, srcset, taille fixée et sizes', async () => {
    const fixture = await afficher();
    http.expectOne('/api/troc/annonces').flush([chucky]);
    await fixture.whenStable();
    const photo = (fixture.nativeElement as HTMLElement).querySelector('article img') as HTMLImageElement;

    expect(photo.getAttribute('src')).toContain('/image/upload/c_fill,ar_4:3,w_600,f_auto,q_auto/');
    expect(photo.getAttribute('srcset')).toContain('w_1200,f_auto,q_auto/chucky.jpg 1200w');
    expect(photo.getAttribute('sizes')).toBeTruthy();
    expect(photo.getAttribute('width')).toBe('600');
    expect(photo.getAttribute('height')).toBe('450');
  });

  it('photo factice (hors Cloudinary) : affichée telle quelle, sans srcset', async () => {
    const fixture = await afficher();
    http.expectOne('/api/troc/annonces').flush([{ ...chucky, photoUrl: 'https://photos.invalid/factice/x' }]);
    await fixture.whenStable();
    const photo = (fixture.nativeElement as HTMLElement).querySelector('article img') as HTMLImageElement;

    expect(photo.getAttribute('src')).toBe('https://photos.invalid/factice/x');
    expect(photo.hasAttribute('srcset')).toBe(false);
  });

  it('apparition en fondu : chaque carte reçoit son rang, pour un décalage plafonné en CSS', async () => {
    const fixture = await afficher();
    http.expectOne('/api/troc/annonces').flush([chucky, { ...chucky, id: 'a2' }, { ...chucky, id: 'a3' }]);
    await fixture.whenStable();
    const rangs = [...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('.annonces-grille > li')]
      .map((li) => li.style.getPropertyValue('--rang'));

    expect(rangs).toEqual(['0', '1', '2']);
  });

  it('carte : catégorie en badge sur la photo et description', async () => {
    const fixture = await afficher();
    http.expectOne('/api/troc/annonces').flush([chucky]);
    await fixture.whenStable();
    const carte = (fixture.nativeElement as HTMLElement).querySelector('article');

    expect(carte?.querySelector('.carte-badge')?.textContent).toContain('Figurines');
    expect(carte?.querySelector('.carte-description')?.textContent).toContain('Bon état');
  });

  it('chargement : trois cartes vides, masquées aux lecteurs d\'écran', async () => {
    const fixture = await afficher();
    const page: HTMLElement = fixture.nativeElement;

    const fantomes = page.querySelectorAll('.carte-fantome');
    expect(fantomes.length).toBe(3);
    expect(fantomes[0].closest('[aria-hidden="true"]')).not.toBeNull();
    http.expectOne('/api/troc/annonces');
  });

  it('liste vide : invitation à publier', async () => {
    const fixture = await afficher();
    http.expectOne('/api/troc/annonces').flush([]);
    await fixture.whenStable();
    const page: HTMLElement = fixture.nativeElement;

    expect(page.textContent).toContain('Aucune annonce pour l’instant');
    expect(page.querySelector('a[href="/annonces/nouvelle"]')).not.toBeNull();
  });

  it('erreur : message annoncé et bouton « Réessayer » qui relance la lecture', async () => {
    const fixture = await afficher();
    http.expectOne('/api/troc/annonces').flush('', { status: 503, statusText: 'Service Unavailable' });
    await fixture.whenStable();
    const page: HTMLElement = fixture.nativeElement;

    expect(page.querySelector('[role="alert"]')?.textContent).toContain('Les annonces n’ont pas pu être chargées');
    [...page.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Réessayer')?.click();
    await fixture.whenStable();

    http.expectOne('/api/troc/annonces').flush([chucky]);
    await fixture.whenStable();
    expect(page.querySelector('article h2')?.textContent).toContain('Figurine Chucky 1988');
  });

  it('après une publication : « Annonce publiée » annoncé une seule fois', async () => {
    TestBed.inject(AnnoncesService).signalerPublication();
    const fixture = await afficher();
    http.expectOne('/api/troc/annonces').flush([chucky]);
    await fixture.whenStable();

    expect(fixture.nativeElement.querySelector('[aria-live="polite"]')?.textContent).toContain('Annonce publiée');
    expect(TestBed.inject(AnnoncesService).prendrePublication()).toBe(false);
  });

  it('propose toujours le lien pour publier une annonce', async () => {
    const fixture = await afficher();
    http.expectOne('/api/troc/annonces').flush([chucky]);
    await fixture.whenStable();

    expect(fixture.nativeElement.querySelector('a[href="/annonces/nouvelle"]')?.textContent).toContain('Publier une annonce');
  });
});
