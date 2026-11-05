import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Annonce, AnnoncesService } from './annonces.service';
import { photoCarte, PhotoCarte } from './photo-cloudinary';

type EtatListe = { etat: 'chargement' } | { etat: 'chargee'; annonces: Annonce[] } | { etat: 'erreur' };

const DATE = new Intl.DateTimeFormat('fr-FR', { dateStyle: 'long', timeZone: 'Europe/Paris' });
const TYPES: Record<Annonce['type'], string> = { ECHANGE: 'Échange', DON: 'Don' };

/** Écran « Annonces » (US1a) : annonces publiées, la plus récente d'abord. */
@Component({
  selector: 'app-liste-annonces',
  imports: [RouterLink],
  templateUrl: './liste-annonces.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ListeAnnonces {
  private readonly api = inject(AnnoncesService);

  protected readonly etat = signal<EtatListe>({ etat: 'chargement' });
  protected readonly publiee = this.api.prendrePublication();

  constructor() {
    this.charger();
  }

  protected charger(): void {
    this.etat.set({ etat: 'chargement' });
    this.api.lister().subscribe({
      next: (annonces) => this.etat.set({ etat: 'chargee', annonces }),
      error: () => this.etat.set({ etat: 'erreur' }),
    });
  }

  /** Largeur affichée d'une carte selon la grille (1 colonne, puis 2, puis 3 dans 72 rem) : choix de la variante. */
  protected readonly tailles = '(min-width: 72rem) 22rem, (min-width: 40rem) 45vw, 100vw';

  protected photo(url: string): PhotoCarte {
    return photoCarte(url);
  }

  protected date(iso: string): string {
    return DATE.format(new Date(iso));
  }

  protected type(type: Annonce['type']): string {
    return TYPES[type] ?? type;
  }
}
