import { Routes } from '@angular/router';
import { Accueil } from './accueil/accueil';

export const routes: Routes = [
  { path: '', component: Accueil, title: 'La Petite Maison de l’Épouvante' },
  { path: '**', redirectTo: '' },
];
