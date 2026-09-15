import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import type { SdkClient } from '@e2e/core/sdk';
import type { BackendClient } from '@e2e/core/backend';

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

/** A trace seeded with a chain of nested spans, plus the decoration hung off it. */
interface NestedTrace {
  id: string;
  name: string;
  spanNames: string[];
  scoreName: string;
  traceCommentId: string;
  spanCommentId: string;
  /** Id of the deepest span in the chain — the one carrying the span-level rows. */
  deepestSpanId: string;
}

/**
 * Seed one trace whose spans form a CHAIN — each parented to the previous —
 * rather than a flat fan-out.
 *
 * Depth is the point. A cascade that only reached the trace's direct children
 * would leave the rest behind, and a flat seed could not tell the two apart.
 */
async function seedNestedTrace(
  sdkClient: SdkClient,
  projectName: string,
  namespace: string,
  label: string,
  depth: number,
): Promise<{ id: string; name: string; spanNames: string[] }> {
  const spanNames = Array.from({ length: depth }, (_, i) => `${namespace}-${label}-span-${i}`);
  const created = await sdkClient.python.createNestedTrace({
    project_name: projectName,
    name: `${namespace}-${label}`,
    input: { question: label },
    output: { answer: label },
    spans: spanNames.map((name, i) => ({
      name,
      ...(i === 0 ? {} : { parent_index: i - 1 }),
    })),
  });
  if (created.span_count !== depth) {
    throw new Error(
      `[seedNestedTrace] expected ${depth} spans on '${label}', bridge reported ${created.span_count}`,
    );
  }
  return { id: created.id, name: created.name, spanNames };
}

/** Spans of one trace, once every one of them is queryable. */
async function awaitSpans(
  backendClient: BackendClient,
  projectId: string,
  traceId: string,
  expected: number,
) {
  await expect
    .poll(async () => (await backendClient.listSpanRefs({ projectId, traceId })).length, {
      timeout: 60_000,
      intervals: [500, 1_000, 2_000],
    })
    .toBe(expected);
  return backendClient.listSpanRefs({ projectId, traceId });
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

  /**
   * Deleting a trace has to take its spans with it (OPIK-7791).
   *
   * Both tests above seed SPAN-LESS traces, so the span cascade has never been
   * asserted anywhere in the estate — it runs only in fixture teardowns, where
   * nothing reads the result. That makes it exactly the kind of regression that
   * stays invisible: the trace disappears from the table either way, and
   * orphaned spans are only visible to whoever later wonders why the project's
   * storage never shrinks.
   *
   * Driven at both surfaces because the delete has two entry points that reach
   * the same service — `DELETE /v1/private/traces/{id}` and the Logs table's
   * bulk delete — and a cascade that broke on one of them would still look
   * healthy on the other.
   */
  test('Deleting a trace cascades to its nested spans, through the API and through the Logs table', { tag: ['@cap:traces.delete-traces-api', '@cap:traces.delete-traces'] }, async ({
    project,
    sdkClient,
    backendClient,
    testNamespace,
    page,
  }) => {
    test.setTimeout(180_000);

    const DOOMED_DEPTH = 5;
    const CONTROL_DEPTH = 4;

    const seeded = await test.step('Seed two 5-span traces to delete and a 4-span control that must survive', async () => {
      // The control is a bystander in the strict sense: nothing in this test
      // ever touches it. Without one, "the target's spans are gone" would be
      // satisfied just as well by a delete that took the whole project.
      const [api, ui, control] = await Promise.all([
        seedNestedTrace(sdkClient, project.name, testNamespace, 'doomed-api', DOOMED_DEPTH),
        seedNestedTrace(sdkClient, project.name, testNamespace, 'doomed-ui', DOOMED_DEPTH),
        seedNestedTrace(sdkClient, project.name, testNamespace, 'control', CONTROL_DEPTH),
      ]);
      return { api, ui, control };
    });

    const decorate = async (
      base: { id: string; name: string; spanNames: string[] },
      depth: number,
    ): Promise<NestedTrace> => {
      const spans = await awaitSpans(backendClient, project.id, base.id, depth);
      const deepest = spans.find((s) => s.name === base.spanNames[depth - 1]);
      expect(deepest, `the deepest span of '${base.name}' must be readable`).toBeDefined();

      const scoreName = `${base.name}-score`;
      const deepestSpanId = deepest!.id;

      // Feedback scores and comments on BOTH the trace and a span, so the
      // delete has dependent rows at both levels to clean up rather than just
      // the span rows themselves.
      await backendClient.addTraceFeedbackScore({ traceId: base.id, name: scoreName, value: 1 });
      await backendClient.addSpanFeedbackScore({ spanId: deepestSpanId, name: scoreName, value: 1 });

      return {
        ...base,
        deepestSpanId,
        scoreName,
        traceCommentId: await backendClient.addComment({
          entity: 'traces',
          entityId: base.id,
          text: `comment on ${base.name}`,
        }),
        spanCommentId: await backendClient.addComment({
          entity: 'spans',
          entityId: deepestSpanId,
          text: `comment on the deepest span of ${base.name}`,
        }),
      };
    };

    const traces = await test.step('Attach a feedback score and a comment to each trace and to its deepest span', async () => ({
      api: await decorate(seeded.api, DOOMED_DEPTH),
      ui: await decorate(seeded.ui, DOOMED_DEPTH),
      control: await decorate(seeded.control, CONTROL_DEPTH),
    }));

    await test.step('The seed really holds before anything is deleted', async () => {
      // A cascade assertion over a seed that never landed is a test that cannot
      // fail: every "it is gone" check would pass against state that was never
      // there. Assert the whole shape first, then delete.
      const all = await backendClient.listSpanRefs({ projectId: project.id });
      expect(all, 'the project holds every seeded span and nothing else').toHaveLength(
        DOOMED_DEPTH * 2 + CONTROL_DEPTH,
      );

      for (const [depth, trace] of [
        [DOOMED_DEPTH, traces.api],
        [DOOMED_DEPTH, traces.ui],
        [CONTROL_DEPTH, traces.control],
      ] as const) {
        const own = all.filter((s) => s.traceId === trace.id);
        expect(own.map((s) => s.name).sort(), `spans of '${trace.name}'`).toEqual(
          [...trace.spanNames].sort(),
        );
        // The chain, not just the count: one span hangs off the trace and every
        // other hangs off a span, which is what gives the cascade depth to lose.
        expect(
          own.filter((s) => s.parentSpanId === null),
          `'${trace.name}' must have exactly one root span`,
        ).toHaveLength(1);
        expect(
          own.filter((s) => s.parentSpanId !== null),
          `'${trace.name}' must nest its remaining ${depth - 1} spans`,
        ).toHaveLength(depth - 1);

        const detail = await backendClient.getTrace(trace.id);
        expect(detail, `'${trace.name}' must exist`).not.toBeNull();
        expect(
          detail!.feedbackScores.map((s) => s.name),
          `'${trace.name}' carries its trace-level score`,
        ).toContain(trace.scoreName);

        const span = await backendClient.getSpan(trace.deepestSpanId);
        expect(span, `the deepest span of '${trace.name}' must exist`).not.toBeNull();
        expect(
          span!.feedbackScores.map((s) => s.name),
          `the deepest span of '${trace.name}' carries its span-level score`,
        ).toContain(trace.scoreName);

        expect(
          await backendClient.getTraceComment(trace.id, trace.traceCommentId),
          `'${trace.name}' carries its trace comment`,
        ).not.toBeNull();
        expect(
          await backendClient.getSpanComment(trace.deepestSpanId, trace.spanCommentId),
          `the deepest span of '${trace.name}' carries its span comment`,
        ).not.toBeNull();
      }
    });

    await test.step('Deleting through the REST API removes the trace and every span under it', async () => {
      await backendClient.deleteTraces([traces.api.id]);
      await expect
        .poll(() => backendClient.getTrace(traces.api.id), {
          timeout: 60_000,
          intervals: [500, 1_000, 2_000],
        })
        .toBeNull();
      await expect
        .poll(
          async () =>
            (await backendClient.listSpanRefs({ projectId: project.id, traceId: traces.api.id }))
              .length,
          { timeout: 60_000, intervals: [500, 1_000, 2_000] },
        )
        .toBe(0);

      // And the rows hung off it. The seed attaches a comment at both levels
      // precisely so the cascade has dependents to reach; leaving them
      // unasserted would make seeding them prove nothing.
      expect(
        await backendClient.getTraceComment(traces.api.id, traces.api.traceCommentId),
        'the deleted trace must not still serve its own comment',
      ).toBeNull();
      expect(
        await backendClient.getSpanComment(traces.api.deepestSpanId, traces.api.spanCommentId),
        "the deleted trace's span comment must go with the span",
      ).toBeNull();
    });

    const logs = new LogsPage(page);

    await test.step('Open Logs: the second doomed trace and the control are listed', async () => {
      await logs.goto(project.id);
      await logs.waitForReady();
      await expect(logs.traceRows).toHaveCount(2);
      await expect(logs.traceRow(traces.ui.id)).toBeVisible();
      await expect(logs.traceRow(traces.control.id)).toBeVisible();
    });

    await test.step('Bulk-delete the second doomed trace from the table', async () => {
      await logs.selectTrace(traces.ui.id);
      await expect(logs.bulkDeleteButton).toBeEnabled();
      await logs.bulkDeleteSelected();
    });

    await test.step('Its row leaves the table without a reload and the control stays', async () => {
      // No page.reload() on purpose: the table has to drop the row on its own.
      await expect(logs.traceRow(traces.ui.id)).toHaveCount(0);
      await expect(logs.traceRow(traces.control.id)).toBeVisible();
      await expect(logs.traceRows).toHaveCount(1);
      await expect.poll(() => logs.countTraces()).toBe(1);
    });

    await test.step('The bulk delete cascaded to that trace\'s spans too', async () => {
      await expect
        .poll(
          async () =>
            (await backendClient.listSpanRefs({ projectId: project.id, traceId: traces.ui.id }))
              .length,
          { timeout: 60_000, intervals: [500, 1_000, 2_000] },
        )
        .toBe(0);

      expect(
        await backendClient.getTraceComment(traces.ui.id, traces.ui.traceCommentId),
        'the bulk-deleted trace must not still serve its own comment',
      ).toBeNull();
      expect(
        await backendClient.getSpanComment(traces.ui.deepestSpanId, traces.ui.spanCommentId),
        "the bulk-deleted trace's span comment must go with the span",
      ).toBeNull();
    });

    await test.step('Project-wide, exactly the control survives — trace, spans and its own rows', async () => {
      // The assertion the per-trace zeros cannot make on their own. Filtering
      // on a deleted trace_id matches nothing whether the cascade ran or merely
      // orphaned the spans, so the surviving set has to be named.
      const remaining = await backendClient.listSpanRefs({ projectId: project.id });
      expect(
        remaining.map((s) => s.name).sort(),
        'only the control trace may still own spans in this project',
      ).toEqual([...traces.control.spanNames].sort());
      expect(remaining, 'and no others').toHaveLength(CONTROL_DEPTH);

      expect(
        await backendClient.listTraceIds({ projectId: project.id }),
        'the control is the only trace left',
      ).toEqual([traces.control.id]);

      // The bystander is intact in substance, not just present: a delete that
      // over-reached could have taken its dependent rows while leaving the row
      // that renders in the table.
      const detail = await backendClient.getTrace(traces.control.id);
      expect(detail, 'the control trace must still exist').not.toBeNull();
      expect(
        detail!.feedbackScores.map((s) => s.name),
        'the control keeps its trace-level score',
      ).toContain(traces.control.scoreName);
      expect(
        await backendClient.getTraceComment(traces.control.id, traces.control.traceCommentId),
        'the control keeps its trace comment',
      ).not.toBeNull();
      expect(
        await backendClient.getSpanComment(
          traces.control.deepestSpanId,
          traces.control.spanCommentId,
        ),
        'the control keeps its span comment',
      ).not.toBeNull();
    });
  });
});
