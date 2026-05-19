import type { EnvConfig } from '../../config/env.config';
import { makePythonSdkClient, type PythonSdkClient } from './python-sdk-client';
import { makeTypescriptSdk, type TypescriptSdk } from './typescript-sdk';

export interface SdkClient {
  python: PythonSdkClient;
  typescript: TypescriptSdk;
}

export function makeSdkClient(env: EnvConfig): SdkClient {
  return {
    python: makePythonSdkClient(),
    typescript: makeTypescriptSdk(env),
  };
}

export { PythonSdkBridgeError } from './python-sdk-client';
export type { PythonSdkClient, TypescriptSdk };
