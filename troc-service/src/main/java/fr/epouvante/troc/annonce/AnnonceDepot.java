package fr.epouvante.troc.annonce;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// Accès à la table annonce par JdbcClient, sans JPA (décision D2 du 05/11/2026).
@Repository
class AnnonceDepot {

    // Liste bornée : l'écran n'affiche que les plus récentes, la réponse reste petite sous charge.
    static final int TAILLE_LISTE = 50;

    private final JdbcClient base;

    AnnonceDepot(JdbcClient base) {
        this.base = base;
    }

    void enregistrer(Annonce a) {
        base.sql("""
                insert into annonce (id, titre, description, categorie, type, statut, auteur_id,
                                     photo_public_id, photo_url, cree_le)
                values (:id, :titre, :description, :categorie, :type, :statut, :auteurId,
                        :photoPublicId, :photoUrl, :creeLe)
                """)
                .param("id", a.id())
                .param("titre", a.titre())
                .param("description", a.description())
                .param("categorie", a.categorie().name())
                .param("type", a.type().name())
                .param("statut", a.statut().name())
                .param("auteurId", a.auteurId())
                .param("photoPublicId", a.photoPublicId())
                .param("photoUrl", a.photoUrl())
                .param("creeLe", Timestamp.from(a.creeLe()))
                .update();
    }

    List<Annonce> publiees() {
        return base.sql("""
                select id, titre, description, categorie, type, statut, auteur_id, photo_public_id, photo_url, cree_le
                from annonce where statut = 'PUBLIEE' order by cree_le desc, id limit :taille
                """)
                .param("taille", TAILLE_LISTE)
                .query((rs, i) -> new Annonce(
                        rs.getObject("id", UUID.class),
                        rs.getString("titre"),
                        rs.getString("description"),
                        Categorie.valueOf(rs.getString("categorie")),
                        Annonce.Type.valueOf(rs.getString("type")),
                        Annonce.Statut.valueOf(rs.getString("statut")),
                        rs.getString("auteur_id"),
                        rs.getString("photo_public_id"),
                        rs.getString("photo_url"),
                        rs.getTimestamp("cree_le").toInstant()))
                .list();
    }
}
