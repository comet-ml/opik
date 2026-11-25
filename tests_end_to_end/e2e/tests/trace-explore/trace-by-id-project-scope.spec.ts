import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import { displayedCost } from '@e2e/core/money';
import type { DuplicateTraceIdProjectSeed } from '@e2e/fixtures';
import type { BackendClient, TraceAggregates } from '@e2e/core/backend';

/**
 * `GET /v1/private/traces/{id}` for a trace id that exists in TWO projects
 * reports only the returned project's spans, cost, usage, counts, providers,
 * trace scores, span scores, comments and guardrails (opik#9643 / issue-7473).
 *
 * `TraceDAO.SELECT_BY_IDS` resolves the trace row to one project —
 * `ORDER BY (workspace_id, project_id, id) DESC LIMIT 1`, so the HIGHEST
 * `project_id` wins — but its nine joined subqueries used to group on the trace
 * id alone. Cost, usage, span counts, `llm_span_count`, `has_tool_spans` and
 * `providers` were therefore summed across every project sharing the id, and
 * comments, feedback scores, span feedback scores and guardrails were joined on
 * the id alone (the comments and guardrails subqueries also deduped
 * `LIMIT 1 BY id` before grouping, so one project's row could displace the
 * other's outright). The fix groups and joins each of them on
 * `(id, project_id)`.
 *
 * This is the one state the estate could not reach. Trace ids are minted by the
 * client, so a replayed job with `OPIK_PROJECT_NAME` changed, a reconfigured
 * OTel collector or a copied notebook produces it, and nothing refuses the
 * second write. The failure mode is silent: a cross-project roll-up renders as
 * a perfectly ordinary number on the panel every user opens all day.
 *
 * ## Why this key and not `traces.guardrail-results`
 *
 * The exploration candidate proposed `traces.guardrail-results`, on the grounds
 * that this is the only reachable assertion on the guardrails join. That key
 * names a UI surface — "GuardrailsCell column + Guardrails tree block", behind
 * `GUARDRAILS_ENABLED` — and this spec asserts the guardrail in the API payload
 * and nowhere else, so claiming it would record a widget nobody has tested as
 * tested for good. The guardrail here is one of nine aggregates, asserted
 * because this release re-keyed its subquery; it is not coverage of the
 * guardrails UI.
 *
 * ## How the seed makes a leak visible
 *
 * See `duplicateTraceIdProjects`. The DECOY project — the one the read must not
 * describe — deliberately holds the larger numbers: more spans, a higher cost,
 * more usage, two providers the other lacks and the only `tool` span. So any
 * leak shows up as an inflated total, an extra span, an unexpected provider or
 * `has_tool_spans` flipping to true, rather than hiding behind the bigger number
 * being the right one. One trace-score name and one span-score name are written
 * at DIFFERENT values in both projects: `span_feedback_scores` is an average per
 * score name over the trace's spans, so a leaked span score surfaces as the mean
 * of the two rather than as an extra row, which no row-count assertion would
 * catch.
 *
 * ## Why the UI leg opens the trace from the RETURNED project
 *
 * Exploration found that opening the shared id from the DECOY project's Logs
 * page renders that project's spans under the other project's header and total —
 * the panel's two halves disagree, because the tree is project-scoped by the
 * route while the header comes from the by-id read. That is the documented
 * consequence of by-id resolving one project (the PR's own test asserts it), not
 * a behaviour anyone has decided the panel should have, so it is reported rather
 * than pinned here. From the returned project the two halves agree, and that
 * agreement is what this spec asserts.
 */

/** The aggregate fields as one comparable object, for a whole-answer assertion. */
function aggregateShape(trace: TraceAggregates) {
  return {
    name: trace.name,
    projectId: trace.projectId,
    totalEstimatedCost: trace.totalEstimatedCost,
    usage: trace.usage,
    spanCount: trace.spanCount,
    llmSpanCount: trace.llmSpanCount,
    hasToolSpans: trace.hasToolSpans,
    providers: trace.providers,
  };
}

/** What `aggregateShape` must equal for one project's own copy of the trace. */
function expectedShape(seed: DuplicateTraceIdProjectSeed) {
  return {
    name: seed.traceName,
    projectId: seed.project.id,
    totalEstimatedCost: seed.expected.totalEstimatedCost,
    usage: seed.expected.usage,
    spanCount: seed.expected.spanCount,
    llmSpanCount: seed.expected.llmSpanCount,
    hasToolSpans: seed.expected.hasToolSpans,
    providers: seed.expected.providers,
  };
}

/** `name=value` per score, sorted, so a set compares whole. */
function scoreSet(scores: Array<{ name: string; value: number }> | null): string[] {
  expect(
    scores,
    'the read must carry a scores list — the API omits the field rather than sending an ' +
      'empty one, and a missing join is not the same answer as a trace with no scores',
  ).not.toBeNull();
  return scores!.map((score) => `${score.name}=${score.value}`).sort();
}

/** The by-id read, once it reports the project the resolver should pick. */
async function readByIdOnce(
  backendClient: BackendClient,
  traceId: string,
  expectedProjectId: string,
): Promise<TraceAggregates> {
  // Polled on the project id because the two copies land as separate ingests:
  // a read issued before the second write is queryable answers the FIRST write
  // quite correctly, which is indistinguishable from a resolver preferring the
  // wrong row. The assertions then re-read and compare the whole answer, so a
  // build that never converges still fails below, naming what it returned.
  await expect
    .poll(
      async () => (await backendClient.readTraceAggregatesById(traceId))?.projectId ?? null,
      {
        timeout: 120_000,
        intervals: [1_000, 2_000, 5_000],
        message:
          'the by-id read must resolve to the highest project_id — the other project means ' +
          'the resolver ordered the trace row differently',
      },
    )
    .toBe(expectedProjectId);
  const trace = await backendClient.readTraceAggregatesById(traceId);
  expect(trace, `trace ${traceId} must be readable by id`).not.toBeNull();
  return trace!;
}

test.describe(
  'Trace Explore — one trace id in two projects',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      'the by-id read and each project’s own listing report only that project’s aggregates',
      { tag: ['@cap:traces.trace-by-id-project-scope'] },
      async ({ duplicateTraceIdProjects, backendClient }) => {
        // Two projects, two trace writes, four span writes, six score writes,
        // two comments, two guardrails and their settle polls — each of which
        // may wait out a listing rate limit on staging.
        test.setTimeout(420_000);

        const { traceId, lower, higher, sharedScoreName, spanScoreName } =
          duplicateTraceIdProjects;

        const byId = await test.step('Read the shared id by id', async () =>
          readByIdOnce(backendClient, traceId, higher.project.id));

        await test.step('Its spans, cost, usage, counts and providers are the returned project’s', async () => {
          // Compared as one object rather than field by field: the subject is
          // that EVERY aggregate came from the same project, and a per-field
          // assertion chain stops at the first wrong one, hiding how far the
          // leak reached.
          expect(
            aggregateShape(byId),
            `the by-id read must describe '${higher.project.name}' alone — the decoy holds ` +
              `${lower.expected.spanCount} spans and ` +
              `$${lower.expected.totalEstimatedCost}, so any leak inflates these numbers`,
          ).toEqual(expectedShape(higher));
        });

        await test.step('Its trace scores are the returned project’s, at the returned project’s values', async () => {
          expect(
            scoreSet(byId.feedbackScores),
            `the shared score name must read ${higher.sharedScoreValue} and not ` +
              `${lower.sharedScoreValue}, and the decoy's own score name must be absent`,
          ).toEqual(
            [
              `${sharedScoreName}=${higher.sharedScoreValue}`,
              `${higher.uniqueScore.name}=${higher.uniqueScore.value}`,
            ].sort(),
          );
        });

        await test.step('Its span scores are the returned project’s, unaveraged', async () => {
          expect(
            scoreSet(byId.spanFeedbackScores),
            `span_feedback_scores averages per score name, so a leak here reads as ` +
              `${(higher.spanScoreValue + lower.spanScoreValue) / 2} rather than as a second row`,
          ).toEqual([`${spanScoreName}=${higher.spanScoreValue}`]);
        });

        await test.step('Its comment is the returned project’s only', async () => {
          // The comments subquery deduped `LIMIT 1 BY id` before grouping, so
          // this is where one project's comment could displace the other's
          // rather than merely join alongside it.
          expect(
            byId.comments,
            'the returned project wrote one comment and the decoy wrote another',
          ).toEqual([higher.comment]);
        });

        await test.step('Its guardrail is the returned project’s only', async () => {
          expect(
            byId.guardrailsValidations,
            'the guardrails subquery filters on the target project, so the decoy’s PII ' +
              'failure must not appear beside — or instead of — the returned project’s',
          ).toEqual([
            {
              spanId: higher.guardrail.secondaryId,
              checks: [{ name: higher.guardrail.name, result: higher.guardrail.result }],
            },
          ]);
        });

        await test.step('Each project’s own listing still reports its own aggregates', async () => {
          // The other half of the question, and the reason the test above is
          // not enough on its own: a join that had stopped aggregating
          // altogether would also satisfy "nothing leaked". The listing is
          // bounded to one project, so it is the only read that can say what
          // each project holds by itself.
          for (const seed of [lower, higher]) {
            const { total, trace } = await backendClient.findTraceAggregatesInProject({
              projectId: seed.project.id,
              traceId,
            });
            expect(
              total,
              `'${seed.project.name}' holds exactly the one trace this test wrote`,
            ).toBe(1);
            expect(trace, `'${seed.project.name}' lists its own copy of ${traceId}`).not.toBeNull();
            expect(
              aggregateShape(trace!),
              `'${seed.project.name}' reports its own aggregates when read within itself`,
            ).toEqual(expectedShape(seed));
            expect(
              scoreSet(trace!.feedbackScores),
              `'${seed.project.name}' reports its own trace scores when read within itself`,
            ).toEqual(
              [
                `${sharedScoreName}=${seed.sharedScoreValue}`,
                `${seed.uniqueScore.name}=${seed.uniqueScore.value}`,
              ].sort(),
            );
          }
        });
      },
    );

    test(
      'the panel opened from the returned project shows only its spans, cost, scores and comment',
      { tag: ['@cap:traces.trace-by-id-project-scope'] },
      async ({ duplicateTraceIdProjects, page }) => {
        test.setTimeout(420_000);

        const { traceId, lower, higher } = duplicateTraceIdProjects;

        const panel = await test.step('Open the shared id from the returned project’s Logs page', async () => {
          const logs = new LogsPage(page);
          await logs.goto(higher.project.id);
          const panel = await logs.openTraceById(traceId);
          await panel.waitForFullyLoaded();
          return panel;
        });

        await test.step('The header names the returned project’s trace', async () => {
          await expect(
            panel.traceNameInHeader(higher.traceName),
            'the by-id read resolves to the highest project_id, so this is its trace name',
          ).toBeVisible();
        });

        await test.step('The tree holds only the returned project’s span', async () => {
          await panel.waitForSpanTreeNodes([higher.spans[0].name]);
          // Read as the whole tree rather than as "the decoy's spans are
          // absent": an exhaustive map fails on an extra node, a missing node
          // and a re-parented one alike, where an absence check passes against
          // a tree that lost everything.
          expect(
            Object.keys(await panel.spanTreeDepths()).sort(),
            'the tree lists the trace root and this project’s one span, and none of the ' +
              `decoy's ${lower.spans.length}`,
          ).toEqual([higher.traceName, higher.spans[0].name].sort());
          await expect(
            panel.spansCountLabel(higher.expected.spanCount),
            'the Spans (n) label agrees with the tree',
          ).toBeVisible();
        });

        await test.step('The stats row shows the returned project’s cost, not the summed one', async () => {
          // The number a user reads to decide spend, and the one a
          // cross-project roll-up corrupts invisibly.
          await expect(
            panel.estimatedCost(displayedCost(higher.expected.totalEstimatedCost)),
            'the trace’s rolled-up cost is its own project’s',
          ).toBeVisible();
          await expect(
            panel.estimatedCost(displayedCost(lower.expected.totalEstimatedCost)),
            'and never the decoy’s',
          ).toHaveCount(0);
          await expect(
            panel.estimatedCost(
              displayedCost(
                higher.expected.totalEstimatedCost + lower.expected.totalEstimatedCost,
              ),
            ),
            'and never the two summed, which is what the unscoped spans_agg reported',
          ).toHaveCount(0);
        });

        await test.step('The Annotate panel shows this project’s scores and comment only', async () => {
          await panel.openAnnotate();
          await expect
            .poll(() => panel.readFeedbackScoreTagValue(duplicateTraceIdProjects.sharedScoreName), {
              message: 'the shared score name renders this project’s value',
            })
            .toBe(String(higher.sharedScoreValue));
          await expect(
            panel.feedbackScoreTag(higher.uniqueScore.name),
            'this project’s own score name is on screen',
          ).toHaveCount(1);
          await expect(
            panel.feedbackScoreTag(lower.uniqueScore.name),
            'the decoy’s score name is not',
          ).toHaveCount(0);
          await expect(
            panel.commentText(higher.comment),
            'this project’s comment is on screen',
          ).toBeVisible();
          await expect(
            panel.commentText(lower.comment),
            'the decoy’s comment is not',
          ).toHaveCount(0);
        });
      },
    );
  },
);
