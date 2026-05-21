import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';

test.describe('Trace Explore — smoke', { tag: ['@t1-smoke', '@trace-explore'] }, () => {
  test('SDK-seeded trace appears in Logs and the panel renders its input/output', async ({
    opikTrace,
    project,
    page,
  }) => {
    const logs = new LogsPage(page);
    await logs.goto(project.id);
    await logs.waitForReady();

    // The Logs page shows the trace count for the project we just seeded one trace into.
    expect(await logs.countTraces()).toBe(1);

    // The trace row carries the trace id; assert the seeded trace is in the table.
    const idsInTable = await logs.readTraceIdsInOrder();
    expect(idsInTable).toContain(opikTrace.id);

    // Open the panel by URL (deep-link) and assert the seeded values render.
    const panel = await logs.openTraceById(opikTrace.id);
    await panel.waitForFullyLoaded();

    await expect(panel.traceNameInHeader(opikTrace.name)).toBeVisible();
    await expect(panel.inputSection).toBeVisible();
    await expect(panel.outputSection).toBeVisible();
    await expect(panel.inputValue(opikTrace.input)).toBeVisible();
    await expect(panel.outputValue(opikTrace.output)).toBeVisible();
    // @opik.track on a leaf function emits exactly one root span.
    await expect(panel.spansCountLabel(1)).toBeVisible();
  });

  test('breadcrumb shows the project name while the trace panel is open', async ({
    opikTrace,
    project,
    page,
  }) => {
    const logs = new LogsPage(page);
    await logs.goto(project.id);
    await logs.waitForReady();

    const panel = await logs.openTraceById(opikTrace.id);
    await panel.waitForFullyLoaded();

    // The Logs page breadcrumb shows the project name; the panel is an overlay
    // on the Logs page, so the breadcrumb remains visible.
    await expect(logs.breadcrumbProjectLink(project.name)).toBeVisible();
  });

  test('multiple traces appear in correct order in the Logs table', async ({
    project,
    sdkClient,
    testNamespace,
    page,
  }) => {
    // Seed three traces directly so this test does not depend on the
    // opikTrace fixture or any state from other tests.
    const first = await sdkClient.python.createTrace({
      project_name: project.name,
      name: `${testNamespace}-trace-first`,
      input: 'first input',
      output: 'first output',
    });
    await new Promise((r) => setTimeout(r, 50));
    const second = await sdkClient.python.createTrace({
      project_name: project.name,
      name: `${testNamespace}-trace-second`,
      input: 'second input',
      output: 'second output',
    });
    await new Promise((r) => setTimeout(r, 50));
    const third = await sdkClient.python.createTrace({
      project_name: project.name,
      name: `${testNamespace}-trace-third`,
      input: 'third input',
      output: 'third output',
    });

    const logs = new LogsPage(page);
    await logs.goto(project.id);
    await logs.waitForReady();

    expect(await logs.countTraces()).toBe(3);
    const ids = await logs.readTraceIdsInOrder();
    expect(ids).toEqual([third.id, second.id, first.id]);
  });
});
