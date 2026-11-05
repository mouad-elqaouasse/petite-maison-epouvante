import { photoCarte } from './photo-cloudinary';

// Photos des cartes : transformations Cloudinary dans l'URL (recadrage 4:3, format et qualité automatiques) et srcset.
describe('photoCarte', () => {
  const ORIGINE = 'https://res.cloudinary.com/demo/image/upload/v1793884444/hqwnbvxr5ub37iadfu6c.jpg';
  const transformee = (largeur: number) =>
    `https://res.cloudinary.com/demo/image/upload/c_fill,ar_4:3,w_${largeur},f_auto,q_auto/v1793884444/hqwnbvxr5ub37iadfu6c.jpg`;

  it('src : recadrée en 4:3, 600 px de large, format et qualité automatiques', () => {
    expect(photoCarte(ORIGINE).src).toBe(transformee(600));
  });

  it('srcset : 400, 600, 800 et 1200 px de large', () => {
    expect(photoCarte(ORIGINE).srcset).toBe(
      [400, 600, 800, 1200].map((l) => `${transformee(l)} ${l}w`).join(', '),
    );
  });

  it('URL qui ne vient pas de Cloudinary (profil photo-factice) : laissée telle quelle, sans srcset', () => {
    expect(photoCarte('https://photos.invalid/factice/abc')).toEqual({
      src: 'https://photos.invalid/factice/abc',
      srcset: null,
    });
  });

  it('URL Cloudinary sans le segment /image/upload/ attendu : laissée telle quelle', () => {
    const autre = 'https://res.cloudinary.com/demo/video/upload/v1/clip.mp4';
    expect(photoCarte(autre)).toEqual({ src: autre, srcset: null });
  });

  it('http (non chiffré) ou autre hôte se terminant par cloudinary.com : laissée telle quelle', () => {
    for (const url of [
      'http://res.cloudinary.com/demo/image/upload/v1/a.jpg',
      'https://res.cloudinary.com.example.org/demo/image/upload/v1/a.jpg',
    ]) {
      expect(photoCarte(url).srcset).toBeNull();
    }
  });
});
