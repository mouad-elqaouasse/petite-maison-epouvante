import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Notification, NotificationsService } from './notifications.service';

type EtatListe = { etat: 'chargement' } | { etat: 'chargee'; notifications: Notification[] } | { etat: 'erreur' };

const DATE = new Intl.DateTimeFormat('fr-FR', { dateStyle: 'long', timeStyle: 'short', timeZone: 'Europe/Paris' });
const TYPES: Record<Notification['type'], string> = { CONFIRMATION: 'Confirmation', ALERTE: 'Alerte' };

/**
 * Écran « Mes notifications » (US3), en lecture seule : confirmations de publication et alertes des catégories
 * suivies, dans l'ordre de l'API (plus récentes d'abord). Marquage « lue » et compteur : US4.
 */
@Component({
  selector: 'app-notifications',
  imports: [RouterLink],
  templateUrl: './notifications.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Notifications {
  private readonly api = inject(NotificationsService);

  protected readonly etat = signal<EtatListe>({ etat: 'chargement' });

  constructor() {
    this.charger();
  }

  protected charger(): void {
    this.etat.set({ etat: 'chargement' });
    this.api.lister().subscribe({
      next: (notifications) => this.etat.set({ etat: 'chargee', notifications }),
      error: () => this.etat.set({ etat: 'erreur' }),
    });
  }

  protected date(iso: string): string {
    return DATE.format(new Date(iso));
  }

  protected type(type: Notification['type']): string {
    return TYPES[type] ?? type;
  }
}
