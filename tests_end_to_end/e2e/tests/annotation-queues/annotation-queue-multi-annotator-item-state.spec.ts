import { test, expect } from '@e2e/fixtures';
import { SmeFlowPage } from '@e2e/pom/sme-flow.page';

/**
 * One reviewer is one annotator, however many artefacts they leave on an item
 * (opik#9474, OPIK-8546).
 *
 * `getItemState` calls a queue item **Completed** once
 * `getDistinctAnnotatorCount` reaches the queue's `annotators_per_item`, and
 * **Reviewed** while the current user has annotated it but the ceiling has not
 * been met. 9474 changed that count from "how many `value_by_author` keys are
 * there" to "how many distinct `entry.author`s are there", because the keys are
 * not people:
 *
 *  - a score submitted through the SME flow is stored under
 *    `<author>_<sourceQueueId>`;
 *  - a comment carries the bare username;
 *  - a score submitted outside the queue carries the bare username too.
 *
 * So before the fix, one person who scored **and** commented — the ordinary way
 * to work a queue — produced two keys, counted as two annotators, and tipped a
 * two-annotator item straight to Completed after a single review. The item then
 * leaves review with half its intended annotators, and nothing anywhere reports
 * it: the queue looks finished, the label is plausible, and the ground truth
 * every downstream measurement rests on is quietly built from one opinion
 * instead of two.
 *
 * Why it needs a permanent e2e test:
 *
 *  - `annotators_per_item` has **zero** occurrences in the estate, and no spec
 *    asserts any item state. The existing `annotation-queues.test.ts` cases use
 *    bare keys only, so they pass identically before and after the fix — the PR
 *    shipped no test for the composite-key case at all.
 *  - The composite key exists only because the SME flow wrote the score. It
 *    cannot be produced by seeding, which is why this drives the real reviewer
 *    UI rather than asserting over a hand-built payload: the whole defect lives
 *    in the gap between how the write keys a score and how the read counts one.
 *
 * Both surfaces, because that gap is the subject: the two artefacts' KEYS and
 * AUTHORS are asserted server-side (one entry per key, both naming the same
 * person), and the consequence is read off the reviewer's own sidebar.
 *
 * Deterministic and free. No model, no LLM judge, no sampling: the reviewer is
 * the test's own session and the scores are numbers it chooses. See
 * `multiAnnotatorQueue` for why both of the queue's configured properties are
 * asserted before the browser opens.
 *
 * ## Why the test asks which item it is on
 *
 * `SMEFlowContext` picks its first item from `shuffledItemIds`, a per-user
 * deterministic shuffle over ids that are fresh UUIDs each run, so the spec
 * cannot know which of the two it will be handed. It reads that off the viewer
 * and treats the other as the bystander — the same approach, and for the same
 * reason, as `sme-trace-queue-annotation.spec.ts`.
 *
 * ## Not claimed
 *
 * The SECOND annotator. Flipping the item to Completed needs a second identity,
 * and the score's author comes from the session, so a single-session spec
 * cannot reach it — this asserts only that one person does not satisfy a
 * two-person ceiling. Nothing here covers the `In review` lock state, the
 * completion view after the last item, or the queue-detail admin route's own
 * rendering of these states.
 */

/** The value the reviewer scores with — inside the definition's 0..1 range. */
const SCORE_VALUE = 1;

/** The value the out-of-queue score carries, deliberately different. */
const PLAIN_SCORE_VALUE = 0;

test.describe(
  'Annotation queues — multi-annotator item state (opik#9474)',
  { tag: ['@t2-cuj', '@area:annotation-queues'] },
  () => {
    // The SME route puts the items sidebar beside the viewer; below ~1280px the
    // sidebar is the first thing the layout gives up.
    test.use({ viewport: { width: 1600, height: 900 } });

    // `describe.configure` and not `test.setTimeout`: the fixture polls for a
    // readable queue and its items with a 60s budget each, which a slow
    // staging can take past the 90s default before the body is reached — so
    // the run would die in setup with the limit the body never got to raise.
    test.describe.configure({ timeout: 300_000 });

    test(
      'one reviewer who scores and comments leaves the item Reviewed, not Completed',
      { tag: ['@cap:annotation-queues.multi-annotator-item-state'] },
      async ({ multiAnnotatorQueue, feedbackDefinition, backendClient, page }) => {
        const queue = multiAnnotatorQueue;
        const sme = new SmeFlowPage(page);
        const commentText = `${queue.queueName}-review-note`;

        await test.step('Open the SME link and enter the annotation view', async () => {
          await sme.goto(queue.queueId);
          await expect(
            sme.welcomeHeading(queue.queueName),
            'the instructions page names the queue the link points at',
          ).toBeVisible({ timeout: 30_000 });
          await sme.startAnnotating();
          await sme.waitForTraceReady();
        });

        const reviewed = await test.step('Read which item the flow handed us', async () => {
          const currentId = await sme.currentTraceId(queue.traces.map((t) => t.id));
          const current = queue.traces.find((t) => t.id === currentId);
          expect(current, 'the viewer is on one of the queue’s own traces').toBeDefined();
          return current as (typeof queue.traces)[number];
        });

        const bystander = queue.traces.find((t) => t.id !== reviewed.id) as
          (typeof queue.traces)[number];

        await test.step('Both the header and the sidebar start from the unprocessed state', async () => {
          // The starting point, asserted rather than assumed: every claim below
          // is about a state CHANGE, and a queue that already read "1 remaining"
          // would make the post-review assertion true without the review.
          await expect(
            sme.itemsRemainingLabel,
            'two unprocessed items before anything is annotated',
          ).toHaveText(`${queue.traces.length} remaining`);
          await expect(
            sme.sidebarEntryState(reviewed.name),
            'an unprocessed entry carries no state tag at all',
          ).toHaveCount(0);
        });

        await test.step('Score the item through the SME flow', async () => {
          await sme.setScore(feedbackDefinition.name, SCORE_VALUE, reviewed.id);
        });

        await test.step('…and comment on it, as the same person', async () => {
          await sme.addComment(commentText, reviewed.id);
        });

        await test.step('Server-side, the two artefacts are two keys naming one person', async () => {
          // The defect's own ingredients, read at their cause. Polled because
          // both writes are debounced mutations the UI has only seen answered,
          // and the trace read is eventually consistent behind them.
          await expect
            .poll(
              async () => {
                const scores = await backendClient.getTraceScoreAuthors(reviewed.id);
                return scores.find((s) => s.name === feedbackDefinition.name)?.entries.length ?? 0;
              },
              {
                message: 'the SME score must be stored before its key is asserted',
                timeout: 90_000,
                intervals: [1_000, 2_000, 5_000],
              },
            )
            .toBe(1);

          const scores = await backendClient.getTraceScoreAuthors(reviewed.id);
          // Exactly one score, under exactly the name the queue counts: a
          // second score name would be a different annotator in the tally and
          // would make the state assertion below right for the wrong reason.
          expect(
            scores.map((s) => s.name),
            'the trace carries exactly the queue’s one feedback score',
          ).toEqual([feedbackDefinition.name]);

          const entries = scores[0].entries;
          expect(entries, 'scored once, by one key').toHaveLength(1);
          const scoreEntry = entries[0];
          // Asserted present, then used. The author is what the fixed count
          // reads, so a response that carried none is the regression itself —
          // deriving it from the key here would reproduce the old behaviour
          // inside the test.
          expect(scoreEntry.author, 'the score entry names its author').not.toBeNull();
          expect(scoreEntry.value, 'and the value the reviewer gave').toBe(SCORE_VALUE);
          // The composite key, spelled out. This is the half that cannot be
          // seeded, and the half the old count mistook for a second person.
          expect(
            scoreEntry.sourceQueueId,
            'a score submitted through the SME flow is scoped to the queue',
          ).toBe(queue.queueId);
          expect(scoreEntry.key, 'so it is keyed <author>_<queueId>').toBe(
            `${scoreEntry.author}_${queue.queueId}`,
          );

          const comments = await backendClient.listTraceComments(reviewed.id);
          expect(
            comments.map((c) => c.text),
            'the reviewer’s comment was stored, exactly once',
          ).toEqual([commentText]);
          // The claim the whole spec turns on: two artefacts, one person. Read
          // off the two responses rather than compared against a username this
          // test supplied, so it holds whoever the session belongs to.
          expect(
            comments[0].createdBy,
            'the comment is by the same person who scored — so the item has ONE annotator',
          ).toBe(scoreEntry.author);
        });

        await test.step('The sidebar reads Reviewed, and the queue still has one to go', async () => {
          await sme.showSidebarFilter('Processed');
          const entry = sme.sidebarEntry(reviewed.name);
          await expect(entry, 'the annotated item is in the Processed list, once').toHaveCount(1);
          // The exact label, not merely "not Completed": an entry that lost its
          // tag altogether would satisfy a negative assertion while telling the
          // reviewer nothing.
          await expect(
            sme.sidebarEntryState(reviewed.name),
            'one of two annotators done reads Reviewed',
          ).toHaveText('Reviewed', { timeout: 30_000 });
          await expect(
            sme.itemsRemainingLabel,
            'and the queue still reports the bystander as outstanding',
          ).toHaveText('1 remaining');
        });

        await test.step('The bystander was not touched', async () => {
          // Asserting only that the target disappeared from "To review" would
          // pass equally well if annotating one item had marked the whole queue
          // processed. Checked in the UI and server-side, by id.
          await sme.showSidebarFilter('To review');
          await expect(
            sme.sidebarEntry(bystander.name),
            'the unreviewed item is still in the To review list',
          ).toHaveCount(1);
          await expect(
            sme.sidebarEntryState(bystander.name),
            'and carries no state tag',
          ).toHaveCount(0);

          expect(
            await backendClient.getTraceScoreAuthors(bystander.id),
            `${bystander.name} carries no feedback score`,
          ).toEqual([]);
          expect(
            await backendClient.listTraceComments(bystander.id),
            `${bystander.name} carries no comment`,
          ).toEqual([]);
        });

        await test.step('The queue still holds both items', async () => {
          const detail = await backendClient.getAnnotationQueue(queue.queueId);
          expect(detail?.itemsCount, 'annotating an item does not remove it from the queue').toBe(
            queue.traces.length,
          );
        });
      },
    );

    test(
      'two value_by_author keys for the same person are still one annotator',
      { tag: ['@cap:annotation-queues.multi-annotator-item-state'] },
      async ({ multiAnnotatorQueue, feedbackDefinition, backendClient, page }) => {
        const queue = multiAnnotatorQueue;
        const sme = new SmeFlowPage(page);

        await test.step('Open the SME link and enter the annotation view', async () => {
          await sme.goto(queue.queueId);
          await expect(
            sme.welcomeHeading(queue.queueName),
            'the instructions page names the queue the link points at',
          ).toBeVisible({ timeout: 30_000 });
          await sme.startAnnotating();
          await sme.waitForTraceReady();
        });

        const reviewed = await test.step('Read which item the flow handed us', async () => {
          const currentId = await sme.currentTraceId(queue.traces.map((t) => t.id));
          const current = queue.traces.find((t) => t.id === currentId);
          expect(current, 'the viewer is on one of the queue’s own traces').toBeDefined();
          return current as (typeof queue.traces)[number];
        });

        await test.step('Score it through the SME flow, producing the queue-scoped key', async () => {
          await sme.setScore(feedbackDefinition.name, SCORE_VALUE, reviewed.id);
        });

        const author = await test.step('That score is stored under <author>_<queueId>', async () => {
          await expect
            .poll(
              async () => {
                const scores = await backendClient.getTraceScoreAuthors(reviewed.id);
                return scores.find((s) => s.name === feedbackDefinition.name)?.entries.length ?? 0;
              },
              {
                message: 'the SME score must be stored before the second one is written',
                timeout: 90_000,
                intervals: [1_000, 2_000, 5_000],
              },
            )
            .toBe(1);
          const entry = (await backendClient.getTraceScoreAuthors(reviewed.id))[0].entries[0];
          expect(entry.sourceQueueId, 'the SME score is queue-scoped').toBe(queue.queueId);
          expect(entry.author, 'and names its author').not.toBeNull();
          return entry.author as string;
        });

        await test.step('Score the SAME trace again from outside the queue', async () => {
          // The second key, the sharper half of 9474's case: the same person,
          // the same score name, no `source_queue_id` — so the row dedupes
          // separately (`LIMIT 1 BY entity_id, name, author, source_queue_id`)
          // and `value_by_author` grows a bare `<author>` key beside the
          // composite one. Written over REST rather than through a second UI,
          // because the scope is the whole difference and no UI offers both.
          await backendClient.setTraceFeedbackScores({
            projectName: queue.projectName,
            scores: [
              {
                traceId: reviewed.id,
                name: feedbackDefinition.name,
                value: PLAIN_SCORE_VALUE,
              },
            ],
          });

          await expect
            .poll(
              async () => {
                const scores = await backendClient.getTraceScoreAuthors(reviewed.id);
                return scores.find((s) => s.name === feedbackDefinition.name)?.entries.length ?? 0;
              },
              {
                message: 'the trace must carry both authored entries before the state is read',
                timeout: 90_000,
                intervals: [1_000, 2_000, 5_000],
              },
            )
            .toBe(2);
        });

        await test.step('Two keys, one author — the whole collection, not just mine', async () => {
          const scores = await backendClient.getTraceScoreAuthors(reviewed.id);
          expect(
            scores.map((s) => s.name),
            'still exactly the queue’s one feedback score',
          ).toEqual([feedbackDefinition.name]);
          const entries = scores[0].entries;
          // Compared as a whole rather than searched: a third entry from
          // somewhere else would make the tally legitimately two annotators,
          // and the state assertion below would then be wrong to expect
          // Reviewed. Sorted by key inside the reader, so the order is stable.
          expect(
            entries.map((e) => ({ key: e.key, author: e.author, sourceQueueId: e.sourceQueueId })),
            'the bare key and the queue-scoped one, both naming the same person',
          ).toEqual(
            [
              { key: author, author, sourceQueueId: null },
              { key: `${author}_${queue.queueId}`, author, sourceQueueId: queue.queueId },
            ].sort((a, b) => a.key.localeCompare(b.key)),
          );
          expect(
            new Set(entries.map((e) => e.author)).size,
            'two keys, one distinct annotator — which is what the ceiling must be compared against',
          ).toBe(1);
        });

        await test.step('The item still reads Reviewed, with one annotator to go', async () => {
          // Reloaded rather than waited out: the state is recomputed from the
          // items poll, and a reload is the shortest path to a render over the
          // payload that now carries both keys. The flow reads
          // "Resume annotating" on a partly-processed queue, which
          // `startAnnotating` accepts.
          await sme.goto(queue.queueId);
          await sme.startAnnotating();
          await sme.waitForTraceReady();

          await sme.showSidebarFilter('Processed');
          await expect(
            sme.sidebarEntry(reviewed.name),
            'the annotated item is in the Processed list, once',
          ).toHaveCount(1);
          await expect(
            sme.sidebarEntryState(reviewed.name),
            'two keys from one person do not satisfy a two-annotator ceiling',
          ).toHaveText('Reviewed', { timeout: 30_000 });
          await expect(
            sme.itemsRemainingLabel,
            'and the queue is not finished',
          ).toHaveText('1 remaining');
        });
      },
    );
  },
);
