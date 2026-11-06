package fr.epouvante.notification.abonnement;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// Accès à la table abonnement par JdbcClient, sans JPA (ADR 0019).
@Repository
class AbonnementDepot {

    private final JdbcClient base;

    AbonnementDepot(JdbcClient base) {
        this.base = base;
    }

    // Catégories du membre, dans l'ordre de la liste fermée.
    List<Categorie> categories(String membreId) {
        return base.sql("select categorie from abonnement where membre_id = :membreId")
                .param("membreId", membreId)
                .query((rs, ligne) -> Categorie.valueOf(rs.getString("categorie")))
                .list().stream()
                .sorted()
                .toList();
    }

    // Idempotent : un ajout rejoué ne change rien.
    void ajouter(String membreId, Categorie categorie) {
        base.sql("""
                insert into abonnement (membre_id, categorie) values (:membreId, :categorie)
                on conflict do nothing
                """)
                .param("membreId", membreId)
                .param("categorie", categorie.name())
                .update();
    }

    // Idempotent : retirer une catégorie non suivie ne change rien.
    void retirer(String membreId, Categorie categorie) {
        base.sql("delete from abonnement where membre_id = :membreId and categorie = :categorie")
                .param("membreId", membreId)
                .param("categorie", categorie.name())
                .update();
    }
}
