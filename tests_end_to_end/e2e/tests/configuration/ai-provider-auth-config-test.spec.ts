import { test, expect } from '@e2e/fixtures';

/**
 * `POST /v1/private/llm-provider-key/auth-config/test` runs a custom provider's
 * token-fetch recipe once, backend-side, so an operator can tell a broken auth
 * configuration from a broken model before saving it.
 *
 * API-level by design: no page shows the endpoint's contract beyond a toast,
 * and the guarantees worth pinning are negative ones — the fetched bearer must
 * never cross the API boundary, and a configuration mistake must come back as a
 * 400 carrying the backend's own message rather than a 500 or a bare failure.
 *
 * Every case below is a *failure* path, which is deliberate: they need no
 * third-party authorisation server, so the spec cannot flake on someone else's
 * uptime. The success path is not asserted here — see the spec-level note at
 * the end of the file.
 */

const UNREACHABLE_TOKEN_URL = 'https://opik-e2e-no-such-auth-host.invalid/token';
const CLIENT_ID_VALUE = 'e2e-client-id-value';
const CLIENT_SECRET_VALUE = 'e2e-client-secret-value';

const AUTH_CONFIG = {
  token_url: UNREACHABLE_TOKEN_URL,
  send_as: 'basic',
  credentials: [
    { key: 'client_id', value: CLIENT_ID_VALUE, secret: false },
    { key: 'client_secret', value: CLIENT_SECRET_VALUE, secret: true },
  ],
};

test.describe(
  'AI providers — token auth test connection',
  { tag: ['@t2-cuj', '@area:configuration'] },
  () => {
    test(
      'Test connection rejects a request that names no configuration to test',
      { tag: ['@cap:configuration.ai-provider-add'] },
      async ({ backendClient, aiProviders }) => {
        await test.step('Neither provider_id nor auth_config is a bad request', async () => {
          const response = await backendClient.testProviderAuthConfig({});
          expect(response.status).toBe(400);
          expect(response.body.message).toBe('either provider_id or auth_config must be provided');
        });

        await test.step('A provider on static-key auth has no recipe to run', async () => {
          const provider = await aiProviders.seed({
            suffix: 'static',
            apiKey: 'sk-e2e-static-key',
          });
          const response = await backendClient.testProviderAuthConfig({
            provider_id: provider.id,
          });
          expect(response.status).toBe(400);
          expect(response.body.message).toBe('the provider has no auth_config to test');
        });
      },
    );

    test(
      'Test connection reports an incomplete recipe field by field',
      { tag: ['@cap:configuration.ai-provider-add'] },
      async ({ backendClient }) => {
        await test.step('A token URL that is not an absolute URI is named', async () => {
          const response = await backendClient.testProviderAuthConfig({
            auth_config: { ...AUTH_CONFIG, token_url: 'auth.example.com/token' },
          });
          expect(response.status).toBe(400);
          expect(response.body.message).toBe('auth_config.token_url must be a valid absolute URI');
        });

        await test.step('An empty credential list is named', async () => {
          const response = await backendClient.testProviderAuthConfig({
            auth_config: { ...AUTH_CONFIG, credentials: [] },
          });
          expect(response.status).toBe(400);
          expect(response.body.message).toBe('auth_config.credentials must not be empty');
        });

        await test.step('Both faults are reported together, not one at a time', async () => {
          const response = await backendClient.testProviderAuthConfig({
            auth_config: { ...AUTH_CONFIG, token_url: 'auth.example.com/token', credentials: [] },
          });
          expect(response.status).toBe(400);
          expect(response.body.message).toBe(
            'auth_config.token_url must be a valid absolute URI; auth_config.credentials must not be empty',
          );
        });
      },
    );

    test(
      'A token fetch that cannot reach the auth service names the URL and leaks no credential',
      { tag: ['@cap:configuration.ai-provider-add'] },
      async ({ backendClient }) => {
        const response = await backendClient.testProviderAuthConfig({ auth_config: AUTH_CONFIG });

        await test.step("The upstream failure is the caller's 400, not a 500", async () => {
          expect(response.status).toBe(400);
        });

        await test.step('The message names the endpoint that could not be reached', async () => {
          const message = String(response.body.message);
          expect(message).toContain('could not reach');
          expect(message).toContain(UNREACHABLE_TOKEN_URL);
        });

        await test.step('No credential value appears anywhere in the reply', async () => {
          // Serialised whole: a value leaking through a field other than
          // `message` would still be a leak.
          const serialised = JSON.stringify(response.body);
          expect(serialised).not.toContain(CLIENT_SECRET_VALUE);
          expect(serialised).not.toContain(CLIENT_ID_VALUE);
        });
      },
    );

    test(
      'A failed test connection returns no token field on any path',
      { tag: ['@cap:configuration.ai-provider-add'] },
      async ({ backendClient, aiProviders }) => {
        const provider = await aiProviders.seed({
          suffix: 'unreachable',
          authConfig: AUTH_CONFIG,
        });

        // Both request modes — the stored recipe, and one submitted as-is —
        // reach the same fetch, so both are checked for the leak.
        const responses = [
          await backendClient.testProviderAuthConfig({ provider_id: provider.id }),
          await backendClient.testProviderAuthConfig({ auth_config: AUTH_CONFIG }),
          await backendClient.testProviderAuthConfig({
            provider_id: provider.id,
            auth_config: AUTH_CONFIG,
          }),
        ];

        for (const response of responses) {
          expect(response.status).toBe(400);
          // The endpoint's whole point is that a bearer never crosses this
          // boundary; the error body must carry the lifetime shape or nothing,
          // never a token under any name.
          expect(Object.keys(response.body).sort()).toEqual(['code', 'message']);
        }
      },
    );
  },
);

/*
 * Not asserted here: the 200 shape (`{ lifetime_seconds }` and no token field).
 * Reaching it needs an authorisation server the backend can call, and this
 * suite's targets — local OSS and the deployed review environments — have none.
 * Standing one up inside the estate, or pointing at a public echo endpoint,
 * would trade a real assertion for a dependency on a third party's uptime.
 * The exploration this spec came from verified that path by hand.
 */
