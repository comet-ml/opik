import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * Bulk-tagging from the Logs table must reach exactly the selected traces, and
 * must merge into what each of them already carried (OPIK-7791).
 *
 * The estate's only tag caller today is `updateTraceTags`, a single-trace
 * `PATCH /v1/private/traces/{id}`. The Manage shared tags dialog goes somewhere
 * else — `PATCH /v1/private/traces/batch`, whose trace -> project map is
 * resolved by `getProjectIdsByTraceIds` — and nothing in the suite has ever
 * driven it.
 *
 * Scope is the assertion, because scope is the half a screenshot cannot show.
 * Both ways this can be wrong leave a table that looks perfectly healthy:
 *
 *   over-reach  -> a bystander quietly gains a tag nobody asked for
 *   replace     -> a selected trace quietly loses the tag it already had
 *
 * So the seed gives all five traces a tag of their own, three non-contiguous
 * rows are selected (0, 1 and 3 — index 2 sits *inside* the selection's span
 * and must come back untouched), and every trace's tag list is read back whole
 * rather than searched for the new value.
 *
 * Written at both surfaces in the direction that catches a disagreement: the
 * write goes through the real dialog, the read-back through the API, and one
 * trace from each side is then re-read in the trace panel so a backend that
 * agreed with itself but not with the UI still fails.
 */
test.describe('Trace bulk tagging — CUJ', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  test('Bulk-tagging a selection tags exactly those traces and merges with their existing tags', { tag: ['@cap:traces.trace-tag-add-remove', '@cap:traces.open-trace-panel'] }, async ({
    bulkTagTraces,
    project,
    testNamespace,
    backendClient,
    page,
  }) => {
    test.setTimeout(180_000);

    const { all, selected, bystanders } = bulkTagTraces;
    const sharedTag = `${testNamespace}-shared`;

    await test.step('Every seeded trace really starts with exactly its own tag', async () => {
      // A merge assertion over a seed whose tags never landed is a test that
      // cannot fail: "trace 1 kept its own tag" would pass against a trace that
      // never had one. Assert the starting shape before touching the browser.
      for (const trace of all) {
        const payload = await backendClient.getTracePayload(trace.id);
        expect(payload, `${trace.name} must be readable before the bulk update`).not.toBeNull();
        expect(payload!.tags, `${trace.name} starts with exactly its own tag`).toEqual([
          trace.ownTag,
        ]);
      }
      expect(
        (await backendClient.listTraceIds({ projectId: project.id })).sort(),
        'the project holds the five seeded traces and nothing else',
      ).toEqual(all.map((t) => t.id).sort());
    });

    const logs = new LogsPage(page);

    await test.step('Open Logs and tick three of the five rows', async () => {
      await logs.goto(project.id);
      await logs.waitForReady();
      await expect(logs.traceRows).toHaveCount(all.length);

      for (const index of selected) {
        await logs.selectTrace(all[index].id);
      }
      await expect(logs.selectionCount(selected.length)).toBeVisible();
    });

    await test.step('Add a shared tag through the Manage shared tags dialog', async () => {
      await logs.addSharedTagToSelection(sharedTag, selected.length);
    });

    await test.step('Each selected trace carries the new tag AND the one it already had', async () => {
      for (const index of selected) {
        const trace = all[index];
        // Polled, not read once: the dialog closes on the mutation resolving,
        // which is not the same instant the analytics store answers with the
        // new row. Sorted whole-list equality, not toContain — a batch that
        // replaced rather than merged would still contain the new tag.
        await expect
          .poll(
            async () => {
              const payload = await backendClient.getTracePayload(trace.id);
              return payload === null ? null : [...(payload.tags ?? [])].sort();
            },
            {
              message: `tags of ${trace.name}`,
              timeout: 30_000,
              intervals: [500, 1_000, 2_000],
            },
          )
          .toEqual([trace.ownTag, sharedTag].sort());
      }
    });

    await test.step('Neither bystander changed, including the one inside the selection span', async () => {
      for (const index of bystanders) {
        const trace = all[index];
        const payload = await backendClient.getTracePayload(trace.id);
        expect(payload, `${trace.name} must still be readable`).not.toBeNull();
        expect(payload!.tags, `tags of ${trace.name}`).toEqual([trace.ownTag]);
      }
    });

    await test.step('Project-wide, exactly three traces carry the new tag', async () => {
      // The per-trace checks above cannot say this on their own: they name the
      // five traces the fixture seeded, and a batch that reached further would
      // still satisfy every one of them.
      const tagged: string[] = [];
      for (const id of await backendClient.listTraceIds({ projectId: project.id })) {
        const payload = await backendClient.getTracePayload(id);
        expect(payload, `trace ${id} must be readable`).not.toBeNull();
        if ((payload!.tags ?? []).includes(sharedTag)) tagged.push(id);
      }
      expect(tagged.sort(), 'only the selected traces carry the shared tag').toEqual(
        selected.map((i) => all[i].id).sort(),
      );
    });

    await test.step('The trace panel agrees with the API on both sides of the selection', async () => {
      const tagged = all[selected[0]];
      const untouched = all[bystanders[0]];

      const taggedPanel = await logs.openTraceById(tagged.id);
      await taggedPanel.waitForFullyLoaded();
      await expect(taggedPanel.tagChip(sharedTag)).toBeVisible();
      await expect(taggedPanel.tagChip(tagged.ownTag)).toBeVisible();

      const untouchedPanel = await logs.openTraceById(untouched.id);
      await untouchedPanel.waitForFullyLoaded();
      await expect(untouchedPanel.tagChip(untouched.ownTag)).toBeVisible();
      await expect(untouchedPanel.tagChip(sharedTag)).toHaveCount(0);
    });
  });
});
