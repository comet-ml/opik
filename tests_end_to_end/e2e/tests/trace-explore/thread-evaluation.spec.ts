import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import type { BackendFilter } from '@e2e/core/backend';

/**
 * Thread-level metrics via `opik.evaluation.evaluate_threads` (OPIK-7025).
 *
 * Two things this flow can get silently wrong, and nothing in the estate covers
 * either:
 *
 * 1. The `evaluation_task` trace the run writes into the evaluation project.
 *    Thread evaluation creates no experiment, and the Logs page hard-scopes both
 *    tabs to `source = sdk`, so a trace written as `source = experiment` renders
 *    the "No traces yet" empty state with no error anywhere — the run reports
 *    success and the evidence is invisible.
 * 2. The optional `trace_context_transform`. Attaching context to the wrong
 *    messages, or emitting `"context": null` for callers who never asked for it,
 *    both read as ordinary payloads.
 *
 * Deterministic by construction: the metric is a fixed-score
 * `ConversationThreadMetric` and the context comes from trace metadata the
 * fixture seeds itself, so no provider key and no model verdict is in the loop.
 */

/** The filter the Logs page itself sends (`generateLogsSourceFilter` in lib/filters.ts). */
const sourceFilter = (source: 'sdk' | 'experiment'): BackendFilter[] => [
  { field: 'source', type: 'string', operator: '=', value: source },
];

/** Messages of one role, in conversation order. */
const messagesOfRole = (
  conversation: Array<Record<string, unknown>>,
  role: 'user' | 'assistant',
): Array<Record<string, unknown>> => conversation.filter((m) => m.role === role);

test.describe('Thread evaluation — CUJ', { tag: ['@t2-cuj', '@area:threads'] }, () => {
  test('evaluate_threads scores the thread and leaves a visible evaluation_task trace in the eval project', { tag: ['@cap:threads.thread-level-metrics', '@cap:threads.thread-feedback-score'] }, async ({
    evaluatedThread,
    backendClient,
    page,
  }) => {
    test.setTimeout(300_000);

    const { plainRun, metric, threadId, projectId } = evaluatedThread;

    await test.step('The run reports the metric it was given', async () => {
      expect(plainRun.scores, 'one metric in, one score out').toHaveLength(1);
      expect(plainRun.scores[0].name).toBe(metric.name);
      expect(plainRun.scores[0].value).toBeCloseTo(metric.value, 6);
      expect(plainRun.scores[0].reason).toBe(metric.reason);
    });

    await test.step('The score lands on the source THREAD over the API', async () => {
      // Feedback-score writes are eventually consistent, so poll for the score
      // rather than reading once. Compared as the whole score set: a run that
      // also wrote scores it should not have is exactly the regression a
      // find()-then-compare would pass straight through.
      await expect
        .poll(
          async () => {
            const thread = await backendClient.getThread({ projectId, threadId });
            return thread.feedbackScores.map((s) => ({ name: s.name, value: s.value }));
          },
          { timeout: 60_000, intervals: [1_000, 2_000, 5_000] },
        )
        .toEqual([{ name: metric.name, value: metric.value }]);
    });

    await test.step('The evaluation_task trace is entitled to the Logs view', async () => {
      // The load-bearing pair. Before OPIK-7025 this trace was written as
      // source=experiment, and thread evaluation creates no experiment for the
      // UI to reach it through — so it existed and was unreachable. Asserting
      // both halves is what makes that distinguishable: the sdk-scoped read
      // must return this trace and ONLY this trace, and the experiment-scoped
      // read must return nothing at all.
      const visibleToLogs = await backendClient.listTraceIds({
        projectId: plainRun.evalProjectId,
        filters: sourceFilter('sdk'),
      });
      expect(
        visibleToLogs,
        'the Logs page scopes to source=sdk; this is the exact set it would render',
      ).toEqual([plainRun.evalTraceId]);

      const strandedAsExperiment = await backendClient.listTraceIds({
        projectId: plainRun.evalProjectId,
        filters: sourceFilter('experiment'),
      });
      expect(
        strandedAsExperiment,
        'nothing may be left behind as source=experiment — the UI cannot reach it',
      ).toEqual([]);
    });

    await test.step('The thread panel shows the metric under Feedback scores', async () => {
      const logs = new LogsPage(page);
      await logs.gotoThreads(projectId);
      await logs.waitForThreadsReady(threadId);
      const panel = await logs.openThreadById(threadId);
      await panel.waitForFullyLoaded();

      // The Threads table hides feedback-score columns by default, so the
      // panel's own tab is where a user actually reads a thread-level score.
      await panel.openFeedbackScoresTab();
      await expect(panel.feedbackScoreRow(metric.name)).toHaveCount(1);
      expect(await panel.readFeedbackScoreValue(metric.name)).toBeCloseTo(metric.value, 6);
    });

    await test.step('The eval project lists exactly the one evaluation_task trace', async () => {
      const logs = new LogsPage(page);
      await logs.goto(plainRun.evalProjectId);
      await logs.waitForReady();
      // Both halves matter: the row is what regressed to invisible, and the
      // count is what would still read "1" if a different trace had rendered.
      await expect(logs.traceRow(plainRun.evalTraceId)).toHaveCount(1);
      expect(await logs.countTraces()).toBe(1);
    });
  });

  test('trace_context_transform attaches context to assistant messages only, and omits the key entirely when not passed', { tag: ['@cap:threads.thread-level-metrics'] }, async ({
    evaluatedThread,
    backendClient,
    page,
  }) => {
    test.setTimeout(300_000);

    const { plainRun, contextRun, turns } = evaluatedThread;
    const expectedContexts = turns.map((t) => [t.document]);

    await test.step('Both runs built the same conversation shape', async () => {
      // Each turn contributes a user message and an assistant message. Without
      // this the context assertions below could pass over a conversation that
      // silently lost turns.
      for (const [label, run] of [
        ['plain', plainRun],
        ['context', contextRun],
      ] as const) {
        expect(run.conversation, `${label} run conversation length`).toHaveLength(
          turns.length * 2,
        );
        expect(
          run.conversation.map((m) => m.role),
          `${label} run alternates user/assistant per turn`,
        ).toEqual(turns.flatMap(() => ['user', 'assistant']));
        expect(
          run.conversation.map((m) => m.content),
          `${label} run carries each turn's own question and answer`,
        ).toEqual(turns.flatMap((t) => [t.question, t.answer]));
      }
    });

    await test.step('Without the transform, no message carries a context KEY at all', async () => {
      // `in`, not a null check. The SDK serializes with exclude_none, so the
      // contract for a caller who never passed a transform is that the key is
      // absent — `"context": null` would be a new field appearing in every
      // existing caller's payload, and a `!= null` assertion would not see it.
      for (const [index, message] of plainRun.conversation.entries()) {
        expect(
          Object.keys(message),
          `plain-run message ${index} (${message.role}) must have no context key`,
        ).not.toContain('context');
      }
    });

    await test.step('With the transform, every assistant message carries its OWN turn context', async () => {
      const assistantMessages = messagesOfRole(contextRun.conversation, 'assistant');
      expect(assistantMessages).toHaveLength(turns.length);
      expect(
        assistantMessages.map((m) => m.context),
        'assistant contexts, in turn order, each the document its own trace logged',
      ).toEqual(expectedContexts);

      for (const [index, message] of messagesOfRole(
        contextRun.conversation,
        'user',
      ).entries()) {
        expect(
          Object.keys(message),
          `user message ${index} must not be given context — it is the agent's answer that is grounded`,
        ).not.toContain('context');
      }
    });

    await test.step('The same is true of the evaluation traces read back over the API', async () => {
      const readConversation = async (traceId: string, label: string) => {
        const trace = await backendClient.getTrace(traceId);
        expect(trace, `${label} evaluation trace exists`).not.toBeNull();
        const input = trace!.input;
        expect(input, `${label} evaluation trace carries an input`).not.toBeNull();
        const conversation = input!.conversation;
        expect(
          Array.isArray(conversation),
          `${label} evaluation trace input carries a conversation array`,
        ).toBe(true);
        return conversation as Array<Record<string, unknown>>;
      };

      const plainConversation = await readConversation(plainRun.evalTraceId, 'plain');
      for (const [index, message] of plainConversation.entries()) {
        expect(
          Object.keys(message),
          `plain-run trace message ${index} must have no context key`,
        ).not.toContain('context');
      }

      const contextConversation = await readConversation(contextRun.evalTraceId, 'context');
      expect(
        messagesOfRole(contextConversation, 'assistant').map((m) => m.context),
        'the context survives the round-trip into the evaluation trace input',
      ).toEqual(expectedContexts);
    });

    await test.step('The trace detail panel renders the context, and only where it was attached', async () => {
      const document = turns[0].document;
      const answer = turns[0].answer;

      const contextLogs = new LogsPage(page);
      await contextLogs.goto(contextRun.evalProjectId);
      const contextPanel = await contextLogs.openTraceById(contextRun.evalTraceId);
      await contextPanel.waitForFullyLoaded();
      await expect(contextPanel.panelText(document)).toHaveCount(1);

      const plainLogs = new LogsPage(page);
      await plainLogs.goto(plainRun.evalProjectId);
      const plainPanel = await plainLogs.openTraceById(plainRun.evalTraceId);
      await plainPanel.waitForFullyLoaded();
      // Gate on the conversation having rendered before claiming the document
      // is absent — otherwise this passes on a panel that simply had not
      // painted yet, which is the same failure it exists to rule out.
      await expect(plainPanel.panelText(answer).first()).toBeVisible();
      await expect(plainPanel.panelText(document)).toHaveCount(0);
    });
  });
});
