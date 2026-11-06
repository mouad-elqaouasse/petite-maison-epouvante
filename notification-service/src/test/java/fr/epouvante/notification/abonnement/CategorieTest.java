package fr.epouvante.notification.abonnement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

// Décision D1 (ADR 0024) : la liste fermée est copiée de troc-service. Ce test fige les libellés du contrat
// (docs/schemas/evenement-annonce-publiee.md) : un écart avec troc-service doit casser le build.
class CategorieTest {

    @Test
    void les_libelles_sont_ceux_du_contrat_dans_le_meme_ordre() {
        assertThat(Arrays.stream(Categorie.values()).map(Categorie::libelle))
                .containsExactly("Figurines", "Films", "BD", "Jeux de société", "Goodies");
    }

    @Test
    void un_libelle_connu_est_reconnu_a_l_identique() {
        assertThat(Categorie.depuisLibelle("Jeux de société")).contains(Categorie.JEUX_DE_SOCIETE);
    }

    @Test
    void un_libelle_inconnu_ou_d_une_autre_casse_est_refuse() {
        assertThat(Categorie.depuisLibelle("Voitures")).isEmpty();
        assertThat(Categorie.depuisLibelle("bd")).isEmpty();
        assertThat(Categorie.depuisLibelle(null)).isEmpty();
    }
}
