import { test, expect } from '@playwright/test';
import { loadEnvConfig } from '../../config/env.config';
import { makeSdkClient, PythonSdkBridgeError } from '../../core/sdk';
import { makeBackendClient } from '../../core/backend';

test.describe('sdk-clients seed', () => {
  test('python bridge creates a project that the backend client can list and delete', async () => {
    const env = loadEnvConfig();
    const sdk = makeSdkClient(env);
    const backend = makeBackendClient(env.apiKey);
    const name = `${env.cujPrefix}-w0-sdk-clients-seed`;

    const created = await sdk.python.createProject({ name });
    expect(created.id).toMatch(/[0-9a-f-]+/i);
    expect(created.name).toBe(name);

    const listed = await backend.listProjectsWithPrefix(name);
    expect(listed.map((p) => p.id)).toContain(created.id);

    await backend.deleteProject(created.id);
    const after = await backend.listProjectsWithPrefix(name);
    expect(after).toHaveLength(0);
  });

  test('typescript sdk constructs and lists projects via the public api field', async () => {
    const env = loadEnvConfig();
    const sdk = makeSdkClient(env);
    const page = await sdk.typescript.api.projects.findProjects({
      name: env.cujPrefix,
      size: 1,
    });
    expect(Array.isArray(page.content)).toBe(true);
  });

  test('python bridge surfaces validation errors as PythonSdkBridgeError(422)', async () => {
    const env = loadEnvConfig();
    const sdk = makeSdkClient(env);
    await expect(
      (sdk.python as unknown as { createProject: (a: object) => Promise<unknown> }).createProject({}),
    ).rejects.toBeInstanceOf(PythonSdkBridgeError);
  });
});
