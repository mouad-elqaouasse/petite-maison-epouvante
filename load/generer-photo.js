// Photo de test des campagnes de charge (docs/cadrage.md §1.4.17 : photo d'environ 200 Ko).
// PNG de 262 × 260 pixels au bruit pseudo-aléatoire (graine fixe) : incompressible, donc environ 200 Ko, et identique
// à chaque génération. Aucune donnée personnelle, aucune métadonnée. Usage : node load/generer-photo.js
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');

const LARGEUR = 262;
const HAUTEUR = 260;

// Générateur congruentiel (Numerical Recipes) : reproductible, sans dépendance.
let graine = 20261124;
const octetSuivant = () => {
  graine = (Math.imul(graine, 1664525) + 1013904223) >>> 0;
  return graine >>> 24;
};

const lignes = Buffer.alloc(HAUTEUR * (1 + LARGEUR * 3));
for (let y = 0, i = 0; y < HAUTEUR; y++) {
  lignes[i++] = 0; // filtre PNG « aucun »
  for (let x = 0; x < LARGEUR * 3; x++) lignes[i++] = octetSuivant();
}

const crcTable = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});
const crc32 = (buf) => {
  let c = 0xffffffff;
  for (const b of buf) c = crcTable[(c ^ b) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
};
const bloc = (type, donnees) => {
  const longueur = Buffer.alloc(4);
  longueur.writeUInt32BE(donnees.length);
  const typeEtDonnees = Buffer.concat([Buffer.from(type, 'ascii'), donnees]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(typeEtDonnees));
  return Buffer.concat([longueur, typeEtDonnees, crc]);
};

const entete = Buffer.alloc(13);
entete.writeUInt32BE(LARGEUR, 0);
entete.writeUInt32BE(HAUTEUR, 4);
entete[8] = 8; // 8 bits par canal
entete[9] = 2; // RVB
const png = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  bloc('IHDR', entete),
  bloc('IDAT', zlib.deflateSync(lignes, { level: 9 })),
  bloc('IEND', Buffer.alloc(0)),
]);

const sortie = path.join(__dirname, 'photo-charge.png');
fs.writeFileSync(sortie, png);
console.log(`${sortie} : ${png.length} octets`);
