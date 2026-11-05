import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { AnnoncesService } from './annonces.service';
import { PublierAnnonce } from './publier-annonce';

// US1a, écran « Publier une annonce » : formulaire, contrôles côté client (confort), erreurs du serveur, succès.
describe('PublierAnnonce', () => {
  let http: HttpTestingController;
  const CATEGORIES = ['Figurines', 'Films', 'BD', 'Jeux de société', 'Goodies'];

  beforeEach(() => {
    // jsdom ne fournit pas les URL blob: des miniatures.
    let numero = 0;
    URL.createObjectURL = vi.fn(() => `blob:apercu-${++numero}`);
    URL.revokeObjectURL = vi.fn();
    TestBed.configureTestingModule({
      imports: [PublierAnnonce],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function afficher() {
    const fixture = TestBed.createComponent(PublierAnnonce);
    await fixture.whenStable();
    return fixture;
  }

  async function afficherAvecCategories() {
    const fixture = await afficher();
    http.expectOne('/api/troc/categories').flush(CATEGORIES);
    await fixture.whenStable();
    return fixture;
  }

  function champ<T extends HTMLElement>(page: HTMLElement, id: string): T {
    return page.querySelector(`#${id}`) as T;
  }

  function saisir(page: HTMLElement, id: string, valeur: string) {
    const element = champ<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>(page, id);
    element.value = valeur;
    element.dispatchEvent(new Event(element instanceof HTMLSelectElement ? 'change' : 'input'));
  }

  function choisirPhoto(page: HTMLElement, fichier: File) {
    const input = champ<HTMLInputElement>(page, 'photo');
    Object.defineProperty(input, 'files', { value: [fichier], configurable: true });
    input.dispatchEvent(new Event('change'));
  }

  function photo(taille: number, type = 'image/jpeg', nom = 'chucky.jpg'): File {
    return new File([new Uint8Array(taille)], nom, { type });
  }

  async function remplir(fixture: Awaited<ReturnType<typeof afficher>>) {
    const page: HTMLElement = fixture.nativeElement;
    saisir(page, 'titre', 'Figurine Chucky 1988');
    saisir(page, 'description', 'Bon état');
    saisir(page, 'categorie', 'Figurines');
    champ<HTMLInputElement>(page, 'type-ECHANGE').click();
    choisirPhoto(page, photo(150 * 1024));
    await fixture.whenStable();
  }

  async function publier(fixture: Awaited<ReturnType<typeof afficher>>) {
    (fixture.nativeElement as HTMLElement).querySelector('form')?.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
  }

  function erreur(page: HTMLElement, id: string): string {
    const describedBy = champ(page, id).getAttribute('aria-describedby') ?? '';
    return describedBy
      .split(' ')
      .map((ref) => page.querySelector(`#${ref}`)?.textContent ?? '')
      .join(' ');
  }

  it('remplit la liste des catégories avec GET /categories', async () => {
    const fixture = await afficherAvecCategories();
    const options = [...champ<HTMLSelectElement>(fixture.nativeElement, 'categorie').options].map((o) => o.textContent?.trim());

    expect(options).toEqual(expect.arrayContaining(CATEGORIES));
  });

  it('catégories indisponibles : message et bouton « Réessayer »', async () => {
    const fixture = await afficher();
    http.expectOne('/api/troc/categories').flush('', { status: 503, statusText: 'Service Unavailable' });
    await fixture.whenStable();
    const page: HTMLElement = fixture.nativeElement;

    expect(page.querySelector('[role="alert"]')?.textContent).toContain('catégories');
    [...page.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Réessayer')?.click();
    await fixture.whenStable();
    http.expectOne('/api/troc/categories').flush(CATEGORIES);
  });

  it('chaque champ a un libellé', async () => {
    const fixture = await afficherAvecCategories();
    const page: HTMLElement = fixture.nativeElement;

    for (const id of ['titre', 'description', 'categorie', 'photo', 'type-ECHANGE', 'type-DON']) {
      expect(page.querySelector(`label[for="${id}"]`), id).not.toBeNull();
    }
  });

  it('formulaire vide : erreurs sous chaque champ, reliées au champ, sans appel au serveur', async () => {
    const fixture = await afficherAvecCategories();
    const page: HTMLElement = fixture.nativeElement;

    await publier(fixture);

    expect(erreur(page, 'titre')).toContain('entre 3 et 80 caractères');
    expect(champ(page, 'titre').getAttribute('aria-invalid')).toBe('true');
    expect(erreur(page, 'categorie')).toContain('Choisissez une catégorie');
    expect(erreur(page, 'type-ECHANGE')).toContain('Choisissez le type');
    expect(erreur(page, 'photo')).toContain('Ajoutez une photo');
    http.expectNone('/api/troc/annonces');
  });

  it('photo de plus de 2 Mo ou au mauvais format : erreur sous le champ', async () => {
    const fixture = await afficherAvecCategories();
    const page: HTMLElement = fixture.nativeElement;

    choisirPhoto(page, photo(2 * 1024 * 1024 + 1));
    await fixture.whenStable();
    expect(erreur(page, 'photo')).toContain('2 Mo');

    choisirPhoto(page, photo(1024, 'image/gif', 'chauve-souris.gif'));
    await fixture.whenStable();
    expect(erreur(page, 'photo')).toContain('JPEG, PNG ou WebP');
  });

  it('zone de dépôt : une photo glissée est retenue comme si elle était choisie', async () => {
    const fixture = await afficherAvecCategories();
    const page: HTMLElement = fixture.nativeElement;
    const zone = page.querySelector('.depot') as HTMLElement;
    const fichier = photo(150 * 1024, 'image/png', 'masque.png');

    const survol = new Event('dragover', { cancelable: true });
    Object.defineProperty(survol, 'dataTransfer', { value: { files: [] } });
    zone.dispatchEvent(survol);
    await fixture.whenStable();
    expect(survol.defaultPrevented).toBe(true);
    expect(zone.classList).toContain('depot-survol');

    const depot = new Event('drop', { cancelable: true });
    Object.defineProperty(depot, 'dataTransfer', { value: { files: [fichier] } });
    zone.dispatchEvent(depot);
    await fixture.whenStable();

    expect(depot.defaultPrevented).toBe(true);
    expect(zone.classList).not.toContain('depot-survol');
    expect(page.querySelector('.depot-fichier')?.textContent).toContain('masque.png');
  });

  it('zone photo vide : icône, consigne et bouton « Choisir une photo » relié au champ fichier', async () => {
    const fixture = await afficherAvecCategories();
    const zone = (fixture.nativeElement as HTMLElement).querySelector('.depot') as HTMLElement;

    expect(zone.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true');
    expect(zone.textContent).toContain('Glissez votre photo ici');
    const bouton = zone.querySelector('label.bouton-photo');
    expect(bouton?.textContent?.trim()).toBe('Choisir une photo');
    expect(bouton?.getAttribute('for')).toBe('photo');
  });

  it('champ fichier masqué à l\'écran seulement : libellé, focus clavier et aide conservés', async () => {
    const fixture = await afficherAvecCategories();
    const page: HTMLElement = fixture.nativeElement;
    const champ = page.querySelector('#photo') as HTMLInputElement;

    expect(champ.classList).toContain('visuellement-masque');
    expect(champ.hidden).toBe(false);
    expect(champ.tabIndex).not.toBe(-1);
    expect(page.querySelector('label[for="photo"]:not(.bouton-photo)')?.textContent).toContain('Photo de l’objet');
    expect(champ.getAttribute('aria-describedby')).toContain('aide-photo');
  });

  it('photo choisie : miniature, nom et taille dans la zone, bouton « Changer », nom absent de l\'aide', async () => {
    const fixture = await afficherAvecCategories();
    const page: HTMLElement = fixture.nativeElement;

    choisirPhoto(page, photo(150 * 1024, 'image/jpeg', 'dracula.jpg'));
    await fixture.whenStable();

    const miniature = page.querySelector('.depot-miniature') as HTMLImageElement;
    expect(miniature.getAttribute('src')).toBe('blob:apercu-1');
    expect(miniature.getAttribute('alt')).toBe('Aperçu de la photo choisie');
    expect(page.querySelector('.depot-fichier')?.textContent).toContain('dracula.jpg');
    expect(page.querySelector('.depot-fichier')?.textContent).toContain('150 Ko');
    expect(page.querySelector('label.bouton-photo')?.textContent?.trim()).toBe('Changer');
    expect(page.querySelector('#aide-photo')?.textContent).not.toContain('dracula.jpg');
    expect(page.querySelector('.depot [aria-live="polite"]')?.textContent).toContain('dracula.jpg');
  });

  it('changer de photo libère l\'aperçu précédent ; quitter l\'écran libère le dernier', async () => {
    const fixture = await afficherAvecCategories();
    const page: HTMLElement = fixture.nativeElement;

    choisirPhoto(page, photo(1024, 'image/jpeg', 'a.jpg'));
    choisirPhoto(page, photo(1024, 'image/png', 'b.png'));
    await fixture.whenStable();
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:apercu-1');
    expect((page.querySelector('.depot-miniature') as HTMLImageElement).getAttribute('src')).toBe('blob:apercu-2');

    fixture.destroy();
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:apercu-2');
  });

  it('pendant l\'envoi : petite roue dans le bouton, masquée aux lecteurs d\'écran', async () => {
    const fixture = await afficherAvecCategories();
    const page: HTMLElement = fixture.nativeElement;
    const bouton = () => page.querySelector('button[type="submit"]') as HTMLButtonElement;
    expect(bouton().querySelector('.roue')).toBeNull();
    await remplir(fixture);

    await publier(fixture);

    expect(bouton().querySelector('.roue')?.getAttribute('aria-hidden')).toBe('true');
    http.expectOne('/api/troc/annonces').flush('', { status: 502, statusText: 'Bad Gateway' });
    await fixture.whenStable();
    expect(bouton().querySelector('.roue')).toBeNull();
  });

  it('format refusé : pas de miniature, message d\'erreur seulement', async () => {
    const fixture = await afficherAvecCategories();
    const page: HTMLElement = fixture.nativeElement;

    choisirPhoto(page, photo(1024, 'image/gif', 'chauve-souris.gif'));
    await fixture.whenStable();

    expect(page.querySelector('.depot-miniature')).toBeNull();
    expect(URL.createObjectURL).not.toHaveBeenCalled();
  });

  it('annonce valide : POST multipart, bouton désactivé pendant l\'envoi, puis retour à la liste', async () => {
    const fixture = await afficherAvecCategories();
    const page: HTMLElement = fixture.nativeElement;
    const router = TestBed.inject(Router);
    const naviguer = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    await remplir(fixture);

    await publier(fixture);

    const requete = http.expectOne('/api/troc/annonces');
    expect(requete.request.method).toBe('POST');
    const corps = requete.request.body as FormData;
    expect(corps.get('titre')).toBe('Figurine Chucky 1988');
    expect(corps.get('description')).toBe('Bon état');
    expect(corps.get('categorie')).toBe('Figurines');
    expect(corps.get('type')).toBe('ECHANGE');
    expect((corps.get('photo') as File).name).toBe('chucky.jpg');
    const bouton = page.querySelector('button[type="submit"]') as HTMLButtonElement;
    expect(bouton.disabled).toBe(true);
    expect(bouton.textContent).toContain('Publication…');

    requete.flush({ id: 'a1' }, { status: 201, statusText: 'Created' });
    await fixture.whenStable();

    expect(naviguer).toHaveBeenCalledWith('/annonces');
    expect(TestBed.inject(AnnoncesService).prendrePublication()).toBe(true);
  });

  it('refus du serveur (400) : ses messages sous les champs concernés', async () => {
    const fixture = await afficherAvecCategories();
    const page: HTMLElement = fixture.nativeElement;
    await remplir(fixture);
    await publier(fixture);

    http.expectOne('/api/troc/annonces').flush(
      { status: 400, title: 'Annonce invalide', erreurs: { photo: 'Le fichier n’est pas une image JPEG, PNG ou WebP.' } },
      { status: 400, statusText: 'Bad Request' },
    );
    await fixture.whenStable();

    expect(erreur(page, 'photo')).toContain('n’est pas une image JPEG, PNG ou WebP');
    expect((page.querySelector('button[type="submit"]') as HTMLButtonElement).disabled).toBe(false);
  });

  it('erreur du serveur (5xx) : message global annoncé, saisie conservée', async () => {
    const fixture = await afficherAvecCategories();
    const page: HTMLElement = fixture.nativeElement;
    await remplir(fixture);
    await publier(fixture);

    http.expectOne('/api/troc/annonces').flush('', { status: 502, statusText: 'Bad Gateway' });
    await fixture.whenStable();

    expect(page.querySelector('[role="alert"]')?.textContent).toContain('L’annonce n’a pas été publiée');
    expect(champ<HTMLInputElement>(page, 'titre').value).toBe('Figurine Chucky 1988');
  });
});
