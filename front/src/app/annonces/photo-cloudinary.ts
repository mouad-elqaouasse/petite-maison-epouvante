/** Photo d'une carte : URL principale et variantes par largeur (null si l'URL ne vient pas de Cloudinary). */
export interface PhotoCarte {
  src: string;
  srcset: string | null;
}

/** Largeurs servies aux cartes ; 600 px pour l'attribut src (docs/backlog/US1a, écran « Annonces »). */
const LARGEURS = [400, 600, 800, 1200];
const LARGEUR_SRC = 600;
const SEGMENT = '/image/upload/';

/**
 * Transformations Cloudinary dans l'URL : recadrage 4:3 qui remplit le cadre (c_fill, ar_4:3), largeur fixée,
 * format (WebP, AVIF…) et qualité choisis par Cloudinary selon le navigateur (f_auto, q_auto). Toutes les cartes ont
 * ainsi des images de même proportion, plus légères que l'original.
 * Seules les URL https de res.cloudinary.com sont transformées ; les autres (profil photo-factice) restent intactes.
 */
export function photoCarte(url: string): PhotoCarte {
  const position = url.indexOf(SEGMENT);
  let hote = '';
  try {
    const analyse = new URL(url);
    hote = analyse.protocol === 'https:' ? analyse.hostname : '';
  } catch {
    hote = '';
  }
  if (hote !== 'res.cloudinary.com' || position < 0) {
    return { src: url, srcset: null };
  }
  const debut = url.slice(0, position + SEGMENT.length);
  const fin = url.slice(position + SEGMENT.length);
  const variante = (largeur: number) => `${debut}c_fill,ar_4:3,w_${largeur},f_auto,q_auto/${fin}`;
  return {
    src: variante(LARGEUR_SRC),
    srcset: LARGEURS.map((largeur) => `${variante(largeur)} ${largeur}w`).join(', '),
  };
}
