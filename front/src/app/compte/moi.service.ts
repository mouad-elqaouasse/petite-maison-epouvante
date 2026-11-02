import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { catchError, map, Observable, of, startWith } from 'rxjs';

/** Rôles vus par un service : en cours de lecture, lus, ou indisponibles (avec la raison affichée). */
export type EtatMoi =
  | { etat: 'lecture' }
  | { etat: 'lu'; roles: string[] }
  | { etat: 'indisponible'; raison: string };

/** Lit GET /api/<service>/moi (ADR 0011) ; le jeton est ajouté par l'intercepteur de keycloak-angular. */
@Injectable({ providedIn: 'root' })
export class MoiService {
  private readonly http = inject(HttpClient);

  lire(url: string): Observable<EtatMoi> {
    return this.http.get<{ roles?: unknown }>(url).pipe(
      map((reponse): EtatMoi =>
        Array.isArray(reponse?.roles)
          ? { etat: 'lu', roles: reponse.roles.filter((r): r is string => typeof r === 'string') }
          : { etat: 'indisponible', raison: 'réponse sans rôles' },
      ),
      catchError((erreur: HttpErrorResponse) =>
        of<EtatMoi>({
          etat: 'indisponible',
          raison: erreur.status === 0 ? 'service injoignable' : `erreur HTTP ${erreur.status}`,
        }),
      ),
      startWith<EtatMoi>({ etat: 'lecture' }),
    );
  }
}
