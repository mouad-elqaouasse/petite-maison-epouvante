import { ChangeDetectionStrategy, Component, inject, signal, WritableSignal } from '@angular/core';
import { forkJoin } from 'rxjs';
import { AnnoncesService } from '../annonces/annonces.service';
import { AbonnementsService } from './abonnements.service';

type EtatPage = { etat: 'chargement' } | { etat: 'erreur' } | { etat: 'pret'; categories: string[] };
type Message = { texte: string; erreur: boolean };

/**
 * Écran « Mes centres d'intérêt » (US2). Liste des catégories lue dans troc-service (jamais codée en dur), catégories
 * suivies dans notification-service (décision D4). Chaque case enregistre aussitôt : cocher = PUT, décocher = DELETE.
 */
@Component({
  selector: 'app-centres-interet',
  templateUrl: './centres-interet.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CentresInteret {
  private readonly annonces = inject(AnnoncesService);
  private readonly abonnements = inject(AbonnementsService);

  protected readonly page = signal<EtatPage>({ etat: 'chargement' });
  protected readonly suivies = signal<ReadonlySet<string>>(new Set());
  protected readonly enCours = signal<ReadonlySet<string>>(new Set());
  protected readonly message = signal<Message | null>(null);

  constructor() {
    this.charger();
  }

  protected charger(): void {
    this.page.set({ etat: 'chargement' });
    forkJoin({ categories: this.annonces.categories(), suivies: this.abonnements.lister() }).subscribe({
      next: ({ categories, suivies }) => {
        this.suivies.set(new Set(suivies));
        this.page.set({ etat: 'pret', categories });
      },
      error: () => this.page.set({ etat: 'erreur' }),
    });
  }

  /**
   * Pendant l'appel, la case n'est pas désactivée (elle perdrait le focus clavier) : elle est marquée aria-busy et un
   * second clic est ignoré.
   */
  protected cliquer(evenement: Event, categorie: string): void {
    if (this.enCours().has(categorie)) {
      evenement.preventDefault();
      return;
    }
    this.basculer(categorie);
  }

  /** La case change tout de suite (elle reflète le clic) ; en cas d'échec, elle revient à son état précédent. */
  private basculer(categorie: string): void {
    const ajout = !this.suivies().has(categorie);
    this.message.set(null);
    this.modifier(this.suivies, categorie, ajout);
    this.modifier(this.enCours, categorie, true);
    const appel = ajout ? this.abonnements.abonner(categorie) : this.abonnements.desabonner(categorie);
    appel.subscribe({
      next: () => {
        this.modifier(this.enCours, categorie, false);
        this.message.set({ texte: `Abonnement à ${categorie} ${ajout ? 'ajouté' : 'retiré'}.`, erreur: false });
      },
      error: () => {
        this.modifier(this.enCours, categorie, false);
        this.modifier(this.suivies, categorie, !ajout);
        this.message.set({ texte: `L’abonnement à ${categorie} n’a pas pu être modifié. Réessayez.`, erreur: true });
      },
    });
  }

  private modifier(ensemble: WritableSignal<ReadonlySet<string>>, categorie: string, present: boolean): void {
    const copie = new Set(ensemble());
    if (present) {
      copie.add(categorie);
    } else {
      copie.delete(categorie);
    }
    ensemble.set(copie);
  }
}
