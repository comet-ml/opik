/**
 * Endpoints of the mock OAuth2 token service + bearer-validating gateway
 * (services/mock-token-auth/), which Playwright's webServer spawns for the run.
 *
 * Two views of the same service, because the test process and the Opik backend
 * may live on different network planes:
 *  - `mockAuthBaseUrl`           — as reached from THIS process (specs poking /stats, /revoke).
 *  - `mockAuthBaseUrlForBackend` — as reached from the OPIK BACKEND, which is what goes into
 *    the provider config (token URL, base URL). Locally the backend is a host process, so the
 *    default is the same localhost; when the backend runs inside docker-compose, set
 *    MOCK_AUTH_URL_FOR_BACKEND=http://host.docker.internal:9878 (and make sure the backend
 *    runs with LLM_PROVIDER_TOKEN_AUTH_DESTINATION_GUARD=relaxed, as the compose file ships).
 */
export const MOCK_AUTH_PORT = parseInt(process.env.MOCK_AUTH_PORT ?? '9878', 10);

export const mockAuthBaseUrl =
  process.env.MOCK_AUTH_URL ?? `http://localhost:${MOCK_AUTH_PORT}`;

export const mockAuthBaseUrlForBackend =
  process.env.MOCK_AUTH_URL_FOR_BACKEND ?? mockAuthBaseUrl;

/** Fixed identities the mock accepts (see mock_token_auth_service.py). */
export const MOCK_AUTH_CLIENT_ID = 'opik-test';
export const MOCK_AUTH_CLIENT_SECRET = 'opik-secret';

export const mockTokenUrlForBackend = `${mockAuthBaseUrlForBackend}/oauth/token`;
export const mockGatewayUrlForBackend = `${mockAuthBaseUrlForBackend}/v1`;

/**
 * Counter map from /stats. Global outcome counters (tokens_issued, chat_ok,
 * chat_refused_unknown, ...) plus model-scoped variants (`chat_ok:<model>`) so
 * parallel specs can assert on their own traffic via unique model names.
 */
export type MockAuthStats = Record<string, number>;

export async function mockAuthStats(): Promise<MockAuthStats> {
  const response = await fetch(`${mockAuthBaseUrl}/stats`);
  if (!response.ok) throw new Error(`mock-auth /stats returned ${response.status}`);
  return (await response.json()) as MockAuthStats;
}

export async function mockAuthRevokeAll(): Promise<void> {
  const response = await fetch(`${mockAuthBaseUrl}/revoke`, { method: 'POST' });
  if (!response.ok) throw new Error(`mock-auth /revoke returned ${response.status}`);
}
