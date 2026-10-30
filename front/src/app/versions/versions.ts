import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { VersionsService } from './versions.service';

/** Page d'accueil du squelette (SQ-03) : SHA des trois composants livrés. */
@Component({
  selector: 'app-versions',
  templateUrl: './versions.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Versions {
  private readonly versions = inject(VersionsService);

  protected readonly composants = [
    { id: 'front', nom: 'Front', etat: this.lire('/version.json') },
    { id: 'troc-service', nom: 'Service des trocs', etat: this.lire('/api/troc/version') },
    { id: 'notification-service', nom: 'Service des notifications', etat: this.lire('/api/notifications/version') },
  ];

  /** Vrai tant qu'une réponse manque : la liste reste aria-busy, puis est annoncée en une fois. */
  protected readonly enLecture = computed(() => this.composants.some((c) => c.etat().etat === 'lecture'));

  private lire(url: string) {
    return toSignal(this.versions.lire(url), { requireSync: true });
  }

  protected court(sha: string): string {
    return sha.slice(0, 7);
  }
}
