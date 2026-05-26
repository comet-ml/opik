import { Opik } from 'opik';
import type { EnvConfig } from '../../config/env.config';

export type TypescriptSdk = Opik;

export function makeTypescriptSdk(env: EnvConfig): TypescriptSdk {
  // OSS deployments don't require an API key; cloud/self-hosted do.
  if (env.deployment !== 'oss' && !env.apiKey) {
    throw new Error(
      'makeTypescriptSdk requires env.apiKey for non-oss deployments — set OPIK_API_KEY or provide OPIK_TEST_USER_EMAIL+OPIK_TEST_USER_PASSWORD for globalSetup to mint one',
    );
  }
  return new Opik({
    apiKey: env.apiKey ?? undefined,
    workspaceName: env.workspace,
    apiUrl: env.apiBaseUrl,
  });
}
