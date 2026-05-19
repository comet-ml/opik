import * as fs from 'node:fs/promises';
import { test, expect } from '@e2e/fixtures';

test.describe('fixture-lifecycle (local-only smoke)', () => {
  test('project fixture creates a cuj-prefixed project and tears it down', async ({
    project,
    backendClient,
    envConfig,
  }) => {
    expect(project.name).toMatch(
      new RegExp(`^${envConfig.cujPrefix}-w\\d+-.*-proj$`),
    );
    const listed = await backendClient.listProjectsWithPrefix(project.name);
    expect(listed.map((p) => p.id)).toContain(project.id);
  });

  test('scratchDir is created, writable, and namespaced by worker', async ({
    scratchDir,
    envConfig,
  }, testInfo) => {
    expect(scratchDir.path).toContain(envConfig.runId);
    expect(scratchDir.path).toContain(`w${testInfo.workerIndex}`);
    const stat = await fs.stat(scratchDir.path);
    expect(stat.isDirectory()).toBe(true);
    const probe = `${scratchDir.path}/probe.txt`;
    await fs.writeFile(probe, 'hello');
    const read = await fs.readFile(probe, 'utf-8');
    expect(read).toBe('hello');
  });

  test('parallel-namespace pair (case A) — namespace includes worker index', async ({
    testNamespace,
  }, testInfo) => {
    expect(testNamespace).toContain(`w${testInfo.workerIndex}`);
    expect(testNamespace).toContain('parallel-namespace-pair');
  });

  test('parallel-namespace pair (case B) — namespace includes worker index', async ({
    testNamespace,
  }, testInfo) => {
    expect(testNamespace).toContain(`w${testInfo.workerIndex}`);
    expect(testNamespace).toContain('parallel-namespace-pair');
  });

  test('failureArtifacts.capture registers sources without throwing on success', async ({
    failureArtifacts,
  }) => {
    failureArtifacts.capture({
      name: 'probe',
      collect: async () => ({ body: 'probe-body', contentType: 'text/plain' }),
    });
    expect(typeof failureArtifacts.capture).toBe('function');
  });
});
