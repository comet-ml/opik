import { test as baseTest } from './openai-responses-stub.fixture';

/** One seeded turn, and whether it was written as an internal call. */
export interface InternalTraceTurnRef {
  traceId: string;
  name: string;
  /** True when the trace carries `metadata._opik.is_internal: true`. */
  internal: boolean;
}

export interface InternalTraceThreadRef {
  /** The `thread_id` the turns were logged under. */
  threadId: string;
  /** The thread's row id — what a `scope: 'thread'` annotation queue takes. */
  threadModelId: string;
  /** Turns oldest-first, the order the Messages panel renders them in. */
  turns: InternalTraceTurnRef[];
  /**
   * `number_of_messages` as the backend reports it: a flat two per trace,
   * internal ones included. The panel subtracts from this, so it is the number
   * the UI must be shown to DISAGREE with — not a convenience.
   */
  rawMessageCount: number;
  /** The turns written as internal calls, oldest-first. */
  internalTurns: InternalTraceTurnRef[];
  /** The turns written as ordinary conversation, oldest-first. */
  plainTurns: InternalTraceTurnRef[];
}

export interface InternalTraceThreadsRef {
  projectId: string;
  projectName: string;
  /** Three turns, the middle one internal: one hidden turn among visible ones. */
  main: InternalTraceThreadRef;
  /**
   * Two turns, BOTH internal. Drives the two states a thread of only internal
   * calls can be in: nothing to render at all (the "only internal calls" empty
   * state), and exactly one trace kept by the URL with the other still hidden.
   */
  allInternal: InternalTraceThreadRef;
}

export interface InternalTraceThreadsFixtures {
  internalTraceThreads: InternalTraceThreadsRef;
}

/** The namespaced metadata container the SDK integrations write into. */
export const OPIK_METADATA_KEY = '_opik';
/** The flag inside it that marks a trace a helper call rather than a turn. */
export const IS_INTERNAL_KEY = 'is_internal';

/** Exactly what an integration writes on a title-generation call. */
const INTERNAL_METADATA = { [OPIK_METADATA_KEY]: { [IS_INTERNAL_KEY]: true } };
/**
 * A plain turn still carries metadata, and deliberately carries a `_opik`
 * container WITHOUT the flag. A rule that hid on "has `_opik` metadata", or on
 * "has any metadata", would be indistinguishable from the real one if the
 * visible turns had none — so the negative case has to be this specific.
 */
const PLAIN_METADATA = { [OPIK_METADATA_KEY]: { source: 'e2e-conversation' } };

/** The backend counts an input and an output for every trace in a thread. */
const MESSAGES_PER_TRACE = 2;

interface TurnSeed {
  question: string;
  answer: string;
  internal: boolean;
}

/**
 * Plain, distinct turns. The internal one reads as what it is — a helper call
 * whose text would be obviously wrong to show as a conversation turn — so a
 * failure screenshot says which trace leaked.
 */
const MAIN_TURNS: readonly TurnSeed[] = [
  {
    question: 'What is the capital of France?',
    answer: 'The capital of France is Paris.',
    internal: false,
  },
  {
    question: 'Summarise this conversation in three words.',
    answer: 'France capital questions',
    internal: true,
  },
  {
    question: 'What is its population?',
    answer: 'Paris has about 2.1 million residents.',
    internal: false,
  },
];

const ALL_INTERNAL_TURNS: readonly TurnSeed[] = [
  {
    question: 'Generate a title for this conversation.',
    answer: 'A chat about France',
    internal: true,
  },
  {
    question: 'Classify the intent of this conversation.',
    answer: 'geography_lookup',
    internal: true,
  },
];

/**
 * Two threads whose turns are a mix of ordinary conversation and traces the SDK
 * marked internal (`metadata._opik.is_internal`), for the thread Messages panel
 * and the SME annotation view that hide them.
 *
 * Seeded through the public SDK bridge, one trace per turn, sequentially with a
 * gap: turn order is derived from `start_time`, and two calls landing in the
 * same millisecond would make "renders in chronological order" a coin toss
 * rather than an assertion.
 *
 * **The fixture proves it can discriminate before any spec opens a browser.**
 * It asserts, through the same `GET /v1/private/traces` read the panel itself
 * makes, that the flag came back verbatim on exactly the traces that were meant
 * to carry it and on no others, and that `number_of_messages` is the unreduced
 * two-per-trace total. Without that, a seed whose metadata silently failed to
 * land would render exactly like a working hide rule, and every spec over it
 * would be green while asserting nothing.
 */
export const test = baseTest.extend<InternalTraceThreadsFixtures>({
  internalTraceThreads: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const seedThread = async (
      label: string,
      seeds: readonly TurnSeed[],
    ): Promise<InternalTraceThreadRef> => {
      const threadId = `${testNamespace}-${label}`;

      const turns: InternalTraceTurnRef[] = [];
      for (let i = 0; i < seeds.length; i += 1) {
        const seed = seeds[i];
        const name = `${testNamespace}-${label}-turn-${i + 1}`;
        const created = await sdkClient.python.createNestedTrace({
          project_name: project.name,
          name,
          input: { question: seed.question },
          output: { answer: seed.answer },
          metadata: seed.internal ? INTERNAL_METADATA : PLAIN_METADATA,
          thread_id: threadId,
          spans: [],
        });
        turns.push({ traceId: created.id, name, internal: seed.internal });
        if (i < seeds.length - 1) {
          await new Promise((r) => setTimeout(r, 100));
        }
      }

      // Thread aggregation is eventually consistent, so the retrieve is polled
      // rather than read once. It is also the only read that answers
      // `thread_model_id`, which the SME queue seed needs.
      const detail = await pollUntil(
        () => backendClient.getThread({ projectId: project.id, threadId }),
        (d) => d.numberOfMessages === seeds.length * MESSAGES_PER_TRACE,
        `thread ${threadId} to aggregate ${seeds.length * MESSAGES_PER_TRACE} messages`,
      );

      if (!detail.threadModelId) {
        throw new Error(
          `internalTraceThreads fixture: thread ${threadId} retrieved without a thread_model_id, ` +
            'so a thread-scope annotation queue cannot be seeded over it.',
        );
      }

      // The read the panel makes, with metadata intact. Asserted here rather
      // than in each spec: every claim downstream rests on the flag having
      // survived THIS query, and a spec is not the place to discover it did not.
      const listed = await pollUntil(
        () => backendClient.listThreadTraces({ projectId: project.id, threadId }),
        (rows) => rows.length === turns.length,
        `GET /traces filtered to ${threadId} to return ${turns.length} traces`,
      );

      const listedIds = listed.map((t) => t.id);
      const seededIds = turns.map((t) => t.traceId);
      if (listedIds.join(',') !== seededIds.join(',')) {
        throw new Error(
          `internalTraceThreads fixture: thread ${threadId} listed traces ` +
            `[${listedIds.join(', ')}] but seeded [${seededIds.join(', ')}] — the panel ` +
            'would render a different set, or a different order, than the specs assert.',
        );
      }

      for (let i = 0; i < listed.length; i += 1) {
        const expected = turns[i].internal ? true : undefined;
        const actual = (
          listed[i].metadata as
            | { _opik?: { is_internal?: unknown } }
            | null
            | undefined
        )?._opik?.is_internal;
        if (actual !== expected) {
          throw new Error(
            `internalTraceThreads fixture: ${listed[i].name} came back with ` +
              `metadata._opik.is_internal = ${JSON.stringify(actual)}, expected ` +
              `${JSON.stringify(expected)}. The hide rule reads exactly this field off ` +
              'exactly this read, so the seed cannot drive it.',
          );
        }
      }

      return {
        threadId,
        threadModelId: detail.threadModelId,
        turns,
        rawMessageCount: seeds.length * MESSAGES_PER_TRACE,
        internalTurns: turns.filter((t) => t.internal),
        plainTurns: turns.filter((t) => !t.internal),
      };
    };

    const main = await seedThread('internal-main', MAIN_TURNS);
    const allInternal = await seedThread('internal-only', ALL_INTERNAL_TURNS);

    const ref: InternalTraceThreadsRef = {
      projectId: project.id,
      projectName: project.name,
      main,
      allInternal,
    };

    await testInfo.attach('opik.internalTraceThreads', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);
    // No explicit teardown — the traces and threads live in `project`, whose
    // own fixture deletes it, the same as `conversation`.
  },
});

/**
 * Poll an eventually-consistent read until it satisfies `done`, then return it.
 * Throws naming what it was waiting for, so a timeout reads as the specific
 * aggregate that never settled rather than as an undefined field later on.
 */
async function pollUntil<T>(
  read: () => Promise<T>,
  done: (value: T) => boolean,
  what: string,
  timeoutMs = 30_000,
): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  let last: T = await read();
  while (!done(last)) {
    if (Date.now() > deadline) {
      throw new Error(
        `internalTraceThreads fixture: timed out after ${timeoutMs}ms waiting for ${what}. ` +
          `Last read: ${JSON.stringify(last)}`,
      );
    }
    await new Promise((r) => setTimeout(r, 500));
    last = await read();
  }
  return last;
}

export { expect } from './openai-responses-stub.fixture';
