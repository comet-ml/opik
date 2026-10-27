import { test, expect } from '@e2e/fixtures';
import { uuid7 } from '@e2e/core/backend';
import type { BackendClient } from '@e2e/core/backend';

/**
 * The claims opik#8597 documents about automatic annotation-queue population,
 * checked against the running feature.
 *
 * #8597 ships no code — it is the documentation page for a fill that #8687 had
 * just turned on by default. That is exactly why it is worth a spec: a docs
 * page cannot regress the release, but it makes falsifiable promises about a
 * behaviour the estate barely covers, and a promise nobody checks is how a
 * product and its documentation drift apart.
 *
 * Three of its claims, none of which any existing spec can see:
 *
 *  - **Forward-only.** Turning automation on collects nothing that was already
 *    scored. The failure is a review queue that fills with history the moment
 *    it is created.
 *  - **Only production traces.** Playground and experiment runs are skipped,
 *    however well they score. The failure is a human review queue filling with
 *    the team's own experiment noise — silent until someone opens it.
 *  - **Never re-added.** An item a reviewer removes stays removed, even if it
 *    is scored again above the threshold. The failure makes the remove action
 *    useless and is invisible from the queue's own configuration.
 *
 * Plus the condition cap the page states as five.
 *
 * `annotation-queue-automated-fill.spec.ts` covers a neighbouring claim — a
 * matching score routes in, a non-matching one does not — and
 * `annotation-queue-automation-config.spec.ts` covers the config round-trip
 * only. Neither says anything about source, about history, or about removal.
 *
 * ## Why every negative here has a barrier rather than a timeout
 *
 * "X never arrives" is unfalsifiable against an asynchronous fill: a spec that
 * waited and shrugged would pass just as well on a build where routing was
 * switched off entirely. So each negative is anchored on a POSITIVE control
 * that went through the same flush cycle. Once the control is in the queue, the
 * batch that carried the negatives has been processed and rejected them.
 *
 * The forward-only case needs a barrier of a different shape, because its score
 * is written BEFORE the queue under test exists and so has no control to travel
 * with. A second queue, created first and matching the same condition, is that
 * barrier: the pre-existing trace arriving in it proves the flush cycle
 * covering that score has completed, so the queue created afterwards really did
 * come into a world where the score was already old news. It doubles as the
 * proof that the trace was routable all along — without it, "it never appeared"
 * would also be satisfied by a trace automation could never have accepted.
 *
 * Not gated on `serviceToggles.annotationQueueAutomationEnabled`: #8687 made it
 * default-on, and the two sibling specs above take the same position. A
 * deployment that turns it back off should fail loudly here rather than skip.
 */

const SCORE_THRESHOLD = 0.5;
/** Comfortably over the threshold, for everything meant to be routed. */
const MATCHING_SCORE = 0.9;
/** Comfortably under it — the condition control. */
const NON_MATCHING_SCORE = 0.1;

/**
 * An item is routed between `debounceDelay` (15s) and `debounceDelay +
 * jobInterval` (20s) after its last score, per `annotationQueueRouting` in
 * config.yml. The budget is several times that so a loaded CI box queueing the
 * flush job behind other work reads as slow, not as broken.
 */
const ROUTING_TIMEOUT_MS = 120_000;

/** Wait until exactly the expected ids are in the queue, out of those asked about. */
async function waitForQueueMembership(
  backendClient: BackendClient,
  queueId: string,
  askAbout: string[],
  expected: string[],
): Promise<void> {
  await expect
    .poll(
      async () =>
        (await backendClient.searchAnnotationQueueItems(queueId, askAbout)).items
          .map((i) => i.id)
          .sort(),
      {
        message: `queue ${queueId} should settle holding exactly ${expected.length} of the ${askAbout.length} ids asked about`,
        timeout: ROUTING_TIMEOUT_MS,
        intervals: [1_000, 2_000, 5_000],
      },
    )
    .toEqual([...expected].sort());
}

test.describe('Annotation queues — what automation will and will not collect', {
  tag: ['@t2-cuj', '@area:annotation-queues'],
}, () => {
  test(
    'automation collects only production traces scored after the queue existed',
    { tag: ['@cap:annotation-queues.automated-queue-fill'] },
    async ({ project, backendClient, registerAnnotationQueueCleanup, testNamespace }) => {
      test.setTimeout(420_000);

      // Namespaced so the condition names a score no other run could write, and
      // so nothing already in this workspace can satisfy it by accident.
      const scoreName = `${testNamespace}-relevance`;
      const condition = { scoreName, operator: '>' as const, value: SCORE_THRESHOLD };

      const clockQueueId = uuid7();
      const queueId = uuid7();

      await test.step('Create the barrier queue, before anything is scored', async () => {
        registerAnnotationQueueCleanup(clockQueueId, `${testNamespace}-clock-queue`);
        const { status, message } = await backendClient.createAnnotationQueue({
          id: clockQueueId,
          projectId: project.id,
          name: `${testNamespace}-clock-queue`,
          scope: 'trace',
          automation: { enabled: true, groups: [[condition]] },
        });
        expect(status, `creating the barrier queue answered: ${message}`).toBe(201);
      });

      const historic = await test.step('Score one SDK trace while only the barrier queue exists', async () => {
        const id = await seedSdkTrace(backendClient, project.name, `${testNamespace}-historic`);
        await backendClient.setTraceFeedbackScores({
          projectName: project.name,
          scores: [{ traceId: id, name: scoreName, value: MATCHING_SCORE }],
        });
        return id;
      });

      await test.step('It lands in the barrier queue — so that flush cycle is done', async () => {
        // The whole point of the barrier. Everything below asserts what a queue
        // created AFTER this moment did with a score written BEFORE it, which
        // is only a statement about forward-only once the score has been
        // through routing at least once.
        await waitForQueueMembership(backendClient, clockQueueId, [historic], [historic]);
      });

      await test.step('Only now create the queue under test, with the same condition', async () => {
        registerAnnotationQueueCleanup(queueId, `${testNamespace}-queue`);
        const { status, message } = await backendClient.createAnnotationQueue({
          id: queueId,
          projectId: project.id,
          name: `${testNamespace}-queue`,
          scope: 'trace',
          automation: { enabled: true, groups: [[condition]] },
        });
        expect(status, `creating the queue under test answered: ${message}`).toBe(201);
      });

      await test.step('…and it really stored the condition the scores below are aimed at', async () => {
        // Proved before any further score is written: over a queue that
        // silently failed to store a condition, nothing would ever be routed
        // and every negative below would pass for the wrong reason.
        const automation = await backendClient.getAnnotationQueueAutomation(queueId);
        expect(automation, 'the queue must read back with an automation block').not.toBeNull();
        expect(automation).toEqual({
          enabled: true,
          maxItemsInQueue: null,
          groups: [[condition]],
        });
      });

      const seeded = await test.step('Seed one trace per source, and one under the threshold', async () => {
        const ids = {
          control: await seedSdkTrace(backendClient, project.name, `${testNamespace}-control`),
          playground: await seedTrace(
            backendClient,
            project.name,
            `${testNamespace}-playground`,
            'playground',
          ),
          experiment: await seedTrace(
            backendClient,
            project.name,
            `${testNamespace}-experiment`,
            'experiment',
          ),
          belowThreshold: await seedSdkTrace(
            backendClient,
            project.name,
            `${testNamespace}-below`,
          ),
        };

        // One request on purpose: it is what makes every exclusion below a
        // contract rather than a race. All four enter the routing buffer in
        // the same instant and leave it in the same flush cycle, so once the
        // control is in the queue the other three have been judged.
        await backendClient.setTraceFeedbackScores({
          projectName: project.name,
          scores: [
            { traceId: ids.control, name: scoreName, value: MATCHING_SCORE },
            { traceId: ids.playground, name: scoreName, value: 1 },
            { traceId: ids.experiment, name: scoreName, value: 1 },
            { traceId: ids.belowThreshold, name: scoreName, value: NON_MATCHING_SCORE },
          ],
        });
        return ids;
      });

      const everyTrace = [historic, ...Object.values(seeded)];

      await test.step('The queue takes the production trace, and nothing else', async () => {
        // The whole answer over every id this test created, not a lookup of the
        // control alone: finding the control in a longer response would pass
        // just as well on a build that swept all five in, which is the
        // regression worth catching.
        await waitForQueueMembership(backendClient, queueId, everyTrace, [seeded.control]);

        const { status, message, items } = await backendClient.searchAnnotationQueueItems(
          queueId,
          everyTrace,
        );
        expect(status, `the membership lookup answered: ${message}`).toBe(200);
        expect(
          items,
          'the scored-before-the-queue, playground, experiment and sub-threshold traces are all out',
        ).toEqual([{ id: seeded.control, source: 'automated' }]);
      });

      await test.step('And the queue holds exactly that one item in total', async () => {
        // Separate from the lookup above, which only reports on the ids it was
        // asked about: a queue that had also swept in traces from elsewhere in
        // the project would satisfy it.
        const queue = await backendClient.getAnnotationQueue(queueId);
        expect(queue, 'the queue must still be readable').not.toBeNull();
        expect(queue!.itemsCount).toBe(1);
      });

      await test.step('The barrier queue meanwhile took every matching trace, history included', async () => {
        // The control for the control. Without it, "the queue under test holds
        // one of five" is equally satisfied by a build whose routing is simply
        // broken for four of them — the barrier queue, which existed before all
        // five scores, must hold both production traces that matched.
        await waitForQueueMembership(backendClient, clockQueueId, everyTrace, [
          historic,
          seeded.control,
        ]);
      });
    },
  );

  test(
    'an item a reviewer removed is not re-added by a later matching score',
    { tag: ['@cap:annotation-queues.automated-queue-fill'] },
    async ({ project, backendClient, registerAnnotationQueueCleanup, testNamespace }) => {
      test.setTimeout(420_000);

      const scoreName = `${testNamespace}-relevance`;
      const queueId = uuid7();

      await test.step('Create a queue that fills itself above the threshold', async () => {
        registerAnnotationQueueCleanup(queueId, `${testNamespace}-requeue`);
        const { status, message } = await backendClient.createAnnotationQueue({
          id: queueId,
          projectId: project.id,
          name: `${testNamespace}-requeue`,
          scope: 'trace',
          automation: {
            enabled: true,
            groups: [[{ scoreName, operator: '>', value: SCORE_THRESHOLD }]],
          },
        });
        expect(status, `creating the queue answered: ${message}`).toBe(201);
      });

      const removed = await seedSdkTrace(backendClient, project.name, `${testNamespace}-removed`);
      const barrier = await seedSdkTrace(backendClient, project.name, `${testNamespace}-barrier`);

      await test.step('Automation routes the first trace in', async () => {
        await backendClient.setTraceFeedbackScores({
          projectName: project.name,
          scores: [{ traceId: removed, name: scoreName, value: MATCHING_SCORE }],
        });
        await waitForQueueMembership(backendClient, queueId, [removed, barrier], [removed]);
      });

      await test.step('A reviewer takes it back out', async () => {
        await backendClient.removeAnnotationQueueItems(queueId, [removed]);
        // Asserted immediately: if the remove silently did nothing, the
        // "stayed out" assertion below would pass over an item that never left.
        await waitForQueueMembership(backendClient, queueId, [removed, barrier], []);
      });

      await test.step('Score it again, higher, alongside a trace that has never been in', async () => {
        // The barrier travels in the same batch, so its arrival is what makes
        // "the removed one stayed out" a statement about re-adding rather than
        // about having waited the wrong amount of time.
        await backendClient.setTraceFeedbackScores({
          projectName: project.name,
          scores: [
            { traceId: removed, name: scoreName, value: 0.95 },
            { traceId: barrier, name: scoreName, value: 0.95 },
          ],
        });
      });

      await test.step('The barrier joins and the removed trace does not come back', async () => {
        await waitForQueueMembership(backendClient, queueId, [removed, barrier], [barrier]);

        const { items } = await backendClient.searchAnnotationQueueItems(queueId, [
          removed,
          barrier,
        ]);
        expect(
          items,
          'the re-scored trace must stay out; only the never-removed one is in, as automated',
        ).toEqual([{ id: barrier, source: 'automated' }]);

        const queue = await backendClient.getAnnotationQueue(queueId);
        expect(queue, 'the queue must still be readable').not.toBeNull();
        expect(queue!.itemsCount, 'and it holds only the barrier').toBe(1);
      });
    },
  );

  test(
    'an automation group takes five AND-ed conditions and refuses a sixth',
    { tag: ['@cap:annotation-queues.create-queue'] },
    async ({ project, backendClient, registerAnnotationQueueCleanup, testNamespace }) => {
      const conditionsFor = (count: number) =>
        Array.from({ length: count }, (_, i) => ({
          scoreName: `${testNamespace}-score-${i + 1}`,
          operator: '>' as const,
          value: SCORE_THRESHOLD,
        }));

      const acceptedId = uuid7();
      const refusedId = uuid7();

      await test.step('Five conditions in one group is accepted', async () => {
        // The positive half, and not a formality: a cap enforced one too low
        // would reject this, and a test that only checked the rejection would
        // call that a pass.
        registerAnnotationQueueCleanup(acceptedId, `${testNamespace}-five`);
        const { status, message } = await backendClient.createAnnotationQueue({
          id: acceptedId,
          projectId: project.id,
          name: `${testNamespace}-five`,
          scope: 'trace',
          automation: { enabled: true, groups: [conditionsFor(5)] },
        });
        expect(status, `five conditions answered: ${message}`).toBe(201);
      });

      await test.step('…and it really stored all five', async () => {
        const automation = await backendClient.getAnnotationQueueAutomation(acceptedId);
        expect(automation, 'the queue must read back with an automation block').not.toBeNull();
        expect(
          automation!.groups,
          'a cap that silently truncated to four would otherwise read as acceptance',
        ).toEqual([conditionsFor(5)]);
      });

      await test.step('Six is refused, and writes nothing', async () => {
        const { status, message } = await backendClient.createAnnotationQueue({
          id: refusedId,
          projectId: project.id,
          name: `${testNamespace}-six`,
          scope: 'trace',
          automation: { enabled: true, groups: [conditionsFor(6)] },
        });
        expect(status, `six conditions answered: ${message}`).toBe(422);

        // The half that matters operationally: a 422 over a queue that was
        // created anyway leaves an automation nobody asked for running against
        // the project.
        expect(
          await backendClient.getAnnotationQueue(refusedId),
          'a refused create must leave no queue behind',
        ).toBeNull();
      });
    },
  );
});

/** A trace that looks like production SDK traffic — what automation accepts. */
async function seedSdkTrace(
  backendClient: BackendClient,
  projectName: string,
  name: string,
): Promise<string> {
  return seedTrace(backendClient, projectName, name, 'sdk');
}

/**
 * One scorable trace under a named source.
 *
 * `createTraceWithSource` rather than the SDK bridge: `source` is the axis
 * under test for two of the claims above, and the bridge writes `sdk` and
 * nothing else. `endTime` is always set — a trace left open is treated as a
 * partial write by parts of the scoring pipeline, which would make an absence
 * ambiguous between "the source was rejected" and "the trace was never
 * eligible".
 */
async function seedTrace(
  backendClient: BackendClient,
  projectName: string,
  name: string,
  source: 'sdk' | 'playground' | 'experiment',
): Promise<string> {
  const id = uuid7();
  const startTime = new Date(Date.now() - 1_000);
  await backendClient.createTraceWithSource({
    id,
    projectName,
    name,
    source,
    input: `${name} input`,
    output: `${name} output`,
    startTime,
    endTime: new Date(),
  });
  return id;
}
