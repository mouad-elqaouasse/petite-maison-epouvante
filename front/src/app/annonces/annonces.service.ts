import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

/** Annonce telle que renvoyée par troc-service (US1a). */
export interface Annonce {
  id: string;
  titre: string;
  description: string | null;
  categorie: string;
  type: 'ECHANGE' | 'DON';
  statut: 'PUBLIEE' | 'RETIREE';
  auteurId: string;
  photoUrl: string;
  creeLe: string;
}

/** Champs refusés par le serveur (400) : nom du champ → message, affiché sous le champ. */
export type ErreursChamps = Partial<Record<'titre' | 'description' | 'categorie' | 'type' | 'photo', string>>;

/** API des annonces de troc-service ; le jeton est ajouté par l'intercepteur de keycloak-angular. */
@Injectable({ providedIn: 'root' })
export class AnnoncesService {
  private readonly http = inject(HttpClient);
  private publicationASignaler = false;

  /** Liste fermée des catégories, jamais codée en dur dans le front (US1a). */
  categories(): Observable<string[]> {
    return this.http.get<string[]>('/api/troc/categories');
  }

  lister(): Observable<Annonce[]> {
    return this.http.get<Annonce[]>('/api/troc/annonces');
  }

  publier(formulaire: FormData): Observable<Annonce> {
    return this.http.post<Annonce>('/api/troc/annonces', formulaire);
  }

  /** « Annonce publiée » à annoncer sur la liste, après la redirection. */
  signalerPublication(): void {
    this.publicationASignaler = true;
  }

  /** Lu une seule fois : le message ne réapparaît pas en revenant sur la liste. */
  prendrePublication(): boolean {
    const signalee = this.publicationASignaler;
    this.publicationASignaler = false;
    return signalee;
  }
}
