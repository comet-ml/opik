import { test, expect, READABILITY_LANGUAGES } from '@e2e/fixtures';

/**
 * Four `Readability` locales scored in ONE `evaluate()` run each produce their
 * own number, and each equals the number the same text and locale produce
 * uncontended.
 *
 * `textstat` keeps its locale in MODULE state, set through `set_lang`, and the
 * evaluation engine scores metrics from a thread pool — so N locales in one run
 * with `task_threads > 1` is exactly the interleaving the module-wide lock
 * opik#8318 added exists to serialise. Two independent things can go wrong and
 * neither raises:
 *
 *  - **`set_lang` stops being applied at all**, and every locale collapses to
 *    textstat's default (`en_US`). The locales stop differing from each other.
 *  - **the lock stops holding**, and a metric computes part-way through
 *    another's locale. Each locale differs from its OWN uncontended value while
 *    the four may still look plausibly distinct.
 *
 * Both are asserted, because neither implies the other. The failure mode in
 * both cases is a silently wrong stored score with no error anywhere, which is
 * why this is worth a spec at all.
 *
 * ## API-level, with no page
 *
 * The subject is the Python SDK's own metric: the scores have to be produced by
 * a real `evaluate()` for the concurrency the lock guards to exist, and the
 * numbers are then read back from the backend. Driving the experiment's score
 * columns in a browser would add a rendering failure mode to an assertion about
 * arithmetic, and `experiments-smoke.spec.ts` already covers scores rendering
 * against an experiment.
 *
 * ## Why no absolute Flesch number is asserted
 *
 * They drift with every `textstat` / `pyphen` release, and a spec pinned to
 * them would fail on a dependency bump that broke nothing. The two comparisons
 * here are internal to one run — concurrent against serial, and the four
 * locales against each other — so both hold across a library upgrade, while
 * still failing for either regression above. The one absolute claim made is the
 * BAND: the fixture's texts were chosen so every locale lands strictly inside
 * the unclamped reading-ease range, which is what keeps "the locales differ"
 * from being vacuously satisfied by the clamp (see the fixture's header).
 *
 * ## Not claimed
 *
 * The capability's note also names an unknown locale raising
 * `MetricComputationError` with the language in the message rather than
 * pyphen's bare `KeyError`. The bridge's `/experiments/readability-evaluate`
 * route has no unknown-locale path, so nothing here reaches it and that half
 * stays uncovered. Nor does this reach the OTHER half of opik#8721 — that
 * `score()` leaves `textstat` on whatever locale it found it on — which is
 * observable only inside the scoring process and so only from a Python unit
 * test, not from anything the route returns.
 */

/**
 * How close a concurrent score must sit to its own uncontended value.
 *
 * Both are float64, computed in the same process from the same text, so a held
 * lock makes them identical rather than merely close — 1e-9 is the margin the
 * staging exploration measured them at, and it is still orders of magnitude
 * tighter than the gap between two locales (whole reading-ease points, i.e.
 * ~0.01 and up once normalised). A lock that stopped holding moves a score into
 * another locale's neighbourhood, which this catches comfortably.
 */
const LOCK_PRECISION = 9;

/**
 * How close the backend's stored average must sit to the mean of the values
 * `evaluate()` returned.
 *
 * The per-item score is stored as `Decimal(18, 9)` and the experiment's average
 * is `toDecimal64(avg(value), 9)`, so the round trip is exact to nine decimal
 * places and the comparison is only loosened for the two roundings in it.
 */
const STORED_AVERAGE_PRECISION = 8;

/** `Readability`'s value is reading ease clamped to [0, 100] and divided by 100. */
const NORMALISED_FLOOR = 0;
const NORMALISED_CEILING = 1;

test.describe(
  'Readability metric — per-locale scoring inside one evaluate() run',
  { tag: ['@t2-cuj', '@area:experiments'] },
  () => {
    test(
      'each locale scores its own value, equal to its uncontended value and distinct from every other locale, and the backend stores one average per locale',
      { tag: ['@cap:experiments.metric-locale-scoring'] },
      async ({ readabilityLocaleExperiment, backendClient }) => {
        const run = readabilityLocaleExperiment;
        const languages = [...READABILITY_LANGUAGES];

        await test.step('The run really scored every item in every locale (seed shape)', async () => {
          // The premise everything below rests on. A run that silently scored
          // three locales, or one item, would satisfy "all values are distinct"
          // and "every value matches its serial twin" over a smaller set and
          // report nothing — so the shape is pinned first, as a whole-set
          // assertion rather than a presence check.
          expect(run.languages, 'the fixture drives the four locales it declares').toEqual(
            languages,
          );
          expect(run.items, 'two seeded texts').toHaveLength(2);
          expect(
            run.scores,
            `one score per (item, locale) pair — ${run.items.length} x ${languages.length}`,
          ).toHaveLength(run.items.length * languages.length);

          // And exactly one score per pair, not merely the right number of
          // them: a run that scored one item twice in one locale and skipped
          // another pair has the same length.
          const pairs = run.scores.map((s) => `${s.key}|${s.language}`);
          expect(new Set(pairs).size, 'every (item, locale) pair appears exactly once').toBe(
            pairs.length,
          );
          for (const item of run.items) {
            for (const language of languages) {
              expect(
                pairs,
                `the run must carry a score for item '${item.key}' in ${language}`,
              ).toContain(`${item.key}|${language}`);
            }
          }

          // Each score must also be filed under the metric name its locale
          // owns. Without this the per-locale averages read back from the
          // backend could not be attributed, and two locales scoring under one
          // name would look like one locale scoring twice.
          for (const score of run.scores) {
            expect(
              score.metricName,
              `score for ${score.key} in ${score.language} is filed under its own metric name`,
            ).toBe(run.metricNameFor(score.language));
          }
        });

        await test.step('No score failed', async () => {
          // `scoring_failed` is how a metric reports it gave up — a locale
          // textstat could not honour, say. A failed score still arrives with a
          // value, so every comparison below would run over it and most would
          // pass; this is the step that refuses it.
          const failed = run.scores.filter((s) => s.scoringFailed);
          expect(
            failed.map((s) => `${s.key} in ${s.language}`),
            'no score may report scoring_failed',
          ).toEqual([]);
        });

        await test.step('Every concurrent score equals its own uncontended value (the lock)', async () => {
          // The lock assertion. The driver computes each (text, locale) alone
          // and sequentially BEFORE the concurrent run and returns it as
          // `serialValue`, in the same process and against the same textstat
          // build — so this comparison is immune to a library bump in a way an
          // absolute number is not, and it is the only assertion that can see a
          // metric scoring in a sibling's locale.
          for (const score of run.scores) {
            expect(
              score.evaluatedValue,
              `${score.key} in ${score.language}: scored ${score.evaluatedValue} inside a ` +
                `4-thread evaluate() but ${score.serialValue} alone — a difference here is the ` +
                'module-wide textstat locale leaking between concurrently scoring metrics',
            ).toBeCloseTo(score.serialValue, LOCK_PRECISION);
          }
        });

        await test.step('The four locales give four distinct values for every item (set_lang)', async () => {
          // The set_lang assertion, and the one that catches the collapse this
          // metric had before opik#8318: every locale quietly scoring as
          // en_US. Asserted per ITEM rather than over the whole set, because
          // distinctness across all eight would also be satisfied by four
          // collapsed locales whose two items merely differ from each other.
          for (const item of run.items) {
            const forItem = run.scores.filter((s) => s.key === item.key);
            expect(forItem, `four scores for item '${item.key}'`).toHaveLength(languages.length);
            const values = forItem.map((s) => s.evaluatedValue);
            expect(
              new Set(values).size,
              `item '${item.key}' must score differently in each of ${languages.join(', ')}; got ` +
                JSON.stringify(
                  Object.fromEntries(forItem.map((s) => [s.language, s.evaluatedValue])),
                ),
            ).toBe(languages.length);
          }
        });

        await test.step('Every value is strictly inside the unclamped band, so the distinctness above is not the clamp', async () => {
          // `Readability` clamps reading ease to [0, 100] before dividing by
          // 100, so text that is merely hard scores 0 in EVERY locale and the
          // step above becomes vacuously false — or, at the other end, 1. The
          // fixture's texts were measured to sit well inside the band for all
          // four locales; this asserts that rather than trusting its comment,
          // so a textstat release that pushed them onto a clamp fails here,
          // naming the cause, instead of failing the distinctness assertion
          // and reading as a locale regression.
          for (const score of run.scores) {
            expect(
              score.evaluatedValue,
              `${score.key} in ${score.language} must not be sitting on Readability's lower ` +
                'clamp — a clamped score carries no locale information at all',
            ).toBeGreaterThan(NORMALISED_FLOOR);
            expect(
              score.evaluatedValue,
              `${score.key} in ${score.language} must not be sitting on the upper clamp`,
            ).toBeLessThan(NORMALISED_CEILING);
          }
        });

        await test.step("The experiment's stored averages hold one distinct number per locale", async () => {
          // Where the run's numbers actually end up, and the only read that can
          // say they were STORED under the right names: the per-item values
          // above come from the SDK's own `evaluate()` return, so comparing
          // them to each other never leaves the client. This is the server's
          // own aggregate — the number the Experiments table renders in its
          // score columns.
          const stored = await backendClient.getExperimentFeedbackScores(run.experimentId);
          const expectedNames = languages.map((language) => run.metricNameFor(language));

          // The whole map, not just the four keys wanted: an aggregate that
          // also carried a stray score name would mean a metric wrote outside
          // its own locale, which is the shape of the bug being hunted.
          expect(
            Object.keys(stored).sort(),
            'the experiment aggregates exactly one score per seeded locale and nothing else',
          ).toEqual([...expectedNames].sort());

          for (const language of languages) {
            const name = run.metricNameFor(language);
            const forLanguage = run.scores.filter((s) => s.language === language);
            expect(forLanguage, `scores to average for ${language}`).toHaveLength(run.items.length);
            const mean =
              forLanguage.reduce((acc, s) => acc + s.evaluatedValue, 0) / forLanguage.length;
            expect(
              stored[name],
              `the backend's average for ${name} must be the mean of the values evaluate() ` +
                `returned for ${language}`,
            ).toBeCloseTo(mean, STORED_AVERAGE_PRECISION);
          }

          // And the stored averages must still be four different numbers. A
          // backend that aggregated every locale into one value, or that
          // rounded them together, would satisfy every per-locale comparison
          // above to its own (wrong) mean and only fail here.
          const storedValues = expectedNames.map((name) => stored[name]);
          expect(
            new Set(storedValues).size,
            `the four stored averages must be four distinct numbers, got ${JSON.stringify(stored)}`,
          ).toBe(expectedNames.length);
        });
      },
    );
  },
);
