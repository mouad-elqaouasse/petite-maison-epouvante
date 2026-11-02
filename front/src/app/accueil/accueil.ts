import { ChangeDetectionStrategy, Component } from '@angular/core';
import { Compte } from '../compte/compte';
import { Versions } from '../versions/versions';

/** Page d'accueil du squelette : versions en ligne (SQ-03) et compte connecté (SQ-06). */
@Component({
  selector: 'app-accueil',
  imports: [Versions, Compte],
  template: `
    <main class="page">
      <h1 class="maison">La Petite Maison de l’Épouvante</h1>
      <app-versions />
      <app-compte />
    </main>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Accueil {}
