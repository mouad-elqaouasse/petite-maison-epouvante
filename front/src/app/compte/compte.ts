import { ChangeDetectionStrategy, Component, computed, inject, Signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { Observable, of, switchMap } from 'rxjs';
import { EtatMoi, MoiService } from './moi.service';
import { SessionService } from './session.service';

/** Section « Mon compte » (SQ-06) : connexion, puis nom et rôles tels que vus par chaque service. */
@Component({
  selector: 'app-compte',
  templateUrl: './compte.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Compte {
  protected readonly session = inject(SessionService);
  private readonly moi = inject(MoiService);
  private readonly connecte = toObservable(computed(() => this.session.etat().etat === 'connecte'));

  protected readonly services = [
    { id: 'troc-service', nom: 'service des trocs', etat: this.lire('/api/troc/moi') },
    { id: 'notification-service', nom: 'service des notifications', etat: this.lire('/api/notifications/moi') },
  ];

  /** Vrai tant qu'une réponse manque : la liste reste aria-busy, puis est annoncée en une fois. */
  protected readonly enLecture = computed(() => this.services.some((s) => s.etat().etat === 'lecture'));

  /** Les /moi ne sont appelés qu'une fois la session ouverte. */
  private lire(url: string): Signal<EtatMoi> {
    return toSignal(
      this.connecte.pipe(
        switchMap((connecte): Observable<EtatMoi> => (connecte ? this.moi.lire(url) : of({ etat: 'lecture' }))),
      ),
      { initialValue: { etat: 'lecture' } as EtatMoi },
    );
  }
}
