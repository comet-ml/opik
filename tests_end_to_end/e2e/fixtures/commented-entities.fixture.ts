import { expect } from '@playwright/test';
import { test as baseTest } from './openrouter-removed-row-spans.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/** Which entity, if any, carries the comment a seeded trace was written for. */
export type CommentedOn = 'trace' | 'span' | 'thread' | 'none';

/** One seeded trace, and what was commented on around it. */
export interface CommentedTraceRef {
  /** Stable, namespace-free handle a spec looks a vector up by. */
  key: string;
  id: string;
  name: string;
  /** The producer-chosen `thread_id`, or null for a thread-less trace. */
  threadId: string | null;
  /**
   * The UUID the backend minted for the materialised thread, resolved by the
   * fixture. Null for a thread-less trace. This — not `threadId` — is what the
   * thread comment route and the `comments` thread filter key on.
   */
  threadModelId: string | null;
  commentedOn: CommentedOn;
}

export interface CommentedEntitiesRef {
  projectId: string;
  projectName: string;
  traces: CommentedTraceRef[];
  /** Trace ids a `comments is_not_empty` filter on /traces must return, exactly. */
  tracesWithTraceComment: string[];
  /** The complementary set — what `is_empty` must return, exactly. */
  tracesWithoutTraceComment: string[];
  /** Thread ids a `comments is_not_empty` filter on /traces/threads must return, exactly. */
  threadsWithThreadComment: string[];
  /** The complementary set. */
  threadsWithoutThreadComment: string[];
}

export interface CommentedEntitiesFixtures {
  commentedEntities: CommentedEntitiesRef;
}

/**
 * Six traces, three of them each alone in their own thread, carrying comments
 * placed so that an unscoped lookup is the only thing that can pass.
 *
 * `FilterQueryBuilder` renders the `comments` filter as a subquery over the
 * `comments` table, and the two Logs tabs render it differently:
 *
 *   TraceField.COMMENTS       id IN (… WHERE entity_type = 'trace')
 *   TraceThreadField.COMMENTS thread_model_id IN (… WHERE entity_type = 'thread')
 *
 * The `entity_type` predicate is the whole subject. Two of these six vectors
 * exist only to break a build that dropped it:
 *
 *  - **`span-commented`** is a thread-less trace whose SPAN carries the only
 *    comment. It must be EXCLUDED from `/traces?comments is_not_empty`. A
 *    lookup that joined on entity id without scoping by type returns it, and
 *    nothing else in the seed would notice.
 *  - **`thread-trace-commented`** is a trace inside its own thread carrying a
 *    TRACE comment. Its thread must be EXCLUDED from
 *    `/traces/threads?comments is_not_empty` — and the same trace must be
 *    INCLUDED on the traces side, which is what makes it a two-way control
 *    rather than a second copy of the first.
 *
 * The two `none` vectors are what stop "the filter returned everything" passing:
 * with them, every assertion here is a proper subset on both tabs.
 *
 * Comment TEXT is deliberately uniform-with-a-suffix rather than searched on:
 * `COMMENTS_OPERATORS` exposes only `is_not_empty`/`is_empty`, so no operator
 * reads the text at all, and a seed implying otherwise would mislead.
 */
const SEEDS: Array<{
  key: string;
  threaded: boolean;
  commentedOn: CommentedOn;
}> = [
  { key: 'trace-commented', threaded: false, commentedOn: 'trace' },
  { key: 'span-commented', threaded: false, commentedOn: 'span' },
  { key: 'plain-uncommented', threaded: false, commentedOn: 'none' },
  { key: 'thread-commented', threaded: true, commentedOn: 'thread' },
  { key: 'thread-trace-commented', threaded: true, commentedOn: 'trace' },
  { key: 'thread-uncommented', threaded: true, commentedOn: 'none' },
];

/**
 * How long a thread may take to materialise before the seed gives up.
 *
 * A thread is not written; it is aggregated from the traces that share a
 * `thread_id`, so `thread_model_id` — which the comment write and the thread
 * filter both key on — does not exist the instant the trace write answers 201.
 * Generous because this is staging: a timeout here is ingestion lag, not an
 * answer about comments, and the message says so.
 */
const THREAD_MODEL_TIMEOUT_MS = 90_000;

/**
 * How long a trace or span may take to become readable before the seed gives up.
 *
 * `POST /v1/private/traces` and `/spans` answer 201 from the ingestion queue,
 * before the row is queryable — and `POST …/{id}/comments` resolves the entity
 * first, so commenting on a just-written span answers **404** rather than
 * queueing. Observed on staging: the span write succeeding and the comment on
 * it 404ing three attempts in a row, which reads exactly like the comment
 * endpoint being broken.
 */
const ENTITY_READABLE_TIMEOUT_MS = 60_000;

export const test = baseTest.extend<CommentedEntitiesFixtures>({
  commentedEntities: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    // Asserted before anything is written: the two controls are the only reason
    // this fixture is shaped the way it is, and a seed list edited down to the
    // "obvious" four vectors would leave both specs passing over an unscoped
    // lookup.
    expect(
      SEEDS.filter((s) => !s.threaded && s.commentedOn === 'span').map((s) => s.key),
      'exactly one thread-less trace whose only comment is on its SPAN',
    ).toEqual(['span-commented']);
    expect(
      SEEDS.filter((s) => s.threaded && s.commentedOn === 'trace').map((s) => s.key),
      'exactly one threaded trace whose comment is on the TRACE, not the thread',
    ).toEqual(['thread-trace-commented']);
    expect(
      SEEDS.filter((s) => s.commentedOn === 'none'),
      'two uncommented vectors, one on each side, so neither filter can pass by returning everything',
    ).toHaveLength(2);

    const traces: CommentedTraceRef[] = [];
    const traceIds: string[] = [];
    const threadCommentIds: string[] = [];
    const now = new Date();

    /**
     * Block until a just-written entity can be resolved by id.
     *
     * The comment endpoints are not queued: they look the entity up and 404 if
     * it is not there yet, so every comment write below has to be gated on
     * this. Polled on "the read stopped answering null", which is how both
     * readers report a row that has not landed.
     */
    const waitUntilReadable = async (
      label: string,
      read: () => Promise<unknown | null>,
    ): Promise<void> => {
      await expect
        .poll(async () => (await read()) !== null, {
          timeout: ENTITY_READABLE_TIMEOUT_MS,
          intervals: [250, 500, 1_000, 2_000],
          message:
            `${label} to become readable before a comment is written against it — a timeout ` +
            'here is ingestion lag, not an answer about comments',
        })
        .toBe(true);
    };

    try {
      for (const seed of SEEDS) {
        const traceId = uuid7();
        const name = `${testNamespace}-comments-${seed.key}`;
        const threadId = seed.threaded ? `${testNamespace}-thread-${seed.key}` : null;

        await backendClient.createTraceWithSource({
          id: traceId,
          projectName: project.name,
          name,
          source: 'sdk',
          input: { question: `comment-filter seed ${seed.key}` },
          output: { answer: `comment-filter seed ${seed.key}` },
          ...(threadId === null ? {} : { threadId }),
          startTime: now,
          endTime: now,
        });
        traceIds.push(traceId);

        traces.push({
          key: seed.key,
          id: traceId,
          name,
          threadId,
          threadModelId: null,
          commentedOn: seed.commentedOn,
        });
      }

      // Comments are written in a second pass, after every trace exists, so a
      // thread's `thread_model_id` lookup is not racing its own trace write.
      for (const ref of traces) {
        if (ref.commentedOn === 'trace') {
          await waitUntilReadable(`trace ${ref.key}`, () => backendClient.getTrace(ref.id));
          const commentId = await backendClient.addComment({
            entity: 'traces',
            entityId: ref.id,
            text: `trace comment on ${ref.key}`,
          });
          // Read back by id rather than trusting the 201. A comment that did
          // not land looks exactly like a correctly-excluded row to every
          // assertion downstream, so the seed proves itself here instead.
          expect(
            await backendClient.getTraceComment(ref.id, commentId),
            `the trace comment seeded on ${ref.key} must be readable back`,
          ).toBe(`trace comment on ${ref.key}`);
        }

        if (ref.commentedOn === 'span') {
          const spanId = uuid7();
          await backendClient.createSpan({
            id: spanId,
            traceId: ref.id,
            projectName: project.name,
            name: `${ref.name}-span`,
            source: 'sdk',
            type: 'general',
            input: { step: 'the span that carries the only comment' },
            output: { step: 'done' },
            startTime: now,
            endTime: now,
          });
          await waitUntilReadable(`span of ${ref.key}`, () => backendClient.getSpan(spanId));
          const commentId = await backendClient.addComment({
            entity: 'spans',
            entityId: spanId,
            text: `span comment on ${ref.key}`,
          });
          expect(
            await backendClient.getSpanComment(spanId, commentId),
            `the span comment seeded on ${ref.key} must be readable back — it is the control ` +
              'for the traces filter, so a seed that silently failed would read as a pass',
          ).toBe(`span comment on ${ref.key}`);
        }
      }

      // Resolve every thread's model id, and comment on the one that wants a
      // THREAD comment. Both need the thread to have materialised.
      for (const ref of traces) {
        if (ref.threadId === null) continue;

        await expect
          .poll(
            async () => {
              const detail = await backendClient
                .getThread({ projectId: project.id, threadId: ref.threadId! })
                .catch(() => null);
              return detail?.threadModelId ?? null;
            },
            {
              timeout: THREAD_MODEL_TIMEOUT_MS,
              intervals: [500, 1_000, 2_000, 5_000],
              message:
                `thread ${ref.threadId} to materialise a thread_model_id — a timeout here is ` +
                'thread aggregation still catching up, not an answer about the comments filter',
            },
          )
          .not.toBeNull();

        const detail = await backendClient.getThread({
          projectId: project.id,
          threadId: ref.threadId,
        });
        ref.threadModelId = detail.threadModelId;
        expect(
          ref.threadModelId,
          `thread ${ref.threadId} must report a thread_model_id before it can be commented on ` +
            'or filtered by',
        ).not.toBeNull();

        if (ref.commentedOn === 'thread') {
          const commentId = await backendClient.addThreadComment({
            threadModelId: ref.threadModelId!,
            text: `thread comment on ${ref.key}`,
          });
          threadCommentIds.push(commentId);
          expect(
            await backendClient.getThreadComment(ref.threadModelId!, commentId),
            `the thread comment seeded on ${ref.key} must be readable back`,
          ).toBe(`thread comment on ${ref.key}`);
        }
      }

      const threaded = traces.filter((t) => t.threadId !== null);
      const ref: CommentedEntitiesRef = {
        projectId: project.id,
        projectName: project.name,
        traces,
        tracesWithTraceComment: traces.filter((t) => t.commentedOn === 'trace').map((t) => t.id),
        tracesWithoutTraceComment: traces
          .filter((t) => t.commentedOn !== 'trace')
          .map((t) => t.id),
        threadsWithThreadComment: threaded
          .filter((t) => t.commentedOn === 'thread')
          .map((t) => t.threadId!),
        threadsWithoutThreadComment: threaded
          .filter((t) => t.commentedOn !== 'thread')
          .map((t) => t.threadId!),
      };

      // The expected answers must be proper, non-empty subsets on both tabs, or
      // an "exactly these" assertion degenerates into "all" or "none".
      expect(ref.tracesWithTraceComment, 'two traces carry a trace comment').toHaveLength(2);
      expect(ref.tracesWithoutTraceComment, 'and four do not').toHaveLength(4);
      expect(ref.threadsWithThreadComment, 'one thread carries a thread comment').toHaveLength(1);
      expect(ref.threadsWithoutThreadComment, 'and two do not').toHaveLength(2);

      await testInfo.attach('opik.commentedEntities', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo)) {
        // Thread comments first, and explicitly: they are the one annotation
        // here that nothing cascades (see `deleteThreadComments`), and once the
        // traces are gone the thread they hang off cannot be reached at all.
        try {
          await backendClient.deleteThreadComments(threadCommentIds);
        } catch (err) {
          console.warn(
            `[commentedEntities fixture] delete thread-comment warning for ${threadCommentIds.join(', ')}:`,
            err,
          );
        }
        // The traces, which take their spans, their trace comments and their
        // span comments with them (TraceDeletedListener / SpanService).
        try {
          await backendClient.deleteTraces(traceIds);
        } catch (err) {
          console.warn(
            `[commentedEntities fixture] delete warning for traces ${traceIds.join(', ')}:`,
            err,
          );
        }
      }
    }
  },
});

export { expect } from './openrouter-removed-row-spans.fixture';
