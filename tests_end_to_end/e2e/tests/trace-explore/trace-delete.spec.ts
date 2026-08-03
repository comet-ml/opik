import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import type { SdkClient } from '../../core/sdk';

interface SeededTrace {
  id: string;
  name: string;
}

/** Seed `count` traces named <namespace>-trace-<i>, oldest first. */
async function seedTraces(
  sdkClient: SdkClient,
  projectName: string,
  namespace: string,
  count: number,
): Promise<SeededTrace[]> {
  const traces: SeededTrace[] = [];
  for (let i = 0; i < count; i++) {
    const created = await sdkClient.python.createTrace({
      project_name: projectName,
      name: `${namespace}-trace-${i}`,
      input: `input-${i}`,
      output: `output-${i}`,
    });
    traces.push({ id: created.id, name: created.name });
  }
  return traces;
}

test.describe('Trace deletion — multi-trace', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  test('Bulk-deleting traces from the Logs table removes them from the UI and the API', { tag: ['@cap:traces.delete-traces'] }, async ({
    project,
    sdkClient,
    backendClient,
    testNamespace,
    page,
  }) => {
    const traces = await test.step('Seed three traces via the Python SDK', async () =>
      seedTraces(sdkClient, project.name, testNamespace, 3));

    const [survivor, doomedA, doomedB] = traces;
    const logs = new LogsPage(page);

    await test.step('Open Logs and verify all three traces are listed', async () => {
      await logs.goto(project.id);
      await logs.waitForReady();
      await expect(logs.traceRows).toHaveCount(3);
    });

    await test.step('Select two traces and bulk-delete them', async () => {
      await logs.selectTrace(doomedA.id);
      await logs.selectTrace(doomedB.id);
      await expect(logs.bulkDeleteButton).toBeEnabled();
      await logs.bulkDeleteSelected();
    });

    await test.step('Verify the deleted rows are gone and the survivor remains', async () => {
      await expect(logs.traceRow(doomedA.id)).toHaveCount(0);
      await expect(logs.traceRow(doomedB.id)).toHaveCount(0);
      await expect(logs.traceRow(survivor.id)).toBeVisible();
      await expect(logs.traceRows).toHaveCount(1);
      await expect.poll(() => logs.countTraces()).toBe(1);
    });

    await test.step('Verify the deleted traces are gone from the API too', async () => {
      expect(await backendClient.getTrace(doomedA.id)).toBeNull();
      expect(await backendClient.getTrace(doomedB.id)).toBeNull();
      expect(await backendClient.getTrace(survivor.id)).not.toBeNull();
    });
  });

  test('Traces deleted through the API disappear from the Logs table', { tag: ['@cap:traces.delete-traces-api'] }, async ({
    project,
    sdkClient,
    backendClient,
    testNamespace,
    page,
  }) => {
    const traces = await test.step('Seed three traces via the Python SDK', async () =>
      seedTraces(sdkClient, project.name, testNamespace, 3));

    const [survivor, doomedA, doomedB] = traces;
    const logs = new LogsPage(page);

    await test.step('Open Logs and verify all three traces are listed', async () => {
      await logs.goto(project.id);
      await logs.waitForReady();
      await expect(logs.traceRows).toHaveCount(3);
    });

    await test.step('Delete two traces through the REST API', async () => {
      await backendClient.deleteTraces([doomedA.id, doomedB.id]);
      expect(await backendClient.getTrace(doomedA.id)).toBeNull();
      expect(await backendClient.getTrace(doomedB.id)).toBeNull();
    });

    await test.step('Reload Logs and verify only the survivor is rendered', async () => {
      await page.reload();
      await logs.waitForReady();
      await expect(logs.traceRow(doomedA.id)).toHaveCount(0);
      await expect(logs.traceRow(doomedB.id)).toHaveCount(0);
      await expect(logs.traceRow(survivor.id)).toBeVisible();
      await expect(logs.traceRows).toHaveCount(1);
      await expect.poll(() => logs.countTraces()).toBe(1);
    });
  });
});
