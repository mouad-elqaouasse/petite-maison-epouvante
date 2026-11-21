import AxeBuilder from '@axe-core/playwright';
import { BrowserContext, expect, Page, TestInfo } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';

// Comptes du realm de test E2E (e2e/preparer.py, D3) : mot de passe = nom d'utilisateur.
export type Compte = 'e2e-auteur' | 'e2e-abonne';

const CAPTURES = 'resultats/captures';

/** Photos factices de troc-service (D4) : servies ici, aucun appel au vrai Cloudinary. */
export async function servirLesPhotosFactices(contexte: BrowserContext): Promise<void> {
  // Image simple en SVG : le navigateur l'affiche, la carte de l'annonce a une vraie image.
  const image =
    '<svg xmlns="http://www.w3.org/2000/svg" width="600" height="450"><rect width="600" height="450" fill="#3b0b0d"/></svg>';
  await contexte.route('https://res.cloudinary.com/**', (route) =>
    route.fulfill({ status: 200, contentType: 'image/svg+xml', body: image }),
  );
}

/** Ouvre une page protégée : le guard renvoie vers Keycloak (PKCE), qui ramène sur la page demandée. */
export async function seConnecter(page: Page, compte: Compte, chemin: string): Promise<void> {
  await page.goto(chemin);
  await page.waitForURL(/auth\.epouvante\.menuly-app\.com/);
  await page.locator('#username').fill(compte);
  await page.locator('#password').fill(compte);
  await page.locator('#kc-login').click();
  await page.waitForURL(`**${chemin}`);
  await expect(page.locator('.entete-membre')).toContainText(compte);
}

/** Photo JPEG valide fabriquée par le navigateur (canvas) : aucun fichier binaire dans le dépôt. */
export async function photoJpeg(page: Page): Promise<{ name: string; mimeType: string; buffer: Buffer }> {
  const donnees = await page.evaluate(() => {
    const canvas = document.createElement('canvas');
    canvas.width = 640;
    canvas.height = 480;
    const ctx = canvas.getContext('2d')!;
    ctx.fillStyle = '#3b0b0d';
    ctx.fillRect(0, 0, 640, 480);
    ctx.fillStyle = '#f1e3c6';
    ctx.font = '64px sans-serif';
    ctx.fillText('E2E', 240, 260);
    return canvas.toDataURL('image/jpeg', 0.8).split(',')[1];
  });
  return { name: 'objet-e2e.jpg', mimeType: 'image/jpeg', buffer: Buffer.from(donnees, 'base64') };
}

/**
 * axe-core (RGAA, partiel) : 0 violation grave ou critique, bloquant. Capture et rapport gardés en artefact du
 * workflow, par taille d'écran.
 */
export async function verifierAccessibilite(page: Page, ecran: string, info: TestInfo): Promise<void> {
  await page.evaluate(() => document.fonts.ready);
  // Fin des animations d'entrée (cartes en fondu) : contrastes mesurés sur l'état final, pas en cours de fondu.
  await page.waitForFunction(() => document.getAnimations().every((a) => a.playState !== 'running'));
  const nom = `${info.project.name}-${ecran}`;
  mkdirSync(CAPTURES, { recursive: true });
  await page.screenshot({ path: `${CAPTURES}/${nom}.png`, fullPage: true });
  const resultat = await new AxeBuilder({ page }).analyze();
  writeFileSync(`${CAPTURES}/${nom}-axe.json`, JSON.stringify(resultat.violations, null, 2));
  const graves = resultat.violations
    .filter((v) => v.impact === 'serious' || v.impact === 'critical')
    .map((v) => `${v.id} (${v.impact}) : ${v.nodes.length} élément(s)`);
  expect(graves, `violations axe-core graves sur « ${ecran} »`).toEqual([]);
}

/** Titre unique par exécution et par taille d'écran : les parcours ne se gênent pas. */
export function titreUnique(base: string, info: TestInfo): string {
  return `${base} ${info.project.name === 'mobile-375' ? 'M' : 'O'}${Date.now().toString(36).slice(-5)}`;
}
