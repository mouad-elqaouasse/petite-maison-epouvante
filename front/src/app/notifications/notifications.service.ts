import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

/** Notification du membre connecté (US3), telle que renvoyée par GET /api/notifications. */
export interface Notification {
  type: 'CONFIRMATION' | 'ALERTE';
  texte: string;
  annonceId: string;
  titreAnnonce: string;
  creeLe: string;
}

/**
 * Notifications du membre connecté, détenues par notification-service (US3), plus récentes d'abord. Le membre est
 * celui du jeton, ajouté par l'intercepteur de keycloak-angular.
 */
@Injectable({ providedIn: 'root' })
export class NotificationsService {
  private readonly http = inject(HttpClient);

  lister(): Observable<Notification[]> {
    return this.http.get<Notification[]>('/api/notifications');
  }
}
