import { Routes } from '@angular/router';
import { Versions } from './versions/versions';

export const routes: Routes = [
  { path: '', component: Versions, title: "Versions — La Petite Maison de l’Épouvante" },
  { path: '**', redirectTo: '' },
];
