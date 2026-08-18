import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import type { BackendFilter } from '@e2e/core/backend';

/**
 * Thread-level metrics: `opik.evaluation.evaluate_threads` (OPIK-7025).
 *
 * The evaluation writes to two places. It scores the *source* thread, and it
 * writes one `evaluation_task` trace per thread into a separate evaluation
 * project — the record of what the metric saw and what it answered.
 *
 * That second write is only half-observable from the API. The Logs page reads
 * traces under `filters=[{field:"source",operator:"=",value:"sdk"}]`
 * (`v2/pages/LogsPage/TracesSpansTab.tsx`), so a trace written with any other
 * source is present to `GET /v1/private/traces` and invisible to every page a
 * user has. The failure renders as an ordinary "No traces yet" empty state over
 * a project that provably holds the trace, which is why this spec asserts the
 * page's own filter and the rendered table rather than an unfiltered read: an
 * API-only assertion that does not replicate the filter passes either way.
 *
 * Deterministic without a provider key: the bridge scores from a fixed-value
 * conversation metric, so there is no judge, no wall-clock and no LLM output
 * anywhere in the assertions.
 */

/** The Logs page's own read. Untyped `source`, exactly as the FE sends it. */
const sourceFilter = (value: 'sdk' | 'experiment'): BackendFilter[] => [
  { field: 'source', operator: '=', value },
];

/** The trace name `evaluate_threads` hard-codes for its evaluation record. */
const EVAL_TRACE_NAME = 'evaluation_task';

test.describe('Thread evaluation — CUJ', { tag: ['@t2-cuj', '@area:threads'] }, () => {
  test('evaluate_threads writes an evaluation_task trace the Logs page can see', { tag: ['@cap:threads.thread-level-metrics'] }, async ({
    threadEvaluation,
    backendClient,
    page,
  }) => {
    const evalTraceId = await test.step('The evaluation wrote exactly one evaluation trace', async () => {
      // The count matters as much as the id: one `evaluation_task` trace per
      // evaluated thread is the contract, and a second one would mean the
      // evaluation ran twice over a thread it was filtered down to once.
      expect(
        threadEvaluation.evalTraceIds,
        'one evaluated thread must produce exactly one evaluation trace',
      ).toHaveLength(1);
      return threadEvaluation.evalTraceIds[0];
    });

    await test.step('The evaluation project holds that trace and nothing else', async () => {
      // Unfiltered, so this is the "the trace exists" half of the differential
      // below — asserted as the whole collection, not as a lookup, so a stray
      // extra trace in the eval project fails here.
      const allTraceIds = await backendClient.listTraceIds({
        projectId: threadEvaluation.evalProjectId,
      });
      expect(allTraceIds, 'the evaluation project holds exactly the evaluation trace').toEqual([
        evalTraceId,
      ]);

      const trace = await backendClient.getTrace(evalTraceId);
      expect(trace, `evaluation trace ${evalTraceId} is readable`).not.toBeNull();
      expect(trace!.name, 'the evaluation trace carries the name evaluate_threads writes').toBe(
        EVAL_TRACE_NAME,
      );
    });

    await test.step('The trace is visible under the Logs page filter, and is not an experiment trace', async () => {
      // The regression this spec exists for. `source` is not a rendered column,
      // so nothing else in the estate would notice it flipping.
      const visibleToLogs = await backendClient.listTraceIds({
        projectId: threadEvaluation.evalProjectId,
        filters: sourceFilter('sdk'),
      });
      expect(
        visibleToLogs,
        'the Logs page reads source=sdk — a trace outside that filter is invisible to every page a user has',
      ).toEqual([evalTraceId]);

      const asExperimentTrace = await backendClient.listTraceIds({
        projectId: threadEvaluation.evalProjectId,
        filters: sourceFilter('experiment'),
      });
      expect(
        asExperimentTrace,
        'thread evaluation creates no experiment, so an experiment-sourced trace here is unreachable by design',
      ).toEqual([]);
    });

    await test.step('The evaluation project Logs page lists the trace', async () => {
      const logs = new LogsPage(page);
      await logs.goto(threadEvaluation.evalProjectId);
      await logs.waitForReady();

      await expect(
        logs.traceRow(evalTraceId),
        'the evaluation trace is one row of the Logs table',
      ).toHaveCount(1);
      await expect(logs.traceRows, 'and it is the only row').toHaveCount(1);
      // The Traces count card, which reads the same filtered endpoint the table
      // does — it rendered blank for a project holding an unreachable trace.
      expect(await logs.countTraces(), 'the Traces count card agrees with the table').toBe(1);
    });

    await test.step('Opening the trace shows the conversation the metric scored', async () => {
      const logs = new LogsPage(page);
      await logs.goto(threadEvaluation.evalProjectId);
      await logs.waitForReady();
      const panel = await logs.openTraceById(evalTraceId);
      await panel.waitForFullyLoaded();

      await expect(panel.traceNameInHeader(EVAL_TRACE_NAME)).toBeVisible();
      for (const turn of threadEvaluation.turns) {
        await expect(
          panel.panelText(turn.input),
          `the panel renders the turn "${turn.input}"`,
        ).toBeVisible();
        await expect(
          panel.panelText(turn.output),
          `the panel renders the answer "${turn.output}"`,
        ).toBeVisible();
      }
    });

    await test.step('The stored conversation is every turn, in order, as user/assistant pairs', async () => {
      // The panel renders the input as YAML, so the assertion on *ordering* is
      // made against the stored payload — the same bytes the panel formats, and
      // the exact shape a conversation metric is handed.
      const trace = await backendClient.getTrace(evalTraceId);
      expect(trace!.input, 'the evaluation trace stores what the metric was given').not.toBeNull();
      expect(trace!.input!.conversation).toEqual(
        threadEvaluation.turns.flatMap((turn) => [
          { role: 'user', content: turn.input },
          { role: 'assistant', content: turn.output },
        ]),
      );
    });
  });

  test('the metric score lands on the source thread and renders in its Feedback scores tab', { tag: ['@cap:threads.thread-feedback-score'] }, async ({
    threadEvaluation,
    backendClient,
    page,
  }) => {
    await test.step('evaluate_threads reported the fixed score for the thread', async () => {
      expect(threadEvaluation.scores, 'one metric produces one score').toHaveLength(1);
      expect(threadEvaluation.scores[0]).toEqual({
        name: threadEvaluation.metricName,
        value: threadEvaluation.score,
        reason: threadEvaluation.reason,
      });
    });

    await test.step('The score is stored on the thread itself', async () => {
      // Returning the score and writing it back are separate steps in the SDK,
      // so the returned object is not evidence the thread was scored. Read the
      // thread as the Threads view does and assert the whole score list: an
      // extra score would mean the evaluation wrote somewhere it should not.
      const { threads } = await backendClient.listThreads({
        projectId: threadEvaluation.sourceProjectId,
        filters: [{ field: 'id', type: 'string', operator: '=', value: threadEvaluation.threadId }],
      });
      expect(threads, 'the evaluated thread is readable').toHaveLength(1);
      expect(threads[0].feedbackScores.map((s) => ({ name: s.name, value: s.value, reason: s.reason }))).toEqual([
        {
          name: threadEvaluation.metricName,
          value: threadEvaluation.score,
          reason: threadEvaluation.reason,
        },
      ]);
    });

    await test.step('The thread panel Feedback scores tab shows the score with its reason', async () => {
      const logs = new LogsPage(page);
      await logs.gotoThreads(threadEvaluation.sourceProjectId);
      await logs.waitForThreadsReady(threadEvaluation.threadId);
      const panel = await logs.openThreadById(threadEvaluation.threadId);
      await panel.waitForFullyLoaded();
      await panel.openFeedbackScoresTab();

      await expect(
        panel.feedbackScoreRow(threadEvaluation.metricName),
        'the metric has exactly one row in the thread scores table',
      ).toHaveCount(1);
      await expect(panel.feedbackScoreValueCell(threadEvaluation.metricName)).toHaveText(
        String(threadEvaluation.score),
      );
      await expect(panel.feedbackScoreReasonCell(threadEvaluation.metricName)).toHaveText(
        threadEvaluation.reason,
      );
    });
  });
});
