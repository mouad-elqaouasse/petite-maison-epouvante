import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { catchError, map, Observable, of, startWith } from 'rxjs';

/** État d'une version : en cours de lecture, lue (SHA du commit) ou indisponible (avec la raison affichée). */
export type EtatVersion =
  | { etat: 'lecture' }
  | { etat: 'lue'; sha: string }
  | { etat: 'indisponible'; raison: string };

/** Lit le SHA renvoyé par /api/<service>/version ou /version.json (même origine, ADR 0006). */
@Injectable({ providedIn: 'root' })
export class VersionsService {
  private readonly http = inject(HttpClient);

  lire(url: string): Observable<EtatVersion> {
    return this.http.get<{ version?: unknown }>(url).pipe(
      map((reponse): EtatVersion =>
        typeof reponse?.version === 'string' && reponse.version !== ''
          ? { etat: 'lue', sha: reponse.version }
          : { etat: 'indisponible', raison: 'réponse sans version' },
      ),
      catchError((erreur: HttpErrorResponse) => of(indisponible(erreur))),
      startWith<EtatVersion>({ etat: 'lecture' }),
    );
  }
}

function indisponible(erreur: HttpErrorResponse): EtatVersion {
  if (erreur.status === 0) {
    return { etat: 'indisponible', raison: 'service injoignable' };
  }
  if (erreur.status === 200) {
    return { etat: 'indisponible', raison: 'réponse illisible' };
  }
  return { etat: 'indisponible', raison: `erreur HTTP ${erreur.status}` };
}
