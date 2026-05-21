import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import '@e2e/matchers/register';

test.describe('Trace Explore — smoke', { tag: ['@t1-smoke', '@trace-explore'] }, () => {
  test('SDK-seeded trace appears in Logs and renders spans correctly', async ({
    opikTrace,
    project,
    backendClient,
    page,
  }) => {
    const logs = new LogsPage(page, backendClient);
    await logs.goto(project.id);
    await logs.waitForReady();

    expect(await logs.countTraces()).toBeGreaterThanOrEqual(1);

    const panel = await logs.openTraceById(opikTrace.id);
    await panel.waitForFullyLoaded();
    const spans = await panel.readSpans();

    expect(spans).toHaveAtLeastSpanCount(1);
    expect(spans).toHaveValidInput();
    expect(spans).toHaveValidOutput();
    expect(spans).toHaveNoErrors();
  });

  test('trace panel exposes project metadata via REST', async ({
    opikTrace,
    project,
    backendClient,
    page,
  }) => {
    const logs = new LogsPage(page, backendClient);
    await logs.goto(project.id);
    await logs.waitForReady();
    const panel = await logs.openTraceById(opikTrace.id);
    await panel.waitForFullyLoaded();
    const metadata = await panel.readMetadata();
    expect(metadata.projectName).toBe(project.name);
  });

  test('multiple traces appear in correct order in the Logs table', async ({
    opikTrace,
    sdkClient,
    project,
    backendClient,
    page,
  }) => {
    const second = await sdkClient.python.createTrace({
      project_name: project.name,
      name: `${opikTrace.name}-second`,
      input: 'second input',
      output: 'second output',
    });
    await new Promise((r) => setTimeout(r, 50));
    const third = await sdkClient.python.createTrace({
      project_name: project.name,
      name: `${opikTrace.name}-third`,
      input: 'third input',
      output: 'third output',
    });

    const logs = new LogsPage(page, backendClient);
    await logs.goto(project.id);
    await logs.waitForReady();

    expect(await logs.countTraces()).toBe(3);
    const ids = await logs.readTraceIdsInOrder();
    expect(ids).toEqual([third.id, second.id, opikTrace.id]);
  });
});
