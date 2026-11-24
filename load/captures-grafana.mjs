// Captures Grafana d'une campagne de charge (« Santé », « Montée en charge » et les panneaux pour le jury), sur une
// période donnée. Lancé par load/captures-grafana.sh, qui ouvre le tunnel.
// Aucun mot de passe : lecture anonyme en Viewer, Grafana n'étant joignable que par le tunnel (ADR 0028).
// Usage : node load/captures-grafana.mjs <campagne> <début ISO> <fin ISO> [port]
import { createRequire } from 'node:module';
import { existsSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const racine = join(dirname(fileURLToPath(import.meta.url)), '..');
// Playwright déjà installé pour l'E2E (e2e/tests) : pas de nouvelle dépendance.
const { chromium } = createRequire(join(racine, 'e2e', 'tests', 'package.json'))('playwright');

const [campagne, debut, fin, port = '13000'] = process.argv.slice(2);
if (!/^[a-z0-9-]+$/.test(campagne ?? '') || !debut || !fin) {
  console.error('usage : node load/captures-grafana.mjs <campagne> <début ISO> <fin ISO> [port]');
  process.exit(2);
}
const de = Date.parse(debut);
const a = Date.parse(fin);
if (Number.isNaN(de) || Number.isNaN(a) || de >= a) {
  console.error('ERREUR : période invalide');
  process.exit(2);
}

const origine = `http://localhost:${port}`;
const periode = `orgId=1&from=${de}&to=${a}&timezone=browser&theme=light`;
const dossier = join(racine, 'docs', 'soutenance', 'captures', 'charge', campagne);
mkdirSync(dossier, { recursive: true });

// Tableaux entiers, puis les 5 panneaux pour le jury (docs/soutenance/guide-grafana.md) et la RAM du nœud.
const tableaux = [
  { fichier: '00-sante.png', uid: 'sante' },
  { fichier: '01-montee-en-charge.png', uid: 'montee-en-charge' },
];
const panneaux = [
  { fichier: '10-requetes-par-seconde.png', uid: 'montee-en-charge', id: 4 },
  { fichier: '11-taux-5xx.png', uid: 'sante', id: 2 },
  { fichier: '12-cpu-par-pod.png', uid: 'montee-en-charge', id: 3 },
  { fichier: '13-replicas-voulus-et-prets.png', uid: 'montee-en-charge', id: 2 },
  { fichier: '14-messages-prets-par-file.png', uid: 'montee-en-charge', id: 6 },
  { fichier: '15-ram-disponible-noeud.png', uid: 'montee-en-charge', id: 7 },
];

// Chromium de Playwright s'il est installé, sinon Microsoft Edge (présent sous Windows) : aucun téléchargement.
const navigateur = existsSync(chromium.executablePath())
  ? await chromium.launch()
  : await chromium.launch({ channel: 'msedge' });
try {
  const page = await navigateur.newPage({
    viewport: { width: 1600, height: 1000 },
    locale: 'fr-FR',
    timezoneId: 'Europe/Paris',
  });

  const attendreRendu = async () => {
    await page.waitForLoadState('networkidle', { timeout: 30_000 });
    // Les panneaux finissent de dessiner après la dernière requête de données.
    await page.waitForTimeout(1500);
    if (page.url().includes('/login')) {
      throw new Error('Grafana demande une connexion : lecture anonyme inactive (ADR 0028)');
    }
  };

  for (const { fichier, uid } of tableaux) {
    await page.setViewportSize({ width: 1600, height: 1000 });
    await page.goto(`${origine}/d/${uid}?${periode}&kiosk`);
    await attendreRendu();
    await page.screenshot({ path: join(dossier, fichier), fullPage: true });
    console.log(`capture : ${fichier}`);
  }
  for (const { fichier, uid, id } of panneaux) {
    await page.setViewportSize({ width: 1200, height: 520 });
    await page.goto(`${origine}/d-solo/${uid}?${periode}&panelId=${id}`);
    await attendreRendu();
    await page.screenshot({ path: join(dossier, fichier) });
    console.log(`capture : ${fichier}`);
  }
  console.log(`dossier : docs/soutenance/captures/charge/${campagne} (période ${debut} → ${fin})`);
} finally {
  await navigateur.close();
}
