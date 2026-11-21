import { request } from '@playwright/test';

// SQ-14 — attend que la pile réponde par la passerelle (services Java sans sonde Compose : images distroless sans
// shell). Attente bornée : 3 min au plus, puis échec explicite.
const ADRESSES = [
  'https://epouvante.menuly-app.com/version.json',
  'https://epouvante.menuly-app.com/api/troc/version',
  'https://epouvante.menuly-app.com/api/notifications/version',
  'https://auth.epouvante.menuly-app.com/realms/epouvante/.well-known/openid-configuration',
];

export default async function attendreLaPile(): Promise<void> {
  const client = await request.newContext({ ignoreHTTPSErrors: true });
  const limite = Date.now() + 180_000;
  try {
    for (const adresse of ADRESSES) {
      let statut = 0;
      while (Date.now() < limite) {
        statut = await client.get(adresse, { timeout: 5_000 }).then((r) => r.status(), () => 0);
        if (statut === 200) break;
        await new Promise((r) => setTimeout(r, 2_000));
      }
      if (statut !== 200) throw new Error(`Pile E2E indisponible : ${adresse} → ${statut || 'pas de réponse'}`);
      console.log(`prêt : ${adresse}`);
    }
  } finally {
    await client.dispose();
  }
}
