import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { SessionService } from './compte/session.service';

/** En-tête commun (US1a) : liens vers les écrans des annonces et déconnexion, pour un membre connecté. */
@Component({
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  selector: 'app-root',
  template: `
    @if (session.etat().etat === 'connecte') {
      <header class="entete">
        <nav class="entete-nav" aria-label="Navigation principale">
          <a routerLink="/" class="entete-maison">La Petite Maison</a>
          <ul>
            <li>
              <a routerLink="/annonces" routerLinkActive="actif" ariaCurrentWhenActive="page" [routerLinkActiveOptions]="{ exact: true }">Annonces</a>
            </li>
            <li>
              <a routerLink="/annonces/nouvelle" routerLinkActive="actif" ariaCurrentWhenActive="page">Publier une annonce</a>
            </li>
          </ul>
          <button type="button" class="entete-deconnexion" (click)="session.deconnecter()">Se déconnecter</button>
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
