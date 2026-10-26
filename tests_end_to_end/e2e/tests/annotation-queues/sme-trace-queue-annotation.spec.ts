import { test, expect } from '@e2e/fixtures';
import type { AnnotationQueueTraceRef } from '@e2e/fixtures';
import { SmeFlowPage } from '@e2e/pom/sme-flow.page';

/**
 * A reviewer working a TRACE queue through `/$workspace/sme` is shown each
 * item's own trace, and the scores they submit land on the items they were
 * shown (opik#9079, OPIK-8809).
 *
 * Two gaps meet here, and both are the silent kind:
 *
 *  - **The trace path of the SME route had no test.** 9079 rewrote
 *    `TraceDataViewer` to render through the shared `DefaultTraceView`, and the
 *    only spec that reached `/sme` at all (`sme-thread-internal-traces.spec.ts`)
 *    uses a THREAD queue, which mounts `ThreadDataViewer` instead.
 *  - **Scoring through the SME layout had no test.** `score-queue-item`,
 *    `score-with-reason` and `skip-item` are all asserted on the project-scoped
 *    admin route (`annotation-queue-scoring.spec.ts`), whose annotate panel is
 *    a different component tree. `annotation-queues.sme-flow`'s own note says
 *    to read that key as "the SME route renders", not "the SME journey works".
 *
 * A reviewer shown the wrong item writes a wrong label and nothing complains:
 * the score is valid, the trace is real, and the ground truth everything else
 * is measured against is quietly wrong. That is what this asserts — not that
 * the page rendered, but that the payload on screen and the trace the score
 * lands on are the same trace, for every item in the queue.
 *
 * ## Why the test asks which item it is on instead of stating it
 *
 * `SMEFlowContext` picks the first item, and every Next after it, from
 * `shuffledItemIds` — a per-user deterministic shuffle keyed on the queue id
 * and the item ids, which are fresh UUIDs on every run. The visit order is
 * therefore unknowable to the spec, and asserting a fixed one would be a flake
 * generator. So the loop reads the trace the viewer is showing, asserts it is
 * one the queue holds and one not already visited, and derives its expected
 * payload from that — which makes the assertion stronger than an ordered one
 * would be, because it holds for whichever order the shuffle produced.
 *
 * ## Not claimed
 *
 * The reason field and the comment composer (`score-with-reason` is asserted on
 * the admin route), the completion view after the last item, and the custom
 * annotator view — `annotator-view-sandbox.spec.ts` owns that.
 */

/** How long a score written through the SME flow's 1s debounce may take to land. */
const SCORE_VISIBLE_TIMEOUT_MS = 60_000;

/**
 * The value scored on each of the two items the reviewer grades.
 *
 * Two DIFFERENT values, deliberately: the feedback definition is 0..1, and if
 * both items were scored 1 then "the right score landed on the right trace"
 * could not be told from "both traces got the same number". With 1 and 0, a
 * swap fails.
 */
const SCORES = [1, 0] as const;

test.describe(
  'SME annotation view — a trace queue',
  { tag: ['@t2-cuj', '@area:annotation-queues'] },
  () => {
    test(
      'shows each item its own trace, and lands a reviewer\'s scores on the items they were shown',
      // `score-queue-item` and `scores-reach-traces` alongside `sme-flow`,
      // because this really does assert both: a score is submitted through the
      // annotate panel, and it is read back off the source trace by id. Both
      // keys already read covered on the admin-route spec, so nothing flips —
      // what the tags add is that the SME route's submit path is now visible on
      // the map instead of being coverage nobody could find.
      //
      // NOT `skip-item`: the middle stop is advanced past with the Next
      // control, which is not the product's Skip action. `score-with-reason` is
      // not claimed either — see "Not claimed" above.
      {
        tag: [
          '@cap:annotation-queues.sme-flow',
          '@cap:annotation-queues.score-queue-item',
          '@cap:annotation-queues.scores-reach-traces',
        ],
      },
      async ({ annotationQueue, backendClient, page }) => {
        const scoreName = annotationQueue.feedbackDefinitionName;
        const queueTraces = annotationQueue.traces;
        const sme = new SmeFlowPage(page);

        await test.step('The queue holds the three seeded traces and none of them is scored yet', async () => {
          // The precondition every assertion below rests on. Asserted as the
          // whole answer rather than as membership: a queue that had also
          // picked up a stray item would make "exactly the scored traces carry
          // the score" a claim about a different set than the one this spec
          // walks. And a trace that arrived already carrying this score would
          // make the final read pass without the UI having written anything.
          const detail = await backendClient.getAnnotationQueue(annotationQueue.id);
          expect(detail, 'the seeded queue is readable').not.toBeNull();
          expect(detail!.itemsCount, 'the queue holds exactly the three seeded traces').toBe(
            queueTraces.length,
          );
          for (const item of queueTraces) {
            const trace = await backendClient.getTrace(item.id);
            expect(trace, `seeded trace ${item.name} is readable`).not.toBeNull();
            expect(
              trace!.feedbackScores.map((fs) => fs.name),
              `${item.name} starts with no score named '${scoreName}'`,
            ).not.toContain(scoreName);
          }
          expect(
            new Set(queueTraces.map((t) => t.input)).size,
            'the three items carry three distinct inputs, or "this item\'s own payload" is unprovable',
          ).toBe(queueTraces.length);
          expect(
            new Set(queueTraces.map((t) => t.output)).size,
            'the three items carry three distinct outputs',
          ).toBe(queueTraces.length);
        });

        await test.step('Open the SME link and enter the annotation view', async () => {
          await sme.goto(annotationQueue.id);
          await expect(
            sme.welcomeHeading(annotationQueue.name),
            'the instructions page names the queue the link points at',
          ).toBeVisible({ timeout: 30_000 });
          await sme.startAnnotating();
          await sme.waitForTraceReady();
        });

        /** Visit order as the shuffle produced it, and what was done at each stop. */
        const visited: Array<{ trace: AnnotationQueueTraceRef; scored: number | null }> = [];

        for (let stop = 0; stop < queueTraces.length; stop += 1) {
          // eslint-disable-next-line no-await-in-loop
          await test.step(`Item ${stop + 1} of ${queueTraces.length}`, async () => {
            await sme.waitForTraceReady();

            const currentId = await sme.currentTraceId(queueTraces.map((t) => t.id));
            const current = queueTraces.find((t) => t.id === currentId)!;
            expect(
              visited.map((v) => v.trace.id),
              'the flow does not show the same item twice before the queue is exhausted',
            ).not.toContain(currentId);

            // The whole point. The items sidebar previews every queued item, so
            // the payload of a sibling IS on the page — these assertions are
            // scoped to the viewer's own Input and Output blocks, where only
            // the item under review may appear.
            await expect(
              sme.codeBlock('Input'),
              `the viewer's Input block shows ${current.name}'s own input`,
            ).toContainText(current.input);
            await expect(
              sme.codeBlock('Output'),
              `the viewer's Output block shows ${current.name}'s own output`,
            ).toContainText(current.output);
            for (const other of queueTraces.filter((t) => t.id !== currentId)) {
              await expect(
                sme.codeBlock('Input'),
                `the viewer's Input block must not show ${other.name}'s input — a reviewer ` +
                  'shown the wrong payload writes a wrong label and nothing complains',
              ).not.toContainText(other.input);
              await expect(
                sme.codeBlock('Output'),
                `the viewer's Output block must not show ${other.name}'s output`,
              ).not.toContainText(other.output);
            }

            // The second half of the identity claim: the header names this
            // trace and no sibling, so the payload above and the trace the
            // score is about to be written to are the same trace.
            await expect(
              sme.traceHeaderIdentifier(currentId),
              'the Trace: header names the item whose payload is on screen',
            ).toHaveCount(1);
            for (const other of queueTraces.filter((t) => t.id !== currentId)) {
              await expect(
                sme.traceHeaderIdentifier(other.id),
                `the header names no sibling (${other.name})`,
              ).toHaveCount(0);
            }

            // Grade the first and the last stop, and advance past the middle
            // one without scoring it. Which TRACE ends up skipped is the
            // shuffle's business; that exactly one is, and that the skip is
            // reached through the same Next control, is this spec's.
            const score = stop === 1 ? null : SCORES[stop === 0 ? 0 : 1];
            if (score !== null) {
              await sme.setScore(scoreName, score);
              const landed = await backendClient.pollTraceForFeedbackScore(currentId, scoreName, {
                timeoutMs: SCORE_VISIBLE_TIMEOUT_MS,
              });
              expect(
                landed.value,
                `the score submitted for ${current.name} reached ${current.name} itself`,
              ).toBe(score);
            }
            visited.push({ trace: current, scored: score });

            if (stop < queueTraces.length - 1) {
              await sme.advancePast(currentId);
            }
          });
        }

        await test.step('Every item was visited exactly once', async () => {
          expect(
            visited.map((v) => v.trace.id).sort(),
            'the flow walked the whole queue',
          ).toEqual(queueTraces.map((t) => t.id).sort());
          expect(
            visited.filter((v) => v.scored === null).map((v) => v.trace.name),
            'exactly one item was advanced past without being scored',
          ).toHaveLength(1);
        });

        await test.step('Exactly the graded items carry the score, each with the value it was given', async () => {
          // Read back over the WHOLE queue, not just the graded items: "the two
          // I scored have the right values" passes equally well against a flow
          // that also wrote something to the one I skipped, and a stray score
          // on an unreviewed trace is the same silent corruption of ground
          // truth as a score on the wrong one.
          for (const { trace, scored } of visited) {
            const detail = await backendClient.getTrace(trace.id);
            expect(detail, `${trace.name} is readable after the run`).not.toBeNull();
            const matching = detail!.feedbackScores.filter((fs) => fs.name === scoreName);
            if (scored === null) {
              expect(
                matching,
                `${trace.name} was advanced past without scoring, so it must carry no ` +
                  `'${scoreName}' at all`,
              ).toEqual([]);
            } else {
              expect(
                matching.map((fs) => fs.value),
                `${trace.name} carries exactly one '${scoreName}', at the value submitted for it`,
              ).toEqual([scored]);
            }
          }
        });

        await test.step('The queue still holds every item it started with', async () => {
          // A reviewer pass must not consume the queue. Nothing in the UI says
          // so, and a flow that removed a scored item would leave the two
          // assertions above true while making the queue unreviewable by the
          // next annotator.
          const detail = await backendClient.getAnnotationQueue(annotationQueue.id);
          expect(detail, 'the queue is still readable after the pass').not.toBeNull();
          expect(detail!.itemsCount, 'scoring removes nothing from the queue').toBe(
            queueTraces.length,
          );
        });
      },
    );
  },
);
