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

export async function findProviderKeyByName(providerName: string): Promise<ProviderKeyRef | null> {
  const response = await fetch(endpoint(), { headers: restHeaders() });
  if (!response.ok) throw new Error(`list provider keys returned ${response.status}`);
  const body = (await response.json()) as { content: ProviderKeyRef[] };
  return body.content.find((key) => key.provider_name === providerName) ?? null;
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
 * A custom-llm `base_url` that always answers a permanent HTTP 404.
 *
 * langchain4j appends `/chat/completions`, so the backend ends up asking its own
 * connector for a route that does not exist and gets its standard
 * `{"code":404,"message":"HTTP 404 Not Found"}` back. That makes "the provider
 * refused with a 4xx" a routing fact rather than a data fact — no external
 * service, no credentials, no mock process, and nothing that can start
 * succeeding.
 *
 * Deliberately not httpbin.org or any other public status echo: a permanent test
 * must not depend on a third party being up, and an external body would also be
 * empty, which the scorer renders as a blank error reason.
 */
export const notFoundProviderBaseUrl = `${backendSelfUrl}/qa-no-such-llm-endpoint`;

export async function createProviderKey(payload: {
  provider: string;
  provider_name: string;
  base_url: string;
  /** Static bearer for providers that are not in `auth_config` token mode. */
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
