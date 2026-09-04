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

async function listProviderKeys(): Promise<ProviderKeyRef[]> {
  const response = await fetch(endpoint(), { headers: restHeaders() });
  if (!response.ok) throw new Error(`list provider keys returned ${response.status}`);
  const body = (await response.json()) as { content: ProviderKeyRef[] };
  return body.content;
}

export async function findProviderKeyByName(providerName: string): Promise<ProviderKeyRef | null> {
  const keys = await listProviderKeys();
  return keys.find((key) => key.provider_name === providerName) ?? null;
}

/**
 * Find a key by its PROVIDER slug (`gemini`, `vertex-ai`, …) rather than by
 * `provider_name`, which only the custom/bedrock/ollama providers carry.
 *
 * The workspace holds at most one key per built-in provider, so this is the
 * only way to ask "is Gemini already configured here" — and a caller that
 * seeds one has to ask, because creating a second would collide with whatever
 * key the environment already had.
 */
export async function findProviderKeyByProvider(provider: string): Promise<ProviderKeyRef | null> {
  const keys = await listProviderKeys();
  return keys.find((key) => key.provider === provider) ?? null;
}

/**
 * Create a provider key, and report its id from the `Location` header —
 * creation answers 201 with an empty body, so that header is the only place
 * the id appears.
 *
 * `null` rather than a throw when the header is missing, so a caller that
 * cleans up by `provider_name` is not made to care: only a caller that has to
 * address the key by id needs the header, and that caller is the one that
 * should complain about it.
 */
export async function createProviderKey(payload: {
  provider: string;
  /** Only the custom/bedrock/ollama providers name their keys. */
  provider_name?: string;
  base_url?: string;
  /** The provider secret. Built-in providers key off this rather than `auth_config`. */
  api_key?: string;
  configuration?: Record<string, string>;
  auth_config?: ProviderAuthConfig;
}): Promise<string | null> {
  const response = await fetch(endpoint(), {
    method: 'POST',
    headers: restHeaders(),
    body: JSON.stringify(payload),
  });
  if (!response.ok) {
    throw new Error(`create provider key returned ${response.status}: ${await response.text()}`);
  }
  return response.headers.get('location')?.split('/').filter(Boolean).pop() ?? null;
}

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

export async function deleteProviderKeyByName(providerName: string): Promise<void> {
  const found = await findProviderKeyByName(providerName);
  if (!found) return;
  await deleteProviderKeyById(found.id);
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
