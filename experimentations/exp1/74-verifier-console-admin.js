// Expérience 1, partie 4 (étape 7) — console d'administration Keycloak jointe par le tunnel.
// Relève les erreurs JavaScript et les requêtes en échec ; avec « login », se connecte et vérifie le realm de test.
//
// Prérequis sur le poste :
//   - tunnel ouvert : ssh -L 8180:localhost:8180 ubuntu@51.178.39.218 'kubectl -n sandbox port-forward svc/keycloak 8180:8080'
//   - playwright-core installé hors du dépôt (npm i playwright-core@1.64.0) et Google Chrome (channel « chrome »).
// Usage (bash, depuis le dossier où playwright-core est installé) :
//   node 74-verifier-console-admin.js                     ouverture de la console jusqu'à la page de connexion
//   KC_ADMIN_PW="$(ssh ubuntu@51.178.39.218 'kubectl -n sandbox get secret keycloak-secrets -o jsonpath={.data.KC_BOOTSTRAP_ADMIN_PASSWORD} | base64 -d')" \
//     node 74-verifier-console-admin.js login              connexion admin et vérification du realm epouvante-test
// Le mot de passe n'est lu que dans la variable KC_ADMIN_PW : jamais affiché, jamais écrit.
// Captures d'écran seulement si CAPTURES désigne un dossier (hors du dépôt).
const path = require('path');
const { chromium } = require('playwright-core');

const CONSOLE = 'http://localhost:8180/admin/';
const REALM = 'epouvante-test';
const CLIENT = 'epouvante-front';

const t0 = Date.now();
const ts = () => ((Date.now() - t0) / 1000).toFixed(1).padStart(5) + 's';
const log = l => console.log(`${ts()} ${l}`);
let ecarts = 0;
const verifier = (libelle, ok, detail) => { if (!ok) ecarts++; log(`[${ok ? 'OK' : 'ECART'}] ${libelle} : ${detail}`); };

let page;
const capture = async nom => { if (process.env.CAPTURES) await page.screenshot({ path: path.join(process.env.CAPTURES, `${nom}.png`) }); };

(async () => {
  const login = process.argv[2] === 'login';
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  page = await browser.newPage();
  const echecs = [];
  page.on('console', m => { if (['error', 'warning'].includes(m.type())) log(`[console.${m.type()}] ${m.text().slice(0, 300)}`); });
  page.on('pageerror', e => log(`[pageerror] ${String(e).slice(0, 300)}`));
  page.on('requestfailed', r => { echecs.push(r.url()); log(`[requestfailed] ${r.method()} ${r.url().split('?')[0]} ${r.failure()?.errorText}`); });
  page.on('response', r => { if (r.status() >= 400) { echecs.push(r.url()); log(`[HTTP ${r.status()}] ${r.request().method()} ${r.url().split('?')[0]}`); } });
  page.on('framenavigated', f => { if (f === page.mainFrame()) log(`[navigation] ${f.url().split('?')[0]}`); });

  // 1. Ouverture de la console : doit aboutir à la page de connexion du realm master, par le tunnel.
  await page.goto(CONSOLE, { waitUntil: 'domcontentloaded' });
  await page.locator('#kc-login').waitFor({ timeout: 20000 }).catch(() => {});
  const urlConnexion = page.url().split('?')[0];
  verifier('page de connexion par le tunnel', urlConnexion.startsWith('http://localhost:8180/realms/master/') && await page.locator('#kc-login').isVisible(), urlConnexion);
  verifier('aucune requête en échec', echecs.length === 0, echecs.length ? echecs.map(u => u.split('?')[0]).join(', ') : 'aucune');
  await capture('connexion');

  if (login) {
    const motDePasse = process.env.KC_ADMIN_PW;
    if (!motDePasse) throw new Error('variable KC_ADMIN_PW absente');
    await page.fill('#username', 'admin');
    await page.fill('#password', motDePasse);
    await Promise.all([page.waitForURL(/\/admin\/master\/console\//, { timeout: 20000 }), page.click('#kc-login')]);
    await page.getByRole('heading').first().waitFor({ timeout: 20000 });
    verifier('connexion admin', page.url().startsWith('http://localhost:8180/admin/master/console/'), page.url().split('?')[0]);

    // Changer seulement l'ancre ne recharge pas le realm courant : rechargement nécessaire.
    await page.goto(`${CONSOLE}master/console/#/${REALM}/clients`, { waitUntil: 'domcontentloaded' });
    await page.reload({ waitUntil: 'domcontentloaded' });
    const lienClient = page.getByText(CLIENT, { exact: true }).first();
    await lienClient.waitFor({ timeout: 20000 });
    verifier(`client ${CLIENT} visible dans le realm ${REALM}`, await lienClient.isVisible(), 'liste des clients');
    await capture('clients');

    // Keycloak 26.8 : réglage PKCE dans l'onglet Settings (section Capability config).
    await lienClient.click();
    await page.getByRole('tab', { name: 'Settings', exact: true }).click({ timeout: 20000 });
    await page.getByText('PKCE Method').first().waitFor({ timeout: 20000 });
    const corps = (await page.locator('body').innerText()).replace(/\s+/g, ' ');
    const i = corps.indexOf('Require PKCE');
    const extrait = i < 0 ? '(introuvable)' : corps.slice(i, i + 40);
    verifier('PKCE obligatoire, méthode S256', /Require PKCE On PKCE Method S256/.test(corps), extrait);
    await capture('pkce');
  }

  await browser.close();
  log(ecarts ? `${ecarts} écart(s)` : 'tous les contrôles sont OK');
  process.exit(ecarts ? 1 : 0);
})().catch(async e => {
  console.error('ERREUR :', e.message.split('\n')[0]);
  try { await capture('erreur'); } catch {}
  process.exit(1);
});
