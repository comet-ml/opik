import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import type { TracePanelPage } from '@e2e/pom/trace-panel.page';
import type { BackendClient } from '@e2e/core/backend';

/**
 * Removing a manual feedback score from a trace and from a span — "Clear score"
 * in the Annotate panel — takes exactly that score and nothing else
 * (opik#9643 / issue-7473).
 *
 * `FeedbackScoreDAO.deleteScoreFrom` grew a `projectId` argument in this
 * release, and both of its deletes (`feedback_scores` and
 * `authored_feedback_scores`) are now `AND project_id = :project_id`, with the
 * project resolved from the entity. Two things can go wrong there and neither
 * announces itself: a project that resolves wrongly makes the delete match no
 * row, so the score comes back on the next read — and a scope that is too wide
 * takes the entity's other scores with it. Both look like a UI that "didn't
 * save" until someone reloads.
 *
 * ## Why this is not already covered
 *
 * `traces.manual-feedback-score` reads `covered: true`, and the gesture is not
 * entirely new: `trace-spans-depth.spec.ts` adds, edits and then clears a TRACE
 * score and asserts the tag disappears. What nothing asserts is the two things
 * this release could break.
 *
 * It never re-reads the trace from the server, so the whole arm is satisfied by
 * a panel that stopped RENDERING a row it still holds — which is exactly what a
 * delete whose project resolves wrongly looks like, since the client updates its
 * own cache on a 204 and only a reload or a fresh read disagrees. And it has no
 * bystander: the trace carries that one score, so a delete that took every score
 * on the trace, or in the project, passes it unchanged. Nothing anywhere clears a
 * SPAN score (`span-annotate-aged-id.spec.ts` adds one and leaves it), and the
 * backend client has no delete method at all. So the release report's "the estate
 * deletes a feedback score nowhere" is right about the span level and about the
 * client, and at trace level the click is driven while its effect is not.
 *
 * The delete is driven here from the UI — through the control a user actually
 * clicks, which sends neither an author nor a `source_queue_id` — rather than
 * through the endpoint, because the project resolution under test is the one the
 * panel's own request triggers.
 *
 * ## What makes the assertions non-trivial
 *
 * The seed is three scores the tests never touch: two on the trace, one on a
 * sibling span, plus a second span deliberately left unscored (see
 * `scoredTraceSpans`). "The score disappeared" is satisfied by a delete that
 * cleared the whole trace, or the whole project, so every test here asserts the
 * survivors by name AND value, at both surfaces, and re-reads them after a
 * reload — a client that merely stopped rendering the score, with the row still
 * stored, passes everything up to the reload and fails after it.
 *
 * Each test reads the stored state back through `GET /v1/private/traces/{id}` or
 * `GET /v1/private/spans/{id}` as well as asserting the panel, because that is
 * the difference between "the tag is gone" and "the row is gone".
 *
 * Deterministic: a seeded feedback definition, literal score values, no LLM and
 * no wall-clock dependence.
 */

/** The manual score value a test writes through the Annotate panel. */
const MANUAL_SCORE = 0.75;

/** `name=value` for every score stored on a trace, sorted so the set compares whole. */
async function storedTraceScores(
  backendClient: BackendClient,
  traceId: string,
): Promise<string[]> {
  const trace = await backendClient.getTrace(traceId);
  expect(trace, `trace ${traceId} must be readable`).not.toBeNull();
  return trace!.feedbackScores.map((score) => `${score.name}=${score.value}`).sort();
}

/** The same for a span. */
async function storedSpanScores(backendClient: BackendClient, spanId: string): Promise<string[]> {
  const span = await backendClient.getSpan(spanId);
  expect(span, `span ${spanId} must be readable`).not.toBeNull();
  return span!.feedbackScores.map((score) => `${score.name}=${score.value}`).sort();
}

/**
 * Set a score in the Annotate panel and resolve once the panel renders it.
 *
 * Polled on the rendered tag rather than on the click: the editor debounces and
 * the write is a mutation whose refetch re-renders the tag, so a reader that
 * continued immediately would be racing it — and the delete this is a precondition
 * for would then be clicked against a row that did not exist yet.
 */
async function annotate(
  panel: TracePanelPage,
  definitionName: string,
  value: number,
): Promise<void> {
  await panel.setAnnotateScore(definitionName, value);
  await expect
    .poll(() => panel.readFeedbackScoreTagValue(definitionName), {
      message: `the '${definitionName}' score tag must render ${value} before it can be cleared`,
    })
    .toBe(String(value));
}

test.describe(
  'Trace Explore — removing a manual feedback score',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      'clearing a manual TRACE score removes that score only, and it stays gone after a reload',
      { tag: ['@cap:traces.manual-feedback-score'] },
      async ({ scoredTraceSpans, feedbackDefinition, backendClient, page }) => {
        // Two ingests, a browser gesture per score and two reloads, each of
        // which may wait out a listing rate limit on staging.
        test.setTimeout(240_000);

        const manualName = feedbackDefinition.name;
        const seeded = scoredTraceSpans.traceScores;
        const seededStored = seeded.map((s) => `${s.name}=${s.value}`).sort();

        const panel = await test.step('Open the trace panel on the Annotate section', async () => {
          const logs = new LogsPage(page);
          await logs.goto(scoredTraceSpans.projectId);
          const panel = await logs.openTraceById(scoredTraceSpans.traceId);
          await panel.waitForFullyLoaded();
          await panel.openAnnotate();
          return panel;
        });

        await test.step('The seeded scores are on screen before anything is written', async () => {
          // The bystanders, asserted present at the surface the delete happens
          // on. Without this the "they survived" assertions below could be
          // satisfied by scores that never rendered in the first place.
          for (const score of seeded) {
            await expect
              .poll(() => panel.readFeedbackScoreTagValue(score.name), {
                message: `the seeded '${score.name}' tag`,
              })
              .toBe(String(score.value));
          }
        });

        await test.step('Add a manual score through the Annotate panel', async () => {
          await annotate(panel, manualName, MANUAL_SCORE);
        });

        await test.step('All three scores are stored, the manual one as a UI score', async () => {
          const stored = await backendClient.pollTraceForFeedbackScore(
            scoredTraceSpans.traceId,
            manualName,
            { until: (score) => score.value === MANUAL_SCORE },
          );
          expect(
            stored.source,
            'a score set from the panel is stored with source ui — the Annotate editor only ' +
              'takes its row value from ui-sourced scores, so the clear button it renders is ' +
              'about this row and not about the seeded sdk ones',
          ).toBe('ui');
          expect(
            await storedTraceScores(backendClient, scoredTraceSpans.traceId),
            'the trace now carries the two seeded scores and the manual one',
          ).toEqual([...seededStored, `${manualName}=${MANUAL_SCORE}`].sort());
        });

        await test.step('Clear the manual score', async () => {
          await panel.clearAnnotateScore(manualName);
          await expect(
            panel.feedbackScoreTag(manualName),
            'the cleared score has no tag left in the panel',
          ).toHaveCount(0);
        });

        await test.step('The stored trace keeps exactly the two seeded scores', async () => {
          // Polled: the delete is a mutation and the read is eventually
          // consistent, so a single read straight after the click could see
          // the pre-delete row and report a product defect that is a race.
          await expect
            .poll(() => storedTraceScores(backendClient, scoredTraceSpans.traceId), {
              timeout: 60_000,
              intervals: [1_000, 2_000, 5_000],
              message:
                'the delete must take the manual score and leave the seeded ones — a wider ' +
                'scope empties this list, a delete that matched nothing leaves the manual score',
            })
            .toEqual(seededStored);
        });

        await test.step('A reload still shows the manual score gone and the seeded ones intact', async () => {
          // The reload is the point. The panel updates its own cache on a
          // successful mutation, so every assertion above holds against a
          // delete that never reached storage; only a re-read from the server
          // can tell the two apart.
          await page.reload();
          await panel.waitForFullyLoaded();
          await panel.openAnnotate();
          for (const score of seeded) {
            await expect
              .poll(() => panel.readFeedbackScoreTagValue(score.name), {
                message: `the seeded '${score.name}' tag after the reload`,
              })
              .toBe(String(score.value));
          }
          await expect(
            panel.feedbackScoreTag(manualName),
            'the cleared score is still absent after a reload',
          ).toHaveCount(0);
        });

        await test.step('Neither span was touched', async () => {
          // The span half of the blast radius. `deleteScoreFrom` resolves a
          // project from the entity and deletes by name within it, so a scope
          // that leaked past the trace would take the span's score of a
          // different name only if it ignored the name too — and the sibling
          // span's emptiness is what shows a delete that reached the project.
          expect(
            await storedSpanScores(backendClient, scoredTraceSpans.spans[0].id),
            'the scored span keeps its own score',
          ).toEqual([`${scoredTraceSpans.spanScore.name}=${scoredTraceSpans.spanScore.value}`]);
          expect(
            await storedSpanScores(backendClient, scoredTraceSpans.spans[1].id),
            'the unscored span is still unscored',
          ).toEqual([]);
        });
      },
    );

    test(
      'clearing a manual SPAN score removes that score only, leaving the sibling span and the trace alone',
      { tag: ['@cap:traces.manual-feedback-score'] },
      async ({ scoredTraceSpans, feedbackDefinition, backendClient, page }) => {
        test.setTimeout(240_000);

        const manualName = feedbackDefinition.name;
        const [scoredSpan, targetSpan] = scoredTraceSpans.spans;
        const seededStored = scoredTraceSpans.traceScores
          .map((s) => `${s.name}=${s.value}`)
          .sort();
        const siblingStored = [
          `${scoredTraceSpans.spanScore.name}=${scoredTraceSpans.spanScore.value}`,
        ];

        const panel = await test.step('Open the trace panel and select the unscored span', async () => {
          const logs = new LogsPage(page);
          await logs.goto(scoredTraceSpans.projectId);
          const panel = await logs.openTraceById(scoredTraceSpans.traceId);
          await panel.waitForFullyLoaded();
          await panel.waitForSpanTreeNodes([scoredSpan.name, targetSpan.name]);
          await panel.selectSpan(targetSpan.name);
          await panel.openAnnotate();
          return panel;
        });

        await test.step('Add a manual score to that span', async () => {
          await annotate(panel, manualName, MANUAL_SCORE);
          const stored = await backendClient.pollSpanForFeedbackScore(targetSpan.id, manualName, {
            until: (score) => score.value === MANUAL_SCORE,
          });
          expect(stored.source, 'a score set from the panel is stored with source ui').toBe('ui');
        });

        await test.step('Clear it, and the span is left with no score at all', async () => {
          await panel.clearAnnotateScore(manualName);
          await expect(
            panel.feedbackScoreTag(manualName),
            'the cleared span score has no tag left in the panel',
          ).toHaveCount(0);
          await expect
            .poll(() => storedSpanScores(backendClient, targetSpan.id), {
              timeout: 60_000,
              intervals: [1_000, 2_000, 5_000],
              message:
                'the span the score was cleared on must end up with none — it had no other ' +
                'score, so anything left here is the delete failing to match its row',
            })
            .toEqual([]);
        });

        await test.step('The sibling span and the trace keep their own scores', async () => {
          expect(
            await storedSpanScores(backendClient, scoredSpan.id),
            'the sibling span was never annotated and keeps its seeded score',
          ).toEqual(siblingStored);
          expect(
            await storedTraceScores(backendClient, scoredTraceSpans.traceId),
            'the trace keeps both seeded scores — a span-level delete must not reach them',
          ).toEqual(seededStored);
        });

        await test.step('After a reload the span is still unscored and the sibling still is not', async () => {
          await page.reload();
          await panel.waitForFullyLoaded();
          await panel.waitForSpanTreeNodes([scoredSpan.name, targetSpan.name]);
          await panel.selectSpan(targetSpan.name);
          await panel.openAnnotate();
          await expect(
            panel.feedbackScoreTag(manualName),
            'the cleared span score is still absent after a reload',
          ).toHaveCount(0);

          await panel.selectSpan(scoredSpan.name);
          await panel.openAnnotate();
          await expect
            .poll(() => panel.readFeedbackScoreTagValue(scoredTraceSpans.spanScore.name), {
              message: "the sibling span's seeded score, re-read from the server",
            })
            .toBe(String(scoredTraceSpans.spanScore.value));
        });
      },
    );
  },
);
