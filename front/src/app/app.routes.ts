import { Routes } from '@angular/router';
import { Accueil } from './accueil/accueil';
import { ListeAnnonces } from './annonces/liste-annonces';
import { PublierAnnonce } from './annonces/publier-annonce';
import { CentresInteret } from './centres-interet/centres-interet';
import { connexionGuard } from './compte/connexion.guard';

export const routes: Routes = [
  { path: '', component: Accueil, title: 'La Petite Maison de l’Épouvante' },
  // US1a : écrans réservés aux membres connectés.
  { path: 'annonces', component: ListeAnnonces, canActivate: [connexionGuard], title: 'Annonces — La Petite Maison de l’Épouvante' },
  {
    path: 'annonces/nouvelle',
    component: PublierAnnonce,
    canActivate: [connexionGuard],
    title: 'Publier une annonce — La Petite Maison de l’Épouvante',
  },
  // US2 : catégories suivies du membre connecté.
  {
    path: 'centres-interet',
    component: CentresInteret,
    canActivate: [connexionGuard],
    title: 'Mes centres d’intérêt — La Petite Maison de l’Épouvante',
  },
  { path: '**', redirectTo: '' },
];
