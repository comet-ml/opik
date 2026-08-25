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

export async function createProviderKey(payload: {
  provider: string;
  provider_name: string;
  base_url: string;
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

/** Server-side connection check; resolves __SECRET__ sentinels via provider_id. */
export async function checkProviderAuthConfig(providerId: string): Promise<{ lifetime_seconds: number }> {
  const response = await fetch(endpoint('/auth-config/test'), {
    method: 'POST',
    headers: restHeaders(),
    body: JSON.stringify({ provider_id: providerId }),
  });
  if (!response.ok) {
    throw new Error(`auth-config check returned ${response.status}: ${await response.text()}`);
  }
  return (await response.json()) as { lifetime_seconds: number };
}
