import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';

/** The ages whose ids the storage cannot represent directly — see the fixture. */
const AGES_UNDER_TEST = ['far-future', 'beyond-ceiling'] as const;

/**
 * Annotating a span whose id sits past what the storage can represent
 * (opik#8537, OPIK-8361).
 *
 * A comment and a manual feedback score are both written against the span's
 * PROJECT, and the backend has to resolve that project from the span id —
 * `getProjectIdFromSpan`, which this PR put a week bound on. A bound that
 * misses makes the project unresolvable, so the write fails outright or
 * attaches to nothing.
 *
 * WHAT THIS ADDS. `traces.manual-feedback-score` is covered today only by
 * scoring a TRACE from the Annotate panel (`trace-spans-depth.spec.ts`), which
 * never touches `getProjectIdFromSpan` at all. The PR's own
 * `commentOnAFarFutureSpanResolvesItsProject` pins the API half in Java; what
 * it cannot reach is the render — whether the panel a user is looking at shows
 * the comment and the score once they land. Both halves are asserted here, and
 * they are genuinely different questions: the write can succeed while the panel
 * reads the span back through a second, equally bounded lookup.
 *
 * Scoped to the two ages that matter. The epoch and present-day spans are
 * exercised by `span-by-id-aged-render.spec.ts`; repeating them through a
 * browser gesture costs time and adds nothing, because the project resolution
 * they exercise is the unbounded case. The seed still contains all four, so the
 * spans not annotated here also serve as untouched bystanders — a write that
 * attached to the wrong span would show up as a score on one of them.
 *
 * Deterministic: fixed id instants, a seeded feedback definition, and a
 * literal comment string. No LLM and no wall-clock dependence.
 */
test.describe(
  'Trace Explore — annotating a span with an out-of-range id',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      'a comment and a manual score attach to a far-future span and render in the panel',
      { tag: ['@cap:traces.manual-feedback-score'] },
      async ({ idAgedSpans, feedbackDefinition, backendClient, project, page }) => {
        const scoreName = feedbackDefinition.name;
        const targets = idAgedSpans.spans.filter((s) =>
          (AGES_UNDER_TEST as readonly string[]).includes(s.label),
        );
        const untouched = idAgedSpans.spans.filter((s) => !targets.includes(s));

        expect(
          targets.map((s) => s.label),
          'the fixture must supply both out-of-range ages, or this test is not testing them',
        ).toEqual([...AGES_UNDER_TEST]);

        const panel = await test.step('Open the trace panel', async () => {
          const logs = new LogsPage(page);
          await logs.goto(project.id);
          const panel = await logs.openTraceById(idAgedSpans.traceId);
          await panel.waitForFullyLoaded();
          return panel;
        });

        for (const [index, span] of targets.entries()) {
          const commentBody = `comment on the ${span.label} span`;
          // Distinct per span so a score written against the wrong one is a
          // value mismatch rather than an invisible overwrite.
          const scoreValue = index === 0 ? 0.25 : 0.75;

          await test.step(`Annotate the ${span.label} span from the panel`, async () => {
            await panel.selectSpan(span.name);
            await panel.openAnnotate();
            await panel.addComment(commentBody);
            await panel.setAnnotateScore(scoreName, scoreValue);
            // Poll: the score write is a mutation and the tag re-renders from
            // the refetched span, so reading immediately races it.
            await expect
              .poll(() => panel.readFeedbackScoreTagValue(scoreName), {
                message: `the ${span.label} span's score tag in the panel`,
              })
              .toBe(String(scoreValue));
          });

          await test.step(`Both writes are readable back off the ${span.label} span`, async () => {
            // Server-side, by id. The panel showing them proves the client
            // round trip; this proves the rows actually resolved to THIS span's
            // project rather than being accepted and dropped.
            await expect
              .poll(() => backendClient.listSpanComments(span.id), {
                message: `comments stored on the ${span.label} span`,
              })
              .toEqual([commentBody]);

            const stored = await backendClient.getSpan(span.id);
            expect(stored, `the ${span.label} span`).not.toBeNull();
            expect(
              (stored?.feedbackScores ?? []).map((fs) => ({ name: fs.name, value: fs.value })),
              `feedback scores stored on the ${span.label} span`,
            ).toEqual([{ name: scoreName, value: scoreValue }]);
          });

          await test.step(`Reopening the ${span.label} span still shows both`, async () => {
            // Re-selected after moving away, so the panel is re-reading the
            // span rather than showing state it still held from the write.
            await panel.selectSpan(idAgedSpans.spans[0].name);
            await panel.selectSpan(span.name);
            await panel.openAnnotate();
            await expect(
              panel.commentText(commentBody),
              `the ${span.label} span's comment, re-read`,
            ).toBeVisible();
            await expect
              .poll(() => panel.readFeedbackScoreTagValue(scoreName), {
                message: `the ${span.label} span's score, re-read`,
              })
              .toBe(String(scoreValue));
          });
        }

        await test.step('The spans nobody annotated carry nothing', async () => {
          // The bystander half. Without it, a write that resolved the wrong
          // project — or the wrong span — could still satisfy every assertion
          // above, because they only ever look at the span they expect.
          for (const span of untouched) {
            expect(
              await backendClient.listSpanComments(span.id),
              `the ${span.label} span was never annotated and must carry no comment`,
            ).toEqual([]);
            const stored = await backendClient.getSpan(span.id);
            expect(
              stored?.feedbackScores ?? [],
              `the ${span.label} span was never annotated and must carry no score`,
            ).toEqual([]);
          }
        });
      },
    );
  },
);
