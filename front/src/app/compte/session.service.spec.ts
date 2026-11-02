import { TestBed } from '@angular/core/testing';
import Keycloak from 'keycloak-js';
import { SessionService } from './session.service';

// Ticket SQ-06 : check-sso au démarrage (PKCE), état de session, connexion et déconnexion.
describe('SessionService', () => {
  const keycloak = {
    init: vi.fn(),
    login: vi.fn(),
    logout: vi.fn(),
    tokenParsed: undefined as Record<string, unknown> | undefined,
  };

  function service(): SessionService {
    TestBed.configureTestingModule({ providers: [{ provide: Keycloak, useValue: keycloak }] });
    return TestBed.inject(SessionService);
  }

  beforeEach(() => {
    keycloak.init.mockReset();
    keycloak.login.mockReset().mockResolvedValue(undefined);
    keycloak.logout.mockReset().mockResolvedValue(undefined);
    keycloak.tokenParsed = undefined;
  });

  it('vérifie la session tant que Keycloak n\'a pas répondu', () => {
    keycloak.init.mockReturnValue(new Promise(() => undefined));
    const session = service();

    void session.demarrer();

    expect(session.etat()).toEqual({ etat: 'verification' });
  });

  it('initialise Keycloak en check-sso silencieux avec PKCE, sans iframe de surveillance', async () => {
    keycloak.init.mockResolvedValue(false);

    await service().demarrer();

    expect(keycloak.init).toHaveBeenCalledWith({
      onLoad: 'check-sso',
      silentCheckSsoRedirectUri: `${window.location.origin}/silent-check-sso.html`,
      pkceMethod: 'S256',
      checkLoginIframe: false,
    });
  });

  it('session retrouvée : connecté avec le nom du jeton', async () => {
    keycloak.init.mockResolvedValue(true);
    keycloak.tokenParsed = { preferred_username: 'moderateur-demo' };
    const session = service();

    await session.demarrer();

    expect(session.etat()).toEqual({ etat: 'connecte', nom: 'moderateur-demo' });
  });

  it('pas de session : déconnecté', async () => {
    keycloak.init.mockResolvedValue(false);
    const session = service();

    await session.demarrer();

    expect(session.etat()).toEqual({ etat: 'deconnecte' });
  });

  it('Keycloak injoignable : erreur, sans bloquer le reste de la page', async () => {
    keycloak.init.mockRejectedValue(new Error('timeout'));
    const session = service();

    await session.demarrer();

    expect(session.etat()).toEqual({ etat: 'erreur' });
  });

  it('se connecter redirige vers Keycloak', () => {
    service().connecter();

    expect(keycloak.login).toHaveBeenCalledOnce();
  });

  it('se déconnecter ferme la session Keycloak et revient sur la page d\'accueil', () => {
    service().deconnecter();

    expect(keycloak.logout).toHaveBeenCalledWith({ redirectUri: `${window.location.origin}/` });
  });
});
