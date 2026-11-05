import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, ElementRef, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { AnnoncesService, ErreursChamps } from './annonces.service';

type EtatCategories = { etat: 'chargement' } | { etat: 'chargees'; categories: string[] } | { etat: 'erreur' };
type Champ = keyof ErreursChamps;

/** Mêmes règles que le serveur, qui fait foi (docs/cadrage.md §1.1 points 2 et 3) : ici pour le confort seulement. */
const PHOTO_MAX = 2 * 1024 * 1024;
const FORMATS = ['image/jpeg', 'image/png', 'image/webp'];
const MESSAGES = {
  titre: 'Le titre doit faire entre 3 et 80 caractères.',
  description: 'La description ne doit pas dépasser 1000 caractères.',
  categorie: 'Choisissez une catégorie.',
  type: 'Choisissez le type : échange ou don.',
  photoAbsente: 'Ajoutez une photo de l’objet.',
  photoTropLourde: 'La photo dépasse 2 Mo : choisissez un fichier plus léger.',
  photoFormat: 'La photo doit être au format JPEG, PNG ou WebP.',
};

/** Écran « Publier une annonce » (US1a). */
@Component({
  selector: 'app-publier-annonce',
  imports: [ReactiveFormsModule],
  templateUrl: './publier-annonce.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PublierAnnonce {
  private readonly api = inject(AnnoncesService);
  private readonly router = inject(Router);
  private readonly hote = inject<ElementRef<HTMLElement>>(ElementRef);

  protected readonly descriptionMax = 1000;
  protected readonly formulaire = inject(NonNullableFormBuilder).group({
    titre: ['', [Validators.required, Validators.minLength(3), Validators.maxLength(80)]],
    description: ['', Validators.maxLength(this.descriptionMax)],
    categorie: ['', Validators.required],
    type: ['', Validators.required],
  });

  protected readonly categories = signal<EtatCategories>({ etat: 'chargement' });
  protected readonly photo = signal<File | null>(null);
  protected readonly soumis = signal(false);
  protected readonly envoi = signal(false);
  protected readonly erreursServeur = signal<ErreursChamps>({});
  protected readonly erreurGlobale = signal<string | null>(null);

  constructor() {
    this.chargerCategories();
  }

  protected chargerCategories(): void {
    this.categories.set({ etat: 'chargement' });
    this.api.categories().subscribe({
      next: (categories) => this.categories.set({ etat: 'chargees', categories }),
      error: () => this.categories.set({ etat: 'erreur' }),
    });
  }

  protected choisirPhoto(evenement: Event): void {
    const fichier = (evenement.target as HTMLInputElement).files?.[0] ?? null;
    this.photo.set(fichier);
    this.erreursServeur.update(({ photo: _photo, ...autres }) => autres);
  }

  /** Message affiché sous un champ : contrôle côté client d'abord, sinon refus du serveur. */
  protected erreur(champ: Champ): string | null {
    return this.erreurClient(champ) ?? this.erreursServeur()[champ] ?? null;
  }

  protected tailleLisible(octets: number): string {
    return octets < 1024 * 1024 ? `${Math.ceil(octets / 1024)} Ko` : `${(octets / 1024 / 1024).toFixed(1)} Mo`;
  }

  protected publier(): void {
    this.soumis.set(true);
    this.erreursServeur.set({});
    this.erreurGlobale.set(null);
    const enErreur = (['titre', 'description', 'categorie', 'type', 'photo'] as Champ[]).find((c) => this.erreurClient(c));
    if (enErreur) {
      this.placerFocus(enErreur);
      return;
    }

    const valeurs = this.formulaire.getRawValue();
    const corps = new FormData();
    corps.append('titre', valeurs.titre.trim());
    corps.append('description', valeurs.description);
    corps.append('categorie', valeurs.categorie);
    corps.append('type', valeurs.type);
    corps.append('photo', this.photo() as File);

    this.envoi.set(true);
    this.api.publier(corps).subscribe({
      next: () => {
        this.api.signalerPublication();
        void this.router.navigateByUrl('/annonces');
      },
      error: (erreur: HttpErrorResponse) => {
        this.envoi.set(false);
        const champs: ErreursChamps | undefined = erreur.status === 400 ? erreur.error?.erreurs : undefined;
        if (champs && Object.keys(champs).length > 0) {
          this.erreursServeur.set(champs);
          this.placerFocus(Object.keys(champs)[0] as Champ);
        } else {
          this.erreurGlobale.set(
            erreur.status === 400
              ? 'L’annonce n’a pas été publiée : le serveur a refusé la demande. Vérifiez les champs, puis réessayez.'
              : 'L’annonce n’a pas été publiée : le service ne répond pas. Réessayez dans un instant.',
          );
        }
      },
    });
  }

  private erreurClient(champ: Champ): string | null {
    if (champ === 'photo') {
      const fichier = this.photo();
      if (!fichier) {
        return this.soumis() ? MESSAGES.photoAbsente : null;
      }
      if (!FORMATS.includes(fichier.type)) {
        return MESSAGES.photoFormat;
      }
      return fichier.size > PHOTO_MAX ? MESSAGES.photoTropLourde : null;
    }
    const controle = this.formulaire.controls[champ];
    if (!controle.invalid || !(this.soumis() || controle.touched)) {
      return null;
    }
    return MESSAGES[champ];
  }

  private placerFocus(champ: Champ): void {
    const id = champ === 'type' ? 'type-ECHANGE' : champ;
    queueMicrotask(() => this.hote.nativeElement.querySelector<HTMLElement>(`#${id}`)?.focus());
  }
}
