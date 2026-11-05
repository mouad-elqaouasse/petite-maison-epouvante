import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { SessionService } from './compte/session.service';

/**
 * En-tête commun (US1a) : liens vers les écrans des annonces, membre connecté et déconnexion. Reste visible en haut
 * de la page au défilement. Affiché seulement pour un membre connecté.
 */
@Component({
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  selector: 'app-root',
  template: `
    @let etat = session.etat();
    @if (etat.etat === 'connecte') {
      <header class="entete">
        <nav class="entete-nav" aria-label="Navigation principale">
          <a routerLink="/" class="entete-maison">La Petite Maison</a>
          <ul class="entete-liens">
            <li>
              <a routerLink="/annonces" routerLinkActive="actif" ariaCurrentWhenActive="page" [routerLinkActiveOptions]="{ exact: true }">Annonces</a>
            </li>
            <li>
              <a routerLink="/annonces/nouvelle" routerLinkActive="actif" ariaCurrentWhenActive="page">Publier une annonce</a>
            </li>
          </ul>
          <div class="entete-compte">
            <span class="entete-membre"><span class="visuellement-masque">Connecté en tant que </span>{{ etat.nom }}</span>
            <button type="button" class="entete-deconnexion" (click)="session.deconnecter()">Se déconnecter</button>
          </div>
        </nav>
      </header>
    }
    <router-outlet />
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
  protected readonly session = inject(SessionService);
}
