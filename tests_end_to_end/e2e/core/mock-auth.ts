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
import { checkProviderAuthConfig } from './provider-keys';

export const MOCK_AUTH_PORT = parseInt(process.env.MOCK_AUTH_PORT ?? '9878', 10);

export const mockAuthBaseUrl =
  process.env.MOCK_AUTH_URL || `http://localhost:${MOCK_AUTH_PORT}`;

// Treat an empty value as unset: CI passes this conditionally, and a `${{ ... || '' }}`
// expression yields "" rather than omitting the variable.
export const mockAuthBaseUrlForBackend =
  process.env.MOCK_AUTH_URL_FOR_BACKEND || mockAuthBaseUrl;

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

/**
 * Whether the OPIK BACKEND can fetch a token from the mock service — not whether THIS process
 * can reach it. The two differ whenever the backend runs in docker-compose while the mock runs
 * on the host: the default `localhost:9878` then resolves to the container's own loopback, so
 * every token fetch fails and these specs die on an opaque missing success toast. Probing the
 * backend's own test-connection endpoint (which performs the fetch server-side) turns that into
 * a skip that names the cause.
 *
 * Resolves to null when the backend CAN reach it, else the reason to skip with.
 * Cached: the answer is a property of the deployment, and every spec in the area asks.
 */
let mockAuthGate: Promise<string | null> | undefined;

export function mockAuthSkipReason(): Promise<string | null> {
  mockAuthGate ??= (async () => {
    try {
      // A bare auth_config needs no stored provider: the backend runs the token fetch and
      // answers 200 with the lifetime when the URL is reachable.
      await checkProviderAuthConfig({
        token_url: mockTokenUrlForBackend,
        send_as: 'basic',
        credentials: [
          { key: 'grant_type', value: 'client_credentials', secret: false },
          { key: 'client_id', value: MOCK_AUTH_CLIENT_ID, secret: false },
          { key: 'client_secret', value: MOCK_AUTH_CLIENT_SECRET, secret: true },
        ],
      });
      return null;
    } catch (err) {
      const detail = err instanceof Error ? err.message : String(err);
      // 404 = deployment predates the endpoint (OPIK-7940), so token auth isn't there to test.
      if (detail.includes('404')) {
        return `deployment has no /auth-config/test endpoint, so dynamic token auth is unavailable (${detail})`;
      }
      return (
        `the Opik backend cannot fetch a token from ${mockTokenUrlForBackend} (${detail}). ` +
        'If the backend runs in docker-compose, set MOCK_AUTH_URL_FOR_BACKEND to a host-reachable ' +
        'address (CI uses http://172.17.0.1:9878) and run the backend with ' +
        'LLM_PROVIDER_TOKEN_AUTH_DESTINATION_GUARD=relaxed.'
      );
    }
  })();
  return mockAuthGate;
}
