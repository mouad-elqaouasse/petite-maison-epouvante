package fr.epouvante.notification.notifier;

// Types de notification d'US3 et texte affiché (docs/backlog/US3-notifier-nouvelle-annonce.md).
enum TypeNotification {
    // À l'auteur : sa publication a abouti.
    CONFIRMATION("Votre annonce est publiée"),
    // Aux membres abonnés à la catégorie, sauf l'auteur.
    ALERTE("Nouvelle annonce");

    private final String texte;

    TypeNotification(String texte) {
        this.texte = texte;
    }

    String texte() {
        return texte;
    }
}
