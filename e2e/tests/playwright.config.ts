import { defineConfig } from '@playwright/test';

// SQ-14 — parcours E2E sur la pile e2e/compose.yaml, aux adresses de la prod (D2), à 375 et 1280 px.
// Un seul worker : les deux parcours partagent les comptes de test et l'ordre des notifications.
export default defineConfig({
  testDir: './parcours',
  globalSetup: './attendre-la-pile.ts',
  outputDir: './resultats/traces',
  timeout: 120_000,
  expect: { timeout: 15_000 },
  workers: 1,
  retries: 0,
  forbidOnly: !!process.env.CI,
  reporter: [['list'], ['html', { outputFolder: './resultats/rapport', open: 'never' }]],
  use: {
    baseURL: 'https://epouvante.menuly-app.com',
    // Certificat auto-signé de la passerelle E2E.
    ignoreHTTPSErrors: true,
    locale: 'fr-FR',
    timezoneId: 'Europe/Paris',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'mobile-375', use: { viewport: { width: 375, height: 812 } } },
    { name: 'ordinateur-1280', use: { viewport: { width: 1280, height: 900 } } },
  ],
});
