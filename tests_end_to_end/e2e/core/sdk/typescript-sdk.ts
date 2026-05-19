import { Opik } from 'opik';
import type { EnvConfig } from '../../config/env.config';

export type TypescriptSdk = Opik;

export function makeTypescriptSdk(env: EnvConfig): TypescriptSdk {
  if (!env.apiKey) {
    throw new Error(
      'makeTypescriptSdk requires env.apiKey — set OPIK_API_KEY (or run a deployment that populates it)',
    );
  }
  return new Opik({
    apiKey: env.apiKey,
    workspaceName: env.workspace,
    apiUrl: env.apiBaseUrl,
  });
}
