import { test as baseTest } from './stoppable-run-dataset.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/** Each trace contributes an input and an output to a thread's message count. */
const MESSAGES_PER_TRACE = 2;

/**
 * A 400-character token with no space, hyphen or other break opportunity in it.
 *
 * Built from a fixed alphabet by index rather than from a random source, so the
 * same string lands on every run and a failure screenshot is comparable with
 * the last one. Length is the point: at the thread panel's widest the message
 * column is a few hundred pixels, so an unbroken run this long cannot fit on
 * one line by any font or zoom — which is what makes "it wrapped" a fact about
 * the CSS rather than about the viewport.
 */
const LONG_UNSPACED_TOKEN = Array.from(
  { length: 400 },
  (_, i) => 'abcdefghijklmnopqrstuvwxyz0123456789'[i % 36],
).join('');

/**
 * A URL with no break opportunity after the scheme.
 *
 * `overflow-wrap: break-word` breaks between words and would leave this one
 * intact and overflowing; only `anywhere` breaks inside it. Kept on a
 * `.invalid` host (RFC 6761) so nothing can ever resolve or fetch it.
 */
const UNBREAKABLE_URL =
  'https://gateway.qa-wrapping.invalid/' +
  Array.from({ length: 12 }, (_, i) => `segment${i}`).join('') +
  '?token=' +
  Array.from({ length: 180 }, (_, i) => '0123456789abcdef'[i % 16]).join('');

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
        `wideContentThread fixture: timed out after ${timeoutMs}ms waiting for ${what}. ` +
          `Last read: ${JSON.stringify(last)}`,
      );
    }
    await new Promise((r) => setTimeout(r, 500));
    last = await read();
  }
  return last;
}

export interface WideContentTurnSeed {
  /** Suffix of the trace name, and the label the ref is keyed by. */
  label: string;
  input: Record<string, unknown>;
  output: Record<string, unknown>;
}

/**
 * Turn payloads, all of them OBJECTS, which is load-bearing twice over.
 *
 * `TraceMessage` renders an object through `react18-json-view` (the two views
 * opik#9234 put `[overflow-wrap:anywhere]` on) and a string through
 * `MarkdownPreview`, which has always broken long words. So a spec over the
 * wrapping fix has to reach the JSON branch, and whether it does is decided by
 * `prettifyMessage`: that helper collapses an object to a string whenever it
 * recognises a chat shape, a single-key wrapper, or any of its predefined text
 * keys (`question`, `message`, `messages`, `input`, `text`, `content`,
 * `prompt`, `contents`, `answer`, `output`, `response`, …). Every key below is
 * therefore deliberately NOT one of those, and every payload carries at least
 * two keys so the single-key unwrap cannot fire either.
 *
 * The fixture asserts the branch was taken rather than trusting this comment —
 * see the `expectedJsonViews` check at the end of the seed.
 */
const TURNS: readonly WideContentTurnSeed[] = [
  {
    label: 'unspaced-token',
    input: { payload_ref: 'blob-in', blob: LONG_UNSPACED_TOKEN },
    output: { result_blob: LONG_UNSPACED_TOKEN, digest: 'sha256:deadbeef' },
  },
  {
    label: 'unbreakable-url',
    input: { payload_ref: 'url-in', endpoint: UNBREAKABLE_URL },
    output: { result_endpoint: UNBREAKABLE_URL, digest: 'sha256:cafebabe' },
  },
  {
    label: 'wide-json',
    input: {
      payload_ref: 'wide-in',
      nested: {
        very_long_attribute_name_that_does_not_wrap_on_its_own: LONG_UNSPACED_TOKEN.slice(0, 180),
        second_very_long_attribute_name_without_break_opportunities: UNBREAKABLE_URL.slice(0, 180),
      },
    },
    output: {
      result_rows: [LONG_UNSPACED_TOKEN.slice(0, 200), UNBREAKABLE_URL.slice(0, 200)],
      digest: 'sha256:f00dface',
    },
  },
  {
    // An ordinary turn, so the panel is not uniformly pathological: a layout
    // rule that only held for wide content would still be a bug, and a spec
    // whose every turn is wide could not see it.
    label: 'ordinary',
    input: { payload_ref: 'plain-in', note: 'a short line' },
    output: { result_note: 'a short answer', digest: 'sha256:0badc0de' },
  },
];

export interface WideContentTurnRef {
  label: string;
  traceId: string;
  name: string;
}

export interface WideContentThreadRef {
  threadId: string;
  /** `thread_model_id`, which a thread-scope annotation queue is seeded by. */
  threadModelId: string;
  projectId: string;
  projectName: string;
  /** Turns in the order they were logged (oldest first). */
  turns: WideContentTurnRef[];
  /** The 400-character unspaced token every wide turn carries. */
  longToken: string;
  /** The break-opportunity-free URL the wide turns carry. */
  unbreakableUrl: string;
}

export interface WideContentThreadQueueRef {
  queueId: string;
  queueName: string;
  projectId: string;
  threadId: string;
  feedbackDefinitionName: string;
}

export interface WideContentThreadFixtures {
  wideContentThread: WideContentThreadRef;
  wideContentThreadQueue: WideContentThreadQueueRef;
}

/**
 * A four-turn thread whose messages carry content that cannot fit on one line:
 * a 400-character unspaced token, a break-opportunity-free URL and a wide
 * nested JSON object, plus one ordinary turn.
 *
 * Seeded through the public SDK bridge, so the same shape lands on OSS and
 * Cloud, and torn down with the project it lives in.
 *
 * The seed is PROVEN before any browser opens: the traces are read back and
 * their payloads asserted to still be objects carrying the long token intact.
 * Without that, an input the backend had stringified or truncated would render
 * through `MarkdownPreview` — which wraps regardless of opik#9234 — and every
 * geometry assertion over it would pass while testing nothing.
 */
export const test = baseTest.extend<WideContentThreadFixtures>({
  wideContentThread: async ({ sdkClient, backendClient, project, testNamespace }, use, testInfo) => {
    const threadId = `${testNamespace}-wide-thread`;

    const turns: WideContentTurnRef[] = [];
    for (let i = 0; i < TURNS.length; i += 1) {
      const seed = TURNS[i];
      const name = `${testNamespace}-${seed.label}`;
      // `createNestedTrace` rather than `createTrace`: the bridge's plain
      // /traces route types input and output as STRINGS, and a string payload
      // is exactly what this fixture must not send — it would render through
      // MarkdownPreview instead of the JSON view under test. No spans, so the
      // "nested" part costs nothing beyond the route's read-back.
      const created = await sdkClient.python.createNestedTrace({
        project_name: project.name,
        name,
        input: seed.input,
        output: seed.output,
        thread_id: threadId,
        spans: [],
      });
      turns.push({ label: seed.label, traceId: created.id, name });
      // A short gap so the turns' generated ids, and so the panel's render
      // order, follow the order they were seeded in.
      if (i < TURNS.length - 1) {
        await new Promise((r) => setTimeout(r, 100));
      }
    }

    // Thread aggregation is eventually consistent, so this is polled rather
    // than read once. It is also the only read that answers `thread_model_id`,
    // which `wideContentThreadQueue` needs.
    const detail = await pollUntil(
      () => backendClient.getThread({ projectId: project.id, threadId }),
      (d) => d.numberOfMessages === TURNS.length * MESSAGES_PER_TRACE,
      `thread ${threadId} to aggregate ${TURNS.length * MESSAGES_PER_TRACE} messages`,
    );

    if (!detail.threadModelId) {
      throw new Error(
        `wideContentThread fixture: thread ${threadId} retrieved without a thread_model_id, ` +
          'so a thread-scope annotation queue cannot be seeded over it.',
      );
    }

    const listed = await pollUntil(
      () => backendClient.listThreadTraces({ projectId: project.id, threadId }),
      (rows) => rows.length === turns.length,
      `GET /traces filtered to ${threadId} to return ${turns.length} traces`,
    );

    const listedIds = listed.map((t) => t.id);
    const seededIds = turns.map((t) => t.traceId);
    if (listedIds.join(',') !== seededIds.join(',')) {
      throw new Error(
        `wideContentThread fixture: thread ${threadId} listed traces [${listedIds.join(', ')}] ` +
          `but seeded [${seededIds.join(', ')}] — the panel would render a different set, or a ` +
          'different order, than the spec asserts.',
      );
    }

    // The discrimination proof, read back through the same `GET /traces/{id}`
    // the panel's own query uses. Three separate ways the seed could silently
    // stop driving the JSON branch — a payload stringified in transit, a key
    // set rewritten, or content truncated below the token's length — and each
    // of them would leave a green spec behind, because `MarkdownPreview` (the
    // branch a string takes) wraps whether or not opik#9234 is in.
    for (let i = 0; i < turns.length; i += 1) {
      const seed = TURNS[i];
      const turn = turns[i];
      const sections = await pollUntil(
        () => backendClient.getTraceSections(turn.traceId),
        (s) => s !== null,
        `GET /traces/${turn.traceId} to return the seeded payloads`,
      );
      for (const side of ['input', 'output'] as const) {
        const payload = sections![side];
        if (payload === null || typeof payload !== 'object' || Array.isArray(payload)) {
          throw new Error(
            `wideContentThread fixture: ${turn.name} came back with a ${side} of type ` +
              `${Array.isArray(payload) ? 'array' : typeof payload}, not an object. The thread ` +
              'panel renders an object through JsonView and anything else through ' +
              'MarkdownPreview, which wraps regardless of the fix under test.',
          );
        }
        const seededKeys = Object.keys(seed[side]).sort().join(',');
        const readKeys = Object.keys(payload as Record<string, unknown>)
          .sort()
          .join(',');
        if (seededKeys !== readKeys) {
          throw new Error(
            `wideContentThread fixture: ${turn.name}'s ${side} came back with keys [${readKeys}] ` +
              `but was seeded with [${seededKeys}] — the payload was rewritten in transit, so ` +
              'what the panel renders is not what this fixture describes.',
          );
        }
      }
      if (seed.label !== 'ordinary') {
        const rendered = JSON.stringify(sections!.input) + JSON.stringify(sections!.output);
        const needle = seed.label === 'unbreakable-url' ? UNBREAKABLE_URL.slice(0, 120) : LONG_UNSPACED_TOKEN.slice(0, 120);
        if (!rendered.includes(needle)) {
          throw new Error(
            `wideContentThread fixture: ${turn.name} no longer carries its unbreakable run of ` +
              'characters, so there is nothing in it that could overflow its container.',
          );
        }
      }
    }

    const ref: WideContentThreadRef = {
      threadId,
      threadModelId: detail.threadModelId,
      projectId: project.id,
      projectName: project.name,
      turns,
      longToken: LONG_UNSPACED_TOKEN,
      unbreakableUrl: UNBREAKABLE_URL,
    };

    await testInfo.attach('opik.wideContentThread', {
      body: JSON.stringify({ ...ref, longToken: `<${LONG_UNSPACED_TOKEN.length} chars>` }, null, 2),
      contentType: 'application/json',
    });

    await use(ref);
    // No explicit teardown — the project fixture's deleteProject cascades the
    // traces, and the thread is derived from them.
  },

  /**
   * A `scope: 'thread'` annotation queue holding exactly the wide-content
   * thread, so the SME annotation view (`/$workspace/sme`) opens straight onto
   * it and renders the same `TraceMessages` component the thread panel does.
   *
   * One item, deliberately: the SME flow lands on the first unprocessed item,
   * and with a second thread in the queue which one that is would depend on
   * queue ordering rather than on the fixture.
   *
   * Created over REST rather than through `sdkClient.python.createAnnotationQueue`
   * — the bridge's route adds TRACES to a trace-scope queue and cannot express
   * either `scope: 'thread'` or an add by `thread_model_id`. Queues cascade with
   * neither the project nor a `test.afterEach`, so teardown is here.
   */
  wideContentThreadQueue: async (
    { backendClient, wideContentThread, feedbackDefinition, testNamespace },
    use,
    testInfo,
  ) => {
    const queueId = uuid7();
    const queueName = `${testNamespace}-wide-thread-queue`;

    const created = await backendClient.createAnnotationQueue({
      id: queueId,
      projectId: wideContentThread.projectId,
      name: queueName,
      scope: 'thread',
      feedbackDefinitionNames: [feedbackDefinition.name],
    });
    if (created.status !== 201) {
      throw new Error(
        `wideContentThreadQueue fixture: creating queue ${queueName} answered ` +
          `${created.status}: ${created.message}`,
      );
    }

    // By `thread_model_id`, not `thread_id`: the endpoint accepts the wrong one
    // with a 204 and adds nothing, which would leave the SME view on its
    // no-items state and read as a product bug.
    await backendClient.addAnnotationQueueItems(queueId, [wideContentThread.threadModelId]);

    const detail = await backendClient.getAnnotationQueue(queueId);
    if (detail?.itemsCount !== 1) {
      throw new Error(
        `wideContentThreadQueue fixture: queue ${queueName} reports itemsCount ` +
          `${detail?.itemsCount ?? 'null'}, expected 1 — the SME view would open on an empty queue.`,
      );
    }

    const ref: WideContentThreadQueueRef = {
      queueId,
      queueName,
      projectId: wideContentThread.projectId,
      threadId: wideContentThread.threadId,
      feedbackDefinitionName: feedbackDefinition.name,
    };

    await testInfo.attach('opik.wideContentThreadQueue', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteAnnotationQueue(queueId);
      } catch (err) {
        console.warn(`[wideContentThreadQueue fixture] delete warning for ${queueName}:`, err);
      }
    }
  },
});

export { expect } from './stoppable-run-dataset.fixture';
