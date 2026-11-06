import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AbonnementsService } from './abonnements.service';

// US2 : API des abonnements de notification-service (décision D2 : libellé dans le chemin, encodé).
describe('AbonnementsService', () => {
  let http: HttpTestingController;
  let api: AbonnementsService;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    api = TestBed.inject(AbonnementsService);
  });

  afterEach(() => http.verify());

  it('lit les catégories suivies avec GET /api/notifications/abonnements', () => {
    let recues: string[] = [];
    api.lister().subscribe((c) => (recues = c));

    const requete = http.expectOne('/api/notifications/abonnements');
    expect(requete.request.method).toBe('GET');
    requete.flush(['BD']);
    expect(recues).toEqual(['BD']);
  });

  it('ajoute une catégorie avec PUT, libellé encodé dans le chemin', () => {
    api.abonner('Jeux de société').subscribe();

    const requete = http.expectOne('/api/notifications/abonnements/Jeux%20de%20soci%C3%A9t%C3%A9');
    expect(requete.request.method).toBe('PUT');
    requete.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('retire une catégorie avec DELETE', () => {
    api.desabonner('BD').subscribe();

    const requete = http.expectOne('/api/notifications/abonnements/BD');
    expect(requete.request.method).toBe('DELETE');
    requete.flush(null, { status: 204, statusText: 'No Content' });
  });
});
