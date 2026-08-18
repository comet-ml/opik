import { test as baseTest, expect as baseExpect } from './aged-experiment.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import type { ConversationTurnRef } from './conversation.fixture';

export interface ThreadEvaluationScoreRef {
  name: string;
  value: number;
  reason: string | null;
}

export interface ThreadEvaluationRef {
  /** The thread that was evaluated, and the project it lives in. */
  threadId: string;
  sourceProjectId: string;
  sourceProjectName: string;
  turns: ConversationTurnRef[];
  /** The metric's name and the constant it was told to return. */
  metricName: string;
  score: number;
  reason: string;
  /** Scores `evaluate_threads` reported back for the thread, in returned order. */
  scores: ThreadEvaluationScoreRef[];
  /** The project `evaluate_threads` wrote its `evaluation_task` trace into. */
  evalProjectId: string;
  evalProjectName: string;
  evalTraceIds: string[];
}

export interface ThreadEvaluationFixtures {
  threadEvaluation: ThreadEvaluationRef;
}

/** Not 0 or 1: a constant that cannot be confused with a default, a boolean
 *  coercion, or a pass/fail flag if it turns up somewhere unexpected. */
const FIXED_SCORE = 0.75;
const SCORE_REASON = 'fixed deterministic score';

/**
 * A conversation thread that has been through `opik.evaluation.evaluate_threads`
 * with a fixed-score conversation metric.
 *
 * The evaluation writes two things: a feedback score onto the source thread, and
 * an `evaluation_task` trace into a *separate* project it creates on the fly.
 * That second project is this fixture's to delete — it is not the `project`
 * fixture's project, so nothing else tears it down.
 *
 * The thread is confirmed present through the API before the evaluation runs.
 * `evaluate_threads` raises when its filter matches no thread, so without the
 * wait a slow aggregation would surface as an opaque bridge 500 rather than as
 * the eventual consistency it actually is.
 */
export const test = baseTest.extend<ThreadEvaluationFixtures>({
  threadEvaluation: async (
    { sdkClient, backendClient, conversation, testNamespace },
    use,
    testInfo,
  ) => {
    const metricName = `${testNamespace}-fixed`;
    const evalProjectName = `${testNamespace}-eval`;

    await baseExpect
      .poll(
        async () => {
          const { threads } = await backendClient.listThreads({
            projectId: conversation.projectId,
            filters: [{ field: 'id', type: 'string', operator: '=', value: conversation.threadId }],
          });
          return threads.length;
        },
        {
          timeout: 120_000,
          intervals: [1_000, 2_000, 5_000],
          message: `thread ${conversation.threadId} never aggregated — evaluate_threads would fail with "no threads found"`,
        },
      )
      .toBe(1);

    const evaluation = await sdkClient.python.evaluateThreads({
      project_name: conversation.projectName,
      filter_string: `id = "${conversation.threadId}"`,
      eval_project_name: evalProjectName,
      metric_name: metricName,
      score: FIXED_SCORE,
      reason: SCORE_REASON,
    });

    // One thread was selected by the filter, so one result is the only shape the
    // rest of this fixture can describe. Asserting it here rather than indexing
    // blindly means a filter that matched more (or nothing) fails naming the
    // cause, instead of surfacing later as an undefined thread id.
    baseExpect(
      evaluation.results,
      `evaluate_threads filtered to id = "${conversation.threadId}" must return exactly one result`,
    ).toHaveLength(1);

    const ref: ThreadEvaluationRef = {
      threadId: conversation.threadId,
      sourceProjectId: conversation.projectId,
      sourceProjectName: conversation.projectName,
      turns: conversation.turns,
      metricName,
      score: FIXED_SCORE,
      reason: SCORE_REASON,
      scores: evaluation.results[0].scores,
      evalProjectId: evaluation.eval_project_id,
      evalProjectName,
      evalTraceIds: evaluation.eval_trace_ids,
    };

    await testInfo.attach('opik.threadEvaluation', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    /** The eval project is created by `evaluate_threads`, not by the `project`
     *  fixture, so nothing cascades it away — delete it explicitly. */
    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteProject(ref.evalProjectId);
      } catch (err) {
        console.warn(`[threadEvaluation fixture] delete warning for ${evalProjectName}:`, err);
      }
    }
  },
});

export { expect } from './aged-experiment.fixture';
