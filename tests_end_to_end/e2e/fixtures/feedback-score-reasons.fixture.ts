import { test as baseTest } from './attachment-mime-types.fixture';

/**
 * The two verdict sentences an LLM judge returns as a `List[str]`.
 *
 * Both are SHORT on purpose. Joined with a space they sit comfortably inside one
 * tooltip line (the tooltip is `max-w-[50vw]`), so "renders on two lines" and
 * "renders on one line" are genuinely different observations here. Two long
 * sentences would soft-wrap either way and the rendering assertion would pass
 * whatever the white-space rule did.
 *
 * Each carries an apostrophe. Under the pre-8414 `str(value)` the field arrived
 * as a Python list literal, and the bracket/quote debris that leaves behind is
 * what the spec's "no list literal" assertion looks for.
 */
export const JUDGE_REASON_ITEMS = [
  "The answer's date is unsupported.",
  "The CEO's name is wrong.",
] as const;

/**
 * What `reason_to_text` (sdks/python .../llm_judges/parsing_helpers.py) returns
 * for {@link JUDGE_REASON_ITEMS} — `"\n".join(...)`.
 *
 * Kept as a literal join rather than imported: the parser is Python, it runs in
 * the SDK process and not in this one, and its own unit tests
 * (`test_parsing_helpers.py::TestReasonToText`) are what guard the join. What is
 * NOT covered anywhere else, and what this fixture exists for, is what happens
 * to that string afterwards — whether the backend stores the newline and
 * whether the UI renders it.
 */
export const MULTILINE_JUDGE_REASON = JUDGE_REASON_ITEMS.join('\n');

/**
 * The same string with the newline collapsed to a space — i.e. what a reader
 * sees if the rendering does NOT honour it. Never seeded; only ever asserted
 * absent.
 */
export const COLLAPSED_JUDGE_REASON = JUDGE_REASON_ITEMS.join(' ');

/** What `reason_to_text` returns for an EMPTY `List[str]`. */
export const EMPTY_LIST_JUDGE_REASON = 'No reason provided';

export interface ScoredReasonSeed {
  /** The feedback score's name, which is also its `data-row-id` in the panel. */
  scoreName: string;
  value: number;
  /** The reason as seeded — and, if nothing mangles it, as stored and rendered. */
  reason: string;
  /** How many lines a reader must see. */
  renderedLines: number;
}

export interface FeedbackScoreReasonsRef {
  traceId: string;
  projectId: string;
  /** The multi-line verdict — the behaviour under test. */
  multiline: ScoredReasonSeed;
  /** The empty-list sentinel — the single-line control. */
  sentinel: ScoredReasonSeed;
}

export interface FeedbackScoreReasonsFixtures {
  feedbackScoreReasons: FeedbackScoreReasonsRef;
}

/**
 * One trace carrying two `source: 'sdk'` feedback scores whose reasons are the
 * two shapes `reason_to_text` can produce: a newline-joined verdict and the
 * empty-list sentinel.
 *
 * Both land on the SAME trace so the panel renders them in one table, under one
 * tooltip component, at one moment. That is what makes the sentinel a control
 * rather than a second observation: "the verdict rendered on two lines" and "the
 * tooltip breaks every reason it is given" are the same sighting without a
 * reason that must stay on one line beside it.
 *
 * No teardown of its own. Feedback scores are rows on the trace and cascade with
 * it; the trace belongs to `opikTrace`, and taking it out from under that
 * fixture is not this one's to do.
 */
export const test = baseTest.extend<FeedbackScoreReasonsFixtures>({
  feedbackScoreReasons: async ({ backendClient, opikTrace, project, testNamespace }, use, testInfo) => {
    const multiline: ScoredReasonSeed = {
      scoreName: `${testNamespace}-hallucination`,
      value: 1,
      reason: MULTILINE_JUDGE_REASON,
      renderedLines: JUDGE_REASON_ITEMS.length,
    };
    const sentinel: ScoredReasonSeed = {
      scoreName: `${testNamespace}-syc-eval`,
      value: 0,
      reason: EMPTY_LIST_JUDGE_REASON,
      renderedLines: 1,
    };

    for (const seed of [multiline, sentinel]) {
      await backendClient.addTraceFeedbackScore({
        traceId: opikTrace.id,
        name: seed.scoreName,
        value: seed.value,
        reason: seed.reason,
      });
    }

    // Prove the newline actually reached storage before the browser opens.
    //
    // This is the discriminating check. A backend that trimmed, normalised or
    // re-encoded the reason would leave the UI nothing to render on two lines,
    // and the panel assertion would then fail looking exactly like a front-end
    // white-space regression. Polled rather than read once: the score write and
    // the trace's read view are eventually consistent, and `until` is what
    // distinguishes "the score row landed" from "the score row landed carrying
    // this reason" — the reason is written with the row here, but a poll that
    // returns on the name alone would still race a partial read.
    for (const seed of [multiline, sentinel]) {
      const stored = await backendClient.pollTraceForFeedbackScore(opikTrace.id, seed.scoreName, {
        until: (score) => score.reason === seed.reason,
      });
      if (stored.reason !== seed.reason) {
        throw new Error(
          `[feedbackScoreReasons fixture] score "${seed.scoreName}" on trace ${opikTrace.id} ` +
            `stored ${JSON.stringify(stored.reason)}, seeded ${JSON.stringify(seed.reason)}`,
        );
      }
    }

    const ref: FeedbackScoreReasonsRef = {
      traceId: opikTrace.id,
      projectId: project.id,
      multiline,
      sentinel,
    };

    await testInfo.attach('opik.feedbackScoreReasons', {
      body: JSON.stringify({ ...ref, namespace: testNamespace }, null, 2),
      contentType: 'application/json',
    });

    await use(ref);
  },
});

export { expect } from './attachment-mime-types.fixture';
