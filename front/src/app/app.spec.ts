import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { Component } from '@angular/core';
import { App } from './app';
import { EtatSession, SessionService } from './compte/session.service';

@Component({ template: '' })
class Vide {}

// En-tête commun (US1a, us1a-ui) : liens des annonces, nom du membre, déconnexion ; seulement pour un membre connecté.
describe('App (en-tête)', () => {
  const etat = signal<EtatSession>({ etat: 'connecte', nom: 'membre-demo' });
  const session = { etat: etat.asReadonly(), connecter: vi.fn(), deconnecter: vi.fn() };

  beforeEach(() => {
    etat.set({ etat: 'connecte', nom: 'membre-demo' });
    TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter([
          { path: 'annonces', component: Vide },
          { path: 'annonces/nouvelle', component: Vide },
          { path: 'centres-interet', component: Vide },
        ]),
        { provide: SessionService, useValue: session },
      ],
    });
  });

  async function afficher(url = '/annonces') {
    const fixture = TestBed.createComponent(App);
    await TestBed.inject(Router).navigateByUrl(url);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('affiche le nom du membre connecté et le bouton de déconnexion', async () => {
    const page = await afficher();

    expect(page.querySelector('.entete-membre')?.textContent).toContain('membre-demo');
    [...page.querySelectorAll('button')].find((b) => b.textContent?.includes('Se déconnecter'))?.click();
    expect(session.deconnecter).toHaveBeenCalled();
  });

  it('marque la page courante avec aria-current, et elle seule', async () => {
    const page = await afficher('/annonces/nouvelle');
    const courants = [...page.querySelectorAll('nav a[aria-current="page"]')].map((a) => a.textContent?.trim());

    expect(courants).toEqual(['Publier une annonce']);
  });

  // US2 : lien vers « Mes centres d'intérêt ».
  it('mène à « Mes centres d’intérêt », marqué comme page courante', async () => {
    const page = await afficher('/centres-interet');
    const lien = [...page.querySelectorAll('nav a')].find((a) => a.textContent?.trim() === 'Mes centres d’intérêt');

    expect(lien?.getAttribute('href')).toBe('/centres-interet');
    expect(lien?.getAttribute('aria-current')).toBe('page');
  });

  it('non connecté : pas d\'en-tête', async () => {
    etat.set({ etat: 'deconnecte' });
    const page = await afficher();

    expect(page.querySelector('header')).toBeNull();
  });
});
