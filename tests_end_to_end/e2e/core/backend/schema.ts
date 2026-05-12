// Hand-written types for the Opik backend endpoints the e2e suite consumes
// (currently only project list/create/delete for the cuj-* teardown sweep).
// Replace with the openapi-typescript output once `npm run backend:generate`
// is wired up against a stable OpenAPI source — see core/backend/generated/.

export interface paths {
  '/v1/private/projects': {
    get: {
      parameters: { query?: { name?: string; size?: number; page?: number } };
      responses: {
        200: {
          content: {
            'application/json': {
              content?: Array<{ id: string; name: string }>;
            };
          };
        };
      };
    };
    post: {
      requestBody: {
        content: {
          'application/json': { name: string; description?: string };
        };
      };
      responses: {
        201: { content: { 'application/json': { id: string; name: string } } };
        204: { content: never };
      };
    };
  };
  '/v1/private/projects/{id}': {
    delete: {
      parameters: { path: { id: string } };
      responses: {
        204: { content: never };
        404: { content: { 'application/json': { code: number; message: string } } };
      };
    };
  };
}
