// Transmet à la page (même origine) l'URL de retour de Keycloak, lue par keycloak-js.
parent.postMessage(location.href, location.origin);
