import { test as baseTest } from './alert.fixture';
import type { BackendClient } from '../core/backend';

/** One turn of a cohort conversation: a single trace carrying the thread id. */
export interface ThreadCohortTurnRef {
  traceId: string;
  input: string;
  output: string;
}

export interface ThreadCohortThreadRef {
  /** The `thread_id` the traces were logged under — what every read addresses. */
  threadId: string;
  /** The thread's own UUID — what `manual-evaluation/threads` addresses. */
  threadModelId: string;
  turns: ThreadCohortTurnRef[];
}

export interface ThreadCohortRef {
  projectId: string;
  projectName: string;
  /** In seed order, so a spec can talk about "the second thread" reproducibly. */
  threads: ThreadCohortThreadRef[];
}

export interface ThreadCohortFixtures {
  threadCohort: ThreadCohortRef;
}

/**
 * Three distinct conversations, not one — the whole point is that a request
 * naming several threads has several threads to get wrong.
 */
const COHORT_SIZE = 3;

/** Two turns each: enough that a thread is a conversation rather than a trace. */
const TURNS = [
  { input: 'What is the capital of France?', output: 'The capital of France is Paris.' },
  { input: 'What is its population?', output: 'Paris has about 2.1 million residents.' },
] as const;

/**
 * A cohort of independent threads in one project, each resolved to the thread
 * model id that thread-addressed writes take.
 *
 * Exists because the estate's other thread fixture (`conversation`) seeds a
 * single thread, and a single thread cannot distinguish a fan-out that split
 * correctly from one that collapsed to its first entry — with N = 1 those are
 * the same observation.
 *
 * Seeded through the public SDK bridge, one trace per turn, with a gap between
 * turns so their chronological order is deterministic (turn order is derived
 * from start_time, and two calls can otherwise land in the same millisecond).
 *
 * The model-id resolution is a precondition, not a convenience: thread rows are
 * aggregated asynchronously from trace ingestion, so this polls until all three
 * exist and throws naming what is missing if they never do. A spec handed a
 * short cohort would assert over whatever did arrive and pass having tested a
 * smaller fan-out than it claims.
 *
 * No teardown — the traces, the threads and their scores all live in `project`,
 * whose own fixture deletes it.
 */
export const test = baseTest.extend<ThreadCohortFixtures>({
  threadCohort: async ({ sdkClient, backendClient, project, testNamespace }, use, testInfo) => {
    const threadIds = Array.from(
      { length: COHORT_SIZE },
      (_, i) => `${testNamespace}-thread-${i + 1}`,
    );

    const turnsByThread = new Map<string, ThreadCohortTurnRef[]>();
    for (const threadId of threadIds) {
      const turns: ThreadCohortTurnRef[] = [];
      for (let i = 0; i < TURNS.length; i++) {
        const { input, output } = TURNS[i];
        const created = await sdkClient.python.createTrace({
          project_name: project.name,
          name: `${threadId}-turn-${i + 1}`,
          input,
          output,
          thread_id: threadId,
        });
        turns.push({ traceId: created.id, input, output });
        await new Promise((r) => setTimeout(r, 50));
      }
      turnsByThread.set(threadId, turns);
    }

    const modelIds = await resolveThreadModelIds(backendClient, project.id, threadIds);

    const ref: ThreadCohortRef = {
      projectId: project.id,
      projectName: project.name,
      threads: threadIds.map((threadId) => ({
        threadId,
        threadModelId: modelIds.get(threadId)!,
        turns: turnsByThread.get(threadId)!,
      })),
    };

    await testInfo.attach('opik.threadCohort', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);
  },
});

/**
 * Poll the Threads list until every seeded thread has been aggregated, and
 * return its model id. Throws — rather than returning a partial map — because
 * an unresolved thread is a broken precondition, and the only honest thing a
 * fixture can do with one is refuse to hand it over.
 */
async function resolveThreadModelIds(
  backendClient: BackendClient,
  projectId: string,
  threadIds: string[],
  timeoutMs = 60_000,
): Promise<Map<string, string>> {
  const deadline = Date.now() + timeoutMs;
  let seen: string[] = [];
  for (;;) {
    const { threads } = await backendClient.listThreads({ projectId });
    const resolved = new Map<string, string>();
    for (const row of threads) {
      if (threadIds.includes(row.id) && row.threadModelId) {
        resolved.set(row.id, row.threadModelId);
      }
    }
    if (resolved.size === threadIds.length) return resolved;
    seen = threads.map((t) => t.id);
    if (Date.now() >= deadline) {
      const missing = threadIds.filter((id) => !resolved.has(id));
      throw new Error(
        `threadCohort fixture: ${missing.length} of ${threadIds.length} threads never got a ` +
          `thread_model_id within ${timeoutMs}ms (missing: ${missing.join(', ')}; ` +
          `project reported: ${seen.join(', ') || '<none>'})`,
      );
    }
    await new Promise((r) => setTimeout(r, 2_000));
  }
}

export { expect } from './alert.fixture';
