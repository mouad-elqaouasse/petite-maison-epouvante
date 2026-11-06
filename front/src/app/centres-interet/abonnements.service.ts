import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

const URL_ABONNEMENTS = '/api/notifications/abonnements';

/**
 * Catégories suivies par le membre connecté (US2), détenues par notification-service. Le membre est celui du jeton,
 * ajouté par l'intercepteur de keycloak-angular ; le libellé est encodé dans le chemin (« Jeux de société »).
 */
@Injectable({ providedIn: 'root' })
export class AbonnementsService {
  private readonly http = inject(HttpClient);

  lister(): Observable<string[]> {
    return this.http.get<string[]>(URL_ABONNEMENTS);
  }

  abonner(categorie: string): Observable<void> {
    return this.http.put<void>(`${URL_ABONNEMENTS}/${encodeURIComponent(categorie)}`, null);
  }

  desabonner(categorie: string): Observable<void> {
    return this.http.delete<void>(`${URL_ABONNEMENTS}/${encodeURIComponent(categorie)}`);
  }
}
