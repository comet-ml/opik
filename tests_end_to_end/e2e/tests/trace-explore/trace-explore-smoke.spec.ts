import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';

test.describe('Trace Explore — smoke', { tag: ['@t1-smoke', '@trace-explore'] }, () => {
  test('Logs view shows seeded traces with correct count and ordering', async ({
    project,
    sdkClient,
    testNamespace,
    page,
  }) => {
    const { first, second, third } = await test.step(
      'Seed three traces via the Python SDK',
      async () => {
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
        return { first, second, third };
      },
    );

    await test.step('Open Logs and verify trace count and ordering', async () => {
      const logs = new LogsPage(page);
      await logs.goto(project.id);
      await logs.waitForReady();

      expect(await logs.countTraces()).toBe(3);
      expect(await logs.readTraceIdsInOrder()).toEqual([third.id, second.id, first.id]);
    });
  });

  test('Trace panel renders the seeded trace header, input, output, span count, and project breadcrumb', async ({
    opikTrace,
    project,
    page,
  }) => {
    const logs = new LogsPage(page);

    const panel = await test.step('Navigate to Logs and open the seeded trace', async () => {
      await logs.goto(project.id);
      await logs.waitForReady();
      const panel = await logs.openTraceById(opikTrace.id);
      await panel.waitForFullyLoaded();
      return panel;
    });

    await test.step('Verify trace panel header, input, and output', async () => {
      await expect(panel.traceNameInHeader(opikTrace.name)).toBeVisible();
      await expect(panel.inputSection).toBeVisible();
      await expect(panel.outputSection).toBeVisible();
      await expect(panel.inputValue(opikTrace.input)).toBeVisible();
      await expect(panel.outputValue(opikTrace.output)).toBeVisible();
    });

    await test.step('Verify span count and project breadcrumb', async () => {
      // @opik.track on a leaf function emits exactly one root span.
      await expect(panel.spansCountLabel(1)).toBeVisible();
      // Project metadata is visible in the breadcrumb of the underlying Logs page
      // that the panel overlays — verifying project context via UI, not REST.
      await expect(logs.breadcrumbProjectLink(project.name)).toBeVisible();
    });
  });
});
