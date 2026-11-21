import { expect, test } from '@playwright/test';
import { photoJpeg, seConnecter, servirLesPhotosFactices, titreUnique, verifierAccessibilite } from './outils';

// Scénario E2E n° 1 (docs/cadrage.md §1.3.12, US1a) : connexion Keycloak (PKCE), publication d'une annonce avec sa
// photo, annonce visible dans la liste. axe-core sur le formulaire et la liste.
test('Publier une annonce avec sa photo, puis la voir dans la liste', async ({ page, context }, info) => {
  await servirLesPhotosFactices(context);
  const titre = titreUnique('Masque de Jason E2E', info);

  await seConnecter(page, 'e2e-auteur', '/annonces/nouvelle');
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  // Catégories chargées depuis troc-service (jamais codées en dur).
  await expect(page.getByLabel('Catégorie').locator('option', { hasText: 'Films' })).toHaveCount(1);
  await verifierAccessibilite(page, 'formulaire', info);

  await page.getByLabel('Titre').fill(titre);
  await page.getByLabel(/Description/).fill('Masque de hockey, bon état. Publié par le parcours E2E.');
  await page.getByLabel('Catégorie').selectOption({ label: 'Films' });
  await page.getByLabel('Don').check();
  await page.locator('#photo').setInputFiles(await photoJpeg(page));
  await page.getByRole('button', { name: 'Publier l’annonce' }).click();

  await page.waitForURL('**/annonces');
  await expect(page.getByText('Annonce publiée')).toBeVisible();
  const carte = page.locator('article.carte', { hasText: titre });
  await expect(carte).toBeVisible();
  await expect(carte.locator('img')).toHaveJSProperty('complete', true);
  await verifierAccessibilite(page, 'liste', info);
});
