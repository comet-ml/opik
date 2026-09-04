import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import type { IdAgedTraceRef } from '@e2e/fixtures';

/**
 * A partial update must merge into the existing trace, whatever the age of the
 * instant its id embeds (OPIK-7456).
 *
 * `TraceService.update` resolves the row it is updating with a partial-by-id
 * read whose week bound is taken on the id. When that bound wrapped — as it did
 * for any id outside 1970-01-05 … 2149-06-06 — the resolve missed and the
 * update fell through to an insert, which is silent wrongness of the worst
 * kind: the trace stays listed, and only the fields the PATCH did not carry
 * have quietly gone.
 *
 * Three ages, because the wrap has two ends and one middle: an id in the epoch
 * week (below the 16-bit `Date` floor), one minted now (the ordinary case that
 * must not regress), and one in ~2201 (above the ceiling).
 *
 * The API half asserts what a partial update preserves; the UI half asserts
 * what a user would have seen instead — a trace panel whose name, input and
 * output had vanished after a tag was added.
 */
test.describe('Trace partial update — CUJ', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  test('a tag-only update on a trace with an out-of-band id keeps every other field', { tag: ['@cap:traces.update-trace-api', '@cap:traces.open-trace-panel'] }, async ({
    idAgedTraces,
    project,
    testNamespace,
    backendClient,
    page,
  }) => {
    const { epoch, present, farFuture } = idAgedTraces;
    const seeded: IdAgedTraceRef[] = [epoch, present, farFuture];
    const tag = `${testNamespace}-patched`;

    await test.step('PATCH each trace with a tag and nothing else', async () => {
      for (const trace of seeded) {
        await backendClient.updateTraceTags({
          traceId: trace.id,
          projectName: project.name,
          tags: [tag],
        });
      }
    });

    await test.step('Every other field survived the update, at all three id ages', async () => {
      for (const trace of seeded) {
        const payload = await backendClient.getTracePayload(trace.id);
        expect(payload, `${trace.name} is still readable after the update`).not.toBeNull();
        expect(payload!.name, `name of ${trace.name}`).toBe(trace.name);
        expect(payload!.input, `input of ${trace.name}`).toEqual(trace.input);
        expect(payload!.output, `output of ${trace.name}`).toEqual(trace.output);
        expect(payload!.metadata, `metadata of ${trace.name}`).toEqual(trace.metadata);
        // The update did land — otherwise "nothing changed" would satisfy every
        // assertion above.
        expect(payload!.tags, `tags of ${trace.name}`).toEqual([tag]);
      }
    });

    const logs = new LogsPage(page);

    await test.step('Each trace panel still renders its seeded name, input and output', async () => {
      await logs.goto(project.id);
      await logs.waitForReady();

      for (const trace of seeded) {
        const panel = await logs.openTraceById(trace.id);
        await panel.waitForFullyLoaded();
        await expect(panel.traceNameInHeader(trace.name)).toBeVisible();
        await expect(panel.inputValue(trace.input)).toBeVisible();
        await expect(panel.outputValue(trace.output)).toBeVisible();
        await expect(panel.tagChip(tag)).toBeVisible();
      }
    });
  });
});
