import { test as baseTest } from './trace-source.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

/**
 * The locales scored in one run. Four rather than two because the regression
 * this guards is a locale being silently replaced by another — with two, a
 * swap and a correct run are one bit apart.
 *
 * `en_US` is the metric's own default, so it has to be in the set: before
 * opik#8318 every locale scored as en_US, and a set without it could not show
 * that collapse happening.
 */
export const READABILITY_LANGUAGES = ['en_US', 'de_DE', 'fr_FR', 'es_ES'] as const;

export interface ReadabilityLocaleScore {
  datasetItemId: string;
  key: string;
  language: string;
  metricName: string;
  /** Scored by `evaluate()`, every locale's metric running concurrently. */
  evaluatedValue: number;
  /** The same text and locale scored alone, sequentially, outside `evaluate()`. */
  serialValue: number;
  scoringFailed: boolean;
}

export interface ReadabilityLocaleExperimentRef {
  experimentId: string;
  experimentName: string;
  datasetId: string;
  datasetName: string;
  projectName: string;
  languages: string[];
  items: Array<{ key: string; text: string }>;
  scores: ReadabilityLocaleScore[];
  /** `readability_<language>`, the column id suffix each locale renders under. */
  metricNameFor: (language: string) => string;
}

export interface ReadabilityLocaleExperimentFixtures {
  readabilityLocaleExperiment: ReadabilityLocaleExperimentRef;
}

/**
 * French prose, deliberately mid-difficulty.
 *
 * Both constraints below are load-bearing, and both were measured against
 * textstat 0.7.13 / pyphen 0.18.1 before these texts were chosen:
 *
 * 1. **Every locale's Flesch reading ease must land strictly inside (0, 100).**
 *    `Readability` clamps reading ease to [0, 100] before normalising, so text
 *    that is merely hard scores 0.0 in EVERY locale and "the locales differ"
 *    becomes vacuously false — the assertion would be testing the clamp. The
 *    first text tried here (dense administrative French) scored -50 to -9 and
 *    collapsed all four to 0.0. These two sit at 47.7-68.5 and 64.0-83.1, so
 *    there is room on both sides for textstat to drift without clamping.
 *    The spec asserts the unclamped band rather than trusting this comment.
 *
 * 2. **The four locales must actually separate on it.** French text read with
 *    German, Spanish and English syllable rules is what makes the four scores
 *    differ; English text barely moves between locales (measured: 4 locales,
 *    1 distinct value), which would make the same assertion vacuous a second
 *    way.
 *
 * Unaccented on purpose — the texts travel through a JSON body, a dataset
 * item and a grid cell, and nothing here is testing encoding.
 */
const SEED_ITEMS: Array<{ key: string; text: string }> = [
  {
    key: 'procedures',
    text:
      'Les nouvelles procedures entrent en vigueur lundi prochain. ' +
      'Les equipes recevront une formation complete. ' +
      'Un guide pratique sera distribue a chaque participant.',
  },
  {
    key: 'rapport',
    text:
      'Le rapport annuel presente les resultats du programme. ' +
      'Chaque service a fourni ses chiffres avant la date limite. ' +
      'Le comite validera ce document en mars.',
  },
];

/**
 * How many task threads `evaluate()` scores with.
 *
 * >1 is the whole point: `textstat` holds its locale in module state, so the
 * lock opik#8318 added only has anything to serialise when the scoring pool
 * actually runs several metrics at once. At 1 this fixture would seed a run
 * that cannot fail the way the spec is looking for.
 */
const TASK_THREADS = 4;

/**
 * One experiment whose items are scored by four `Readability` metrics, one per
 * locale, in a single `evaluate()` run.
 *
 * Seeded through the bridge rather than REST, because the subject is the
 * PYTHON SDK's own metric: the scores have to be produced by a real
 * `evaluate()` for the concurrency the lock guards to exist at all. Writing
 * plausible feedback scores directly would seed the assertion's expected
 * answer.
 *
 * Teardown deletes the experiment and then the dataset it references; the
 * project fixture owns the project, and the run-prefix sweep in
 * `global-teardown.ts` is the backstop for both.
 */
export const test = baseTest.extend<ReadabilityLocaleExperimentFixtures>({
  readabilityLocaleExperiment: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const datasetName = `${testNamespace}-readability-ds`;
    const experimentName = `${testNamespace}-readability-exp`;

    const created = await sdkClient.python.readabilityEvaluate({
      project_name: project.name,
      dataset_name: datasetName,
      experiment_name: experimentName,
      dataset_description: 'one text per row, scored in four locales at once',
      items: SEED_ITEMS,
      languages: [...READABILITY_LANGUAGES],
      task_threads: TASK_THREADS,
    });

    const ref: ReadabilityLocaleExperimentRef = {
      experimentId: created.experiment_id,
      experimentName: created.experiment_name,
      datasetId: created.dataset_id,
      datasetName,
      projectName: project.name,
      languages: [...READABILITY_LANGUAGES],
      items: SEED_ITEMS,
      scores: created.scores.map((s) => ({
        datasetItemId: s.dataset_item_id,
        key: s.key,
        language: s.language,
        metricName: s.metric_name,
        evaluatedValue: s.evaluated_value,
        serialValue: s.serial_value,
        scoringFailed: s.scoring_failed,
      })),
      metricNameFor: (language: string) => `readability_${language}`,
    };

    await testInfo.attach('opik.readabilityLocaleExperiment', {
      body: JSON.stringify({ ...ref, metricNameFor: undefined }, null, 2),
      contentType: 'application/json',
    });

    try {
      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo)) {
        try {
          await backendClient.deleteExperiment(created.experiment_id);
        } catch (err) {
          console.warn(
            `[readabilityLocaleExperiment fixture] delete experiment warning for ${experimentName}:`,
            err,
          );
        }
        try {
          await backendClient.deleteDataset(created.dataset_id);
        } catch (err) {
          console.warn(
            `[readabilityLocaleExperiment fixture] delete dataset warning for ${datasetName}:`,
            err,
          );
        }
      }
    }
  },
});

export { expect } from './trace-source.fixture';
