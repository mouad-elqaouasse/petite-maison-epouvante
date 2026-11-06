package fr.epouvante.notification.notifier;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// Accès à la table notification par JdbcClient, sans JPA (ADR 0019).
@Repository
class NotificationDepot {

    record Ligne(TypeNotification type, UUID annonceId, String titreAnnonce, Instant creeLe) {
    }

    private final JdbcClient base;

    NotificationDepot(JdbcClient base) {
        this.base = base;
    }

    // Idempotent (ADR 0025) : une notification déjà créée pour (membre, annonce, type) n'est pas recréée.
    // Renvoie true si une ligne a été ajoutée.
    boolean creer(String membreId, UUID annonceId, TypeNotification type, String titreAnnonce) {
        return base.sql("""
                insert into notification (membre_id, annonce_id, type, titre_annonce)
                values (:membreId, :annonceId, :type, :titre)
                on conflict (membre_id, annonce_id, type) do nothing
                """)
                .param("membreId", membreId)
                .param("annonceId", annonceId)
                .param("type", type.name())
                .param("titre", titreAnnonce)
                .update() == 1;
    }

    // Notifications du membre, plus récentes d'abord.
    List<Ligne> duMembre(String membreId) {
        return base.sql("""
                select type, annonce_id, titre_annonce, cree_le from notification
                where membre_id = :membreId
                order by cree_le desc, id
                """)
                .param("membreId", membreId)
                .query((rs, ligne) -> new Ligne(
                        TypeNotification.valueOf(rs.getString("type")),
                        rs.getObject("annonce_id", UUID.class),
                        rs.getString("titre_annonce"),
                        rs.getTimestamp("cree_le").toInstant()))
                .list();
    }
}
