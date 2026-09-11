import { test as baseTest } from './aged-experiment.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

/** One RAG-shaped turn: a question, its answer, and the document it was grounded on. */
export interface EvaluatedThreadTurnRef {
  traceId: string;
  question: string;
  answer: string;
  /** Logged as `metadata.retrieved_docs` on this turn's trace. */
  document: string;
}

/** One `evaluate_threads` run and everything a spec needs to assert about it. */
export interface ThreadEvaluationRunRef {
  /** Project the run's `evaluation_task` trace was written into. */
  evalProjectName: string;
  evalProjectId: string;
  /** The single `evaluation_task` trace the run produced. */
  evalTraceId: string;
  scores: Array<{ name: string; value: number; reason: string | null }>;
  /** The conversation the metric's `score()` received, verbatim. */
  conversation: Array<Record<string, unknown>>;
}

export interface EvaluatedThreadRef {
  threadId: string;
  projectId: string;
  projectName: string;
  turns: EvaluatedThreadTurnRef[];
  /** Name and value the fixed-score metric reports on every run. */
  metric: { name: string; value: number; reason: string };
  /** `evaluate_threads` WITHOUT `trace_context_transform` — the pre-existing caller shape. */
  plainRun: ThreadEvaluationRunRef;
  /** `evaluate_threads` WITH `trace_context_transform` reading `metadata.retrieved_docs`. */
  contextRun: ThreadEvaluationRunRef;
}

export interface EvaluatedThreadFixtures {
  evaluatedThread: EvaluatedThreadRef;
}

/**
 * Three RAG-shaped turns. The documents are deliberately distinct and each
 * names a fact only its own turn could have used, so "every assistant message
 * carries its OWN trace's context" is a claim that can fail — with three
 * interchangeable documents it could not.
 */
const TURNS = [
  {
    question: 'What is the capital of France?',
    answer: 'The capital of France is Paris.',
    document: 'Paris has been the capital of France since the year 987.',
  },
  {
    question: 'What is its population?',
    answer: 'Paris has about 2.1 million residents.',
    document: 'The population of the city of Paris was 2,102,650 in 2023.',
  },
  {
    question: 'Name a famous landmark there.',
    answer: 'The Eiffel Tower is its most famous landmark.',
    document: 'The Eiffel Tower opened in 1889 and stands 330 metres tall.',
  },
] as const;

/** Key the turns log their retrieved documents under, and the key the transform reads. */
const CONTEXT_METADATA_KEY = 'retrieved_docs';
const INPUT_KEY = 'question';
const OUTPUT_KEY = 'answer';

/**
 * A fixed value, so the flow is assertable without a provider key or a model
 * verdict. Not 0 or 1: a metric that silently failed scores 0.0, and a
 * boolean-ish value would make a wrong-score regression indistinguishable
 * from a right one.
 */
const METRIC = { name: 'fixed_thread_score', value: 0.75, reason: 'fixed by the e2e metric' };

/**
 * A multi-turn conversation that has been scored by `evaluate_threads`, twice:
 * once without `trace_context_transform` and once with it, into two separate
 * evaluation projects.
 *
 * Both runs use the same fixed-score metric, so the source thread ends up
 * carrying exactly one feedback score (scores are keyed by name) and a spec can
 * assert on the whole set rather than looking its own score up in a crowd.
 *
 * Seeded through the public SDK bridge. The evaluation projects are created by
 * the SDK as a side effect of the run, so they are NOT covered by the `project`
 * fixture's cascade and are deleted here explicitly — `OPIK_LEAVE_FAILURES`
 * keeps them, along with everything else the run left behind.
 */
export const test = baseTest.extend<EvaluatedThreadFixtures>({
  evaluatedThread: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const threadId = `${testNamespace}-eval-thread`;

    const turns: EvaluatedThreadTurnRef[] = [];
    for (let i = 0; i < TURNS.length; i++) {
      const { question, answer, document } = TURNS[i];
      const created = await sdkClient.python.createNestedTrace({
        project_name: project.name,
        name: `${testNamespace}-turn-${i + 1}`,
        input: { [INPUT_KEY]: question },
        output: { [OUTPUT_KEY]: answer },
        // Where a RAG pipeline would log what it retrieved for this turn, and
        // what `trace_context_transform` is pointed at below.
        metadata: { [CONTEXT_METADATA_KEY]: [document] },
        thread_id: threadId,
        spans: [],
      });
      turns.push({ traceId: created.id, question, answer, document });
      if (i < TURNS.length - 1) {
        // Turn order is derived from start_time, so the turns need distinct
        // ones — without a gap two calls can land in the same millisecond.
        await new Promise((r) => setTimeout(r, 50));
      }
    }

    const runEvaluation = async (
      suffix: string,
      contextMetadataKey?: string,
    ): Promise<ThreadEvaluationRunRef> => {
      const evalProjectName = `${testNamespace}-${suffix}`;
      const result = await sdkClient.python.evaluateThreads({
        project_name: project.name,
        eval_project_name: evalProjectName,
        thread_id: threadId,
        trace_input_key: INPUT_KEY,
        trace_output_key: OUTPUT_KEY,
        metric_name: METRIC.name,
        score_value: METRIC.value,
        score_reason: METRIC.reason,
        ...(contextMetadataKey ? { context_metadata_key: contextMetadataKey } : {}),
      });

      const [evalProject] = await backendClient.listProjectsWithPrefix(evalProjectName);
      if (!evalProject) {
        throw new Error(
          `evaluated-thread fixture: evaluate_threads did not create project ${evalProjectName}`,
        );
      }

      // The run writes exactly one evaluation_task trace, and the specs assert
      // on it by id. Resolving it here (rather than in the test) keeps the
      // fixture's promise honest: if the trace the run is supposed to produce
      // is missing, the fixture fails instead of handing a spec a state it
      // cannot distinguish from a bug it was written to catch.
      const evalTraceIds = await backendClient.listTraceIds({ projectId: evalProject.id });
      if (evalTraceIds.length !== 1) {
        throw new Error(
          `evaluated-thread fixture: expected exactly 1 trace in ${evalProjectName}, got ${evalTraceIds.length}`,
        );
      }

      return {
        evalProjectName,
        evalProjectId: evalProject.id,
        evalTraceId: evalTraceIds[0],
        scores: result.scores,
        conversation: result.conversation,
      };
    };

    // Plain first: both runs write the same metric name onto the same thread,
    // and running them in a fixed order keeps which one wrote last knowable.
    const plainRun = await runEvaluation('eval-plain');
    const contextRun = await runEvaluation('eval-ctx', CONTEXT_METADATA_KEY);

    const ref: EvaluatedThreadRef = {
      threadId,
      projectId: project.id,
      projectName: project.name,
      turns,
      metric: { ...METRIC },
      plainRun,
      contextRun,
    };

    await testInfo.attach('opik.evaluatedThread', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      for (const run of [plainRun, contextRun]) {
        try {
          await backendClient.deleteProject(run.evalProjectId);
        } catch (err) {
          console.warn(
            `[evaluated-thread fixture] delete warning for ${run.evalProjectName}:`,
            err,
          );
        }
      }
    }
    // The conversation traces and the thread's score live in `project`, whose
    // own fixture deletes it.
  },
});

export { expect } from './aged-experiment.fixture';
