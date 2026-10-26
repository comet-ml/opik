/**
 * Thin fetch-based helpers for the LLM provider-key REST endpoints.
 *
 * Stopgap: the suite's TS SDK predates the `auth_config` field (OPIK-7940), so the token-auth
 * specs read and clean up provider keys through the public REST surface directly.
 *
 * TODO: once the SDK is regenerated with `auth_config`, migrate these to
 * `opik.api.llmProviderKeys.*` on the backend client (core/backend/client.ts) and delete
 * this module.
 * Provider keys are WORKSPACE-GLOBAL — every spec must use a testNamespace-prefixed
 * provider_name and delete what it creates, or parallel runs will trample each other.
 */
import { loadEnvConfig } from '../config/env.config';

export interface ProviderAuthCredential {
  key: string;
  value: string;
  secret: boolean;
}

export interface ProviderAuthConfig {
  token_url?: string;
  send_as?: string;
  credentials?: ProviderAuthCredential[];
  token_field?: string;
  expires_field?: string;
  fallback_ttl_seconds?: number;
}

export interface ProviderKeyRef {
  id: string;
  provider: string;
  provider_name?: string;
  base_url?: string;
  auth_config?: ProviderAuthConfig;
  /**
   * The provider's own settings, as stored. `models` is a COMMA-SEPARATED
   * string of fully-qualified ids (`custom-llm/<provider>/<model>`), not an
   * array — see `providerModelNames` for reading it as a list.
   */
  configuration?: { models?: string };
}

/**
 * The model display names a Custom provider offers, in stored order.
 *
 * For a spec to prove its own seed landed before it opens a browser: a model
 * picker asserted over a provider that silently kept fewer models than the test
 * asked for is a test that cannot fail, and it reads as coverage forever.
 *
 * The stored value is one comma-separated string of qualified ids; the picker
 * renders only the model segment, so that is what comes back here — the names a
 * caller can match against what is on screen.
 */
export async function providerModelNames(providerName: string): Promise<string[]> {
  const key = await findProviderKeyByName(providerName);
  if (!key) throw new Error(`providerModelNames: no provider key named '${providerName}'`);
  const models = key.configuration?.models;
  if (typeof models !== 'string' || models.trim() === '') {
    throw new Error(
      `providerModelNames: provider '${providerName}' stored no models — ` +
        `configuration was ${JSON.stringify(key.configuration)}`,
    );
  }
  return models.split(',').map((id) => id.trim().split('/').pop() ?? '');
}

function restHeaders(): Record<string, string> {
  const env = loadEnvConfig();
  return {
    'Content-Type': 'application/json',
    ...(env.apiKey ? { authorization: env.apiKey } : {}),
    ...(env.workspace ? { 'Comet-Workspace': env.workspace } : {}),
  };
}

function endpoint(path = ''): string {
  const env = loadEnvConfig();
  return `${env.apiBaseUrl}/v1/private/llm-provider-key${path}`;
}

export async function findProviderKeyByName(providerName: string): Promise<ProviderKeyRef | null> {
  const response = await fetch(endpoint(), { headers: restHeaders() });
  if (!response.ok) throw new Error(`list provider keys returned ${response.status}`);
  const body = (await response.json()) as { content: ProviderKeyRef[] };
  return body.content.find((key) => key.provider_name === providerName) ?? null;
}

/**
 * Find a key for a BUILT-IN provider (`openrouter`, `openai`, …), which is
 * addressed by its provider type rather than by a name.
 *
 * `findProviderKeyByName` cannot see these at all: a built-in provider has no
 * `provider_name`, because there is only ever one key per provider per
 * workspace. That also makes it un-namespaceable — a spec cannot give itself a
 * private one — so a caller that needs a built-in key must REUSE whatever the
 * workspace already has and delete only a key it seeded itself. Clobbering a
 * real key on a shared workspace is not recoverable from a test run.
 */
export async function findProviderKeyByProvider(provider: string): Promise<ProviderKeyRef | null> {
  const response = await fetch(endpoint(), { headers: restHeaders() });
  if (!response.ok) throw new Error(`list provider keys returned ${response.status}`);
  const body = (await response.json()) as { content: ProviderKeyRef[] };
  return body.content.find((key) => key.provider === provider) ?? null;
}

/** Delete one provider key by id — for built-ins, which have no name to look up. */
export async function deleteProviderKeyById(id: string): Promise<void> {
  const response = await fetch(endpoint('/delete'), {
    method: 'POST',
    headers: restHeaders(),
    body: JSON.stringify({ ids: [id] }),
  });
  if (!response.ok) {
    throw new Error(`delete provider key returned ${response.status}`);
  }
}

/**
 * How the OPIK BACKEND addresses its own HTTP connector — not how this process
 * addresses the deployment.
 *
 * The default is the backend's own `SERVER_APPLICATION_PORT` (8080, see
 * apps/opik-backend/config.yml), which every deployment shape can reach from
 * inside itself: a host process, a docker-compose container, a pod. That is the
 * whole point — unlike `mockAuthBaseUrlForBackend`, this needs no topology
 * knowledge and no host-reachable address, so it works identically on a local
 * `oss` stack and on a remote deployment. Override only if the connector is
 * moved off 8080.
 */
export const backendSelfUrl = process.env.OPIK_BACKEND_SELF_URL || 'http://localhost:8080';

/**
 * Whether this deployment's provider destination guard refuses internal
 * addresses — the `strict` mode of `LLM_PROVIDER_DESTINATION_GUARD`.
 *
 * It matters to every seeded failure provider in the estate. opik#9214 made
 * `strict` the APPLICATION default, and under it `127.0.0.1`, `localhost` and
 * every RFC 1918 address are refused with a 400 *on save* — so a fixture that
 * seeds a loopback `base_url` does not produce a provider that fails to
 * answer, it produces no provider at all. Both shipped deployment manifests set
 * `relaxed`, because a self-hosted gateway legitimately lives on an internal
 * network; cloud runs `strict`.
 *
 * Inferred from the deployment shape rather than probed, so no seed has to be
 * attempted and thrown away to find out: `cloud` is the deployment that runs
 * `strict`. `OPIK_PROVIDER_DESTINATION_GUARD` overrides it for a deployment
 * configured against that grain — a self-hosted stack that has deliberately
 * turned the guard on, or a cloud-shaped target running `relaxed`.
 */
export function guardRefusesInternalDestinations(): boolean {
  const override = process.env.OPIK_PROVIDER_DESTINATION_GUARD;
  if (override) return override.trim().toLowerCase() === 'strict';
  return loadEnvConfig().deployment === 'cloud';
}

/**
 * A custom-llm `base_url` that always answers a permanent HTTP 404.
 *
 * langchain4j appends `/chat/completions`, so the backend ends up asking for a
 * route that does not exist and gets the standard
 * `{"code":404,"message":"HTTP 404 Not Found"}` back. That makes "the provider
 * refused with a 4xx" a routing fact rather than a data fact — no external
 * service, no credentials, no mock process, and nothing that can start
 * succeeding.
 *
 * Deliberately not httpbin.org or any other public status echo: a permanent test
 * must not depend on a third party being up, and an external body would also be
 * empty, which the scorer renders as a blank error reason.
 *
 * KNOWN LIMITATION, and not fixable without giving up what this URL is for: it
 * only works where the destination guard is `relaxed`. Under `strict` a
 * loopback `base_url` is refused on save, so `createPermanentlyFailing` cannot
 * seed at all — see `guardRefusesInternalDestinations`. Substituting a public
 * host would not rescue it either, because the whole value here is that the
 * 404 body is OPIK'S OWN (`{"code":404,"message":"HTTP 404 Not Found"}`), which
 * is what the one spec on this path quotes; any other host answers a body of
 * its own. Fixing it needs a reachable always-404 route inside the deployment,
 * which is a deployment change rather than a test one. `unreachableBaseUrl` in
 * the provider-key fixture is strict-safe because it promises only that the
 * call fails fast, never what the failure says.
 */
export const notFoundProviderBaseUrl = `${backendSelfUrl}/qa-no-such-llm-endpoint`;

/**
 * A base URL that fails FAST and that a `strict` deployment will store.
 *
 * Under `strict` the estate has no better option, and this is not for want of
 * looking. Probed against cloud staging at 2.2.121: loopback and every RFC
 * 1918, CGNAT and link-local address are refused on save; the DEPLOYMENT'S OWN
 * hostname is refused too, because it resolves to a private address from inside
 * the cluster; and the documentation-reserved literals (`192.0.2.1`,
 * `198.51.100.1`, `203.0.113.1`) are all stored but they BLACKHOLE, so a call
 * to one hangs until it times out instead of failing. There is no public
 * address that refuses a connect on demand — a refusal needs a host that is up
 * with nothing listening, which nothing public guarantees.
 *
 * So this is the one URL in the estate that depends on a host outside the
 * deployment, and the choice is narrowed by exactly that: `comet.com` is
 * FIRST-PARTY — the same organisation that operates the deployment under test —
 * rather than a third-party status echo, so "the dependency is down" and "the
 * vendor is down" are the same event. The path will never exist, so the 4xx is
 * permanent and cannot start succeeding.
 *
 * Only ever used where the caller needs the call to fail quickly and cheaply,
 * never where it needs a particular error BODY — that is
 * `notFoundProviderBaseUrl`, and see the limitation recorded on it.
 *
 * Override with `OPIK_PUBLIC_NOT_FOUND_BASE_URL` for a `strict` deployment that
 * has its own reachable always-4xx host, which is strictly better than this.
 */
export const PUBLIC_NOT_FOUND_BASE_URL =
  process.env.OPIK_PUBLIC_NOT_FOUND_BASE_URL ||
  'https://www.comet.com/qa-no-such-llm-endpoint';

/**
 * A public base URL that always passes the destination guard and is never
 * connected to.
 *
 * `192.0.2.1` is TEST-NET-1 (RFC 5737). Three properties, all load-bearing for
 * a spec whose subject is the SAVE rather than the call:
 *
 *  - It is a literal address, so the guard's `InetAddress.getAllByName` parses
 *    rather than resolves — no DNS, no third-party host, nothing that can
 *    start failing.
 *  - It is public by IANA and outside every range `DestinationGuard`'s
 *    `NON_PUBLIC_RANGES` refuses (note `192.0.0.0/24` is a different block and
 *    does not cover it), so it saves under `strict` as well as `relaxed` —
 *    which the estate's loopback URLs do NOT: a `strict` deployment refuses
 *    `127.0.0.1` and `localhost` with a 400 on save.
 *  - `https`, so it also satisfies a deployment running the guard in
 *    `HTTPS_ONLY` scheme mode rather than the `plaintext_or_tls` default.
 *
 * Deliberately NOT for a provider anything will call: a connect to TEST-NET-1
 * hangs until it times out. `createUnresponsive` wants exactly that; a caller
 * here wants a URL the server will store and never dial.
 */
export const guardPassingBaseUrl = 'https://192.0.2.1/v1';

/** A second {@link guardPassingBaseUrl}, for asserting what a base_url CHANGE does. */
export const guardPassingBaseUrlAlternate = 'https://192.0.2.1/v2';

/**
 * As {@link createProviderKey}, but hands back the status and body instead of
 * throwing on a non-2xx.
 *
 * For specs whose subject is the REFUSAL — `provider=opik-free`, a guarded
 * destination — where the status code and the message are the assertion. The
 * throwing version turns both into an `Error` string a caller would have to
 * parse back out.
 */
export async function createProviderKeyRaw(payload: {
  provider: string;
  provider_name?: string;
  base_url?: string;
  api_key?: string;
  configuration?: Record<string, string>;
  auth_config?: ProviderAuthConfig;
}): Promise<{ status: number; body: string; id: string | null }> {
  const response = await fetch(endpoint(), {
    method: 'POST',
    headers: restHeaders(),
    body: JSON.stringify(payload),
  });
  // A 201 answers with no body: the `Location` header is the only place the new
  // id appears. Taken from there rather than by listing back and matching on
  // `provider_name`, so the id a caller then PATCHes is the one this request
  // actually created — on a shared workspace a list lookup can answer for a
  // namesake another run left behind.
  const location = response.headers.get('location');
  return {
    status: response.status,
    body: await response.text(),
    id: location ? (location.split('/').pop() ?? null) : null,
  };
}

/**
 * `PATCH /v1/private/llm-provider-key/{id}` — the update the AI providers tab
 * sends, with the status and body kept.
 *
 * Raw by default rather than throwing, because the interesting updates here are
 * the ones the server REFUSES: changing `base_url` without resubmitting the
 * credentials is a 400 whose message is the whole point
 * (`LlmProviderApiKeyService.validateCredentialsResubmittedOnUrlChange`).
 *
 * `base_url` and `api_key` are both optional and the distinction between
 * omitted and empty is the contract: the rule only fires when `base_url` is
 * PRESENT and different, so a caller testing the no-op branch must be able to
 * send the same URL, and one testing the fires-branch must be able to send a
 * URL with no credential beside it.
 */
export async function patchProviderKey(
  id: string,
  payload: {
    base_url?: string;
    api_key?: string;
    configuration?: Record<string, string>;
    auth_config?: ProviderAuthConfig;
  },
): Promise<{ status: number; body: string }> {
  const response = await fetch(endpoint(`/${id}`), {
    method: 'PATCH',
    headers: restHeaders(),
    body: JSON.stringify(payload),
  });
  return { status: response.status, body: await response.text() };
}

/**
 * One provider key by id — `GET /v1/private/llm-provider-key/{id}`.
 *
 * Distinct from `findProviderKeyByName`, which pages the list: a caller
 * asserting that a REFUSED update did not partially apply wants the stored row
 * itself, and a 404 here is a different finding from "absent from the list".
 */
export async function getProviderKeyById(id: string): Promise<ProviderKeyRef> {
  const response = await fetch(endpoint(`/${id}`), { headers: restHeaders() });
  if (!response.ok) {
    throw new Error(`get provider key ${id} returned ${response.status}: ${await response.text()}`);
  }
  return (await response.json()) as ProviderKeyRef;
}

export async function createProviderKey(payload: {
  provider: string;
  /**
   * Required for the provider types that compose their key from it (custom,
   * bedrock, ollama); omitted for a built-in provider, which has exactly one
   * row per workspace and no name of its own.
   */
  provider_name?: string;
  /** Omitted for a built-in provider, which the backend routes itself. */
  base_url?: string;
  /**
   * Static bearer, for providers that are not in OAuth2 token-auth mode. The
   * endpoint requires one whenever `auth_config` is absent, so a static-auth
   * provider cannot be created without it.
   */
  api_key?: string;
  configuration?: Record<string, string>;
  auth_config?: ProviderAuthConfig;
}): Promise<void> {
  const response = await fetch(endpoint(), {
    method: 'POST',
    headers: restHeaders(),
    body: JSON.stringify(payload),
  });
  if (!response.ok) {
    throw new Error(`create provider key returned ${response.status}: ${await response.text()}`);
  }
}

/**
 * The provider TYPES (`openai`, `gemini`, …) the workspace already has a key
 * for, built-in ones included — unlike `findProviderKeyByName`, which matches
 * the user-defined `provider_name` only custom providers carry.
 */
export async function listConfiguredProviderTypes(): Promise<string[]> {
  const response = await fetch(endpoint(), { headers: restHeaders() });
  if (!response.ok) throw new Error(`list provider keys returned ${response.status}`);
  const body = (await response.json()) as { content: ProviderKeyRef[] };
  return body.content.map((key) => key.provider);
}

/**
 * Make sure a BUILT-IN provider (`openai`, `anthropic`, `gemini`, `vertex-ai`)
 * has a key on the workspace, and report whether one had to be added.
 *
 * Strictly additive, and that is load-bearing in both directions:
 *
 *  - It never overwrites. A built-in provider is ONE row per workspace, so
 *    writing over an existing key would destroy a real credential another spec
 *    (or a human) depends on.
 *  - It never deletes, and neither should its caller. Because there is only one
 *    row per provider, a fixture that removed its own key would pull the
 *    provider out from under any spec running in parallel that needs the same
 *    one. This is why `ConfigurationPage.ensureProviderConfigured` has no
 *    teardown either.
 *
 * `apiKey` is used only when the provider is absent. Pass a real key from the
 * environment when the runner has one; a placeholder is acceptable only for
 * specs that never let a request reach the provider, and the caller owns that
 * judgement (see the `modelRegistryProviders` fixture for the trade-off a
 * placeholder leaves behind).
 */
export async function ensureBuiltInProviderKey(
  provider: string,
  apiKey: string,
): Promise<{ added: boolean }> {
  if ((await listConfiguredProviderTypes()).includes(provider)) return { added: false };
  try {
    // Only `provider` and `api_key`: a built-in provider carries no name of
    // its own and no base URL, and sending empty ones would store them.
    await createProviderKey({ provider, api_key: apiKey });
  } catch (err) {
    // A parallel worker may have created it between the read and this write.
    // The endpoint refuses the duplicate, which is the outcome we wanted.
    if (!(await listConfiguredProviderTypes()).includes(provider)) throw err;
    return { added: false };
  }
  return { added: true };
}

export async function deleteProviderKeyByName(providerName: string): Promise<void> {
  const found = await findProviderKeyByName(providerName);
  if (!found) return;
  const response = await fetch(endpoint('/delete'), {
    method: 'POST',
    headers: restHeaders(),
    body: JSON.stringify({ ids: [found.id] }),
  });
  if (!response.ok) {
    throw new Error(`delete provider key returned ${response.status}`);
  }
}

/** Carries the HTTP status so callers can classify a failure instead of parsing prose. */
export class AuthConfigCheckError extends Error {
  constructor(
    readonly status: number,
    readonly body: string,
  ) {
    super(`auth-config check returned ${status}: ${body}`);
    this.name = 'AuthConfigCheckError';
  }
}

/**
 * Server-side connection check: the BACKEND performs the token fetch. Passing a provider id
 * tests the stored recipe (resolving __SECRET__ sentinels); passing an auth_config tests
 * submitted values without needing a stored provider at all.
 * Throws AuthConfigCheckError on a non-2xx. Note 400 is overloaded — the endpoint returns it
 * for every way the fetch itself can fail (unreachable URL, refused destination, rejected
 * credentials, malformed reply), so the status alone does not identify the cause.
 */
export async function checkProviderAuthConfig(
  target: string | ProviderAuthConfig,
): Promise<{ lifetime_seconds: number }> {
  const body =
    typeof target === 'string' ? { provider_id: target } : { auth_config: target };
  const response = await fetch(endpoint('/auth-config/test'), {
    method: 'POST',
    headers: restHeaders(),
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    throw new AuthConfigCheckError(response.status, await response.text());
  }
  return (await response.json()) as { lifetime_seconds: number };
}
