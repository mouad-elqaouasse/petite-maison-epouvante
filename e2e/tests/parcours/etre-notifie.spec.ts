import { expect, Page, test } from '@playwright/test';
import { photoJpeg, seConnecter, servirLesPhotosFactices, titreUnique, verifierAccessibilite } from './outils';

// Scénario E2E n° 2 (docs/cadrage.md §1.3.12, US2 et US3, D5) : un membre abonné à une catégorie voit la notification
// d'une annonce publiée par un autre membre ; l'auteur voit sa confirmation. Deux navigateurs, deux comptes.

/** « Actualiser » jusqu'à l'apparition de la notification : 30 s au plus (traitement asynchrone, RabbitMQ). */
async function attendreLaNotification(page: Page, titre: string, type: 'Alerte' | 'Confirmation'): Promise<void> {
  const notification = page.locator('.notification', { hasText: titre });
  await expect(async () => {
    await page.getByRole('button', { name: 'Actualiser' }).click();
    await expect(notification).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 30_000, intervals: [1_000, 2_000] });
  await expect(notification.locator('.notification-type')).toHaveText(type);
}

test('Un membre abonné voit la notification d’une nouvelle annonce', async ({ browser }, info) => {
  const titre = titreUnique('Jeu Ouija 1972 E2E', info);

  // L'abonné choisit la catégorie dans « Mes centres d'intérêt ».
  const abonne = await browser.newContext();
  const pageAbonne = await abonne.newPage();
  await seConnecter(pageAbonne, 'e2e-abonne', '/centres-interet');
  const caseJeux = pageAbonne.getByLabel('Jeux de société');
  if (!(await caseJeux.isChecked())) {
    await caseJeux.check();
    await expect(pageAbonne.locator('[aria-live]').last()).toHaveText('Abonnement à Jeux de société ajouté.');
  }
  await verifierAccessibilite(pageAbonne, 'centres-interet', info);

  // L'auteur publie une annonce dans cette catégorie.
  const auteur = await browser.newContext();
  await servirLesPhotosFactices(auteur);
  const pageAuteur = await auteur.newPage();
  await seConnecter(pageAuteur, 'e2e-auteur', '/annonces/nouvelle');
  await pageAuteur.getByLabel('Titre').fill(titre);
  await pageAuteur.getByLabel('Catégorie').selectOption({ label: 'Jeux de société' });
  await pageAuteur.getByLabel('Échange').check();
  await pageAuteur.locator('#photo').setInputFiles(await photoJpeg(pageAuteur));
  await pageAuteur.getByRole('button', { name: 'Publier l’annonce' }).click();
  await pageAuteur.waitForURL('**/annonces');

  // L'abonné voit l'alerte ; l'auteur, sa confirmation.
  await pageAbonne.getByRole('link', { name: 'Mes notifications' }).click();
  await attendreLaNotification(pageAbonne, titre, 'Alerte');
  await verifierAccessibilite(pageAbonne, 'notifications', info);

  await pageAuteur.getByRole('link', { name: 'Mes notifications' }).click();
  await attendreLaNotification(pageAuteur, titre, 'Confirmation');

  await abonne.close();
  await auteur.close();
});
