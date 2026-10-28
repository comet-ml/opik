import { test, expect } from '@e2e/fixtures';
import { buildConstantScoreMetric, buildRequiredParamMetric } from '@e2e/core/metrics';
import { uuid7 } from '@e2e/core/backend';
import { LogsPage } from '@e2e/pom/logs.page';

/** What the metric scores when its required parameter arrived as `None`. */
const ABSENT_VALUE = 1;
/** …and when it arrived with the trace's real metadata. */
const PRESENT_VALUE = 2;

/** The reason the metric writes when the parameter was filled with `None`. */
const ABSENT_REASON = 'metadata=None';

/** A marker inside the seeded metadata, so "the real value arrived" is checkable. */
const METADATA_MARKER = 'marker-value';

/**
 * A code rule whose `score()` requires a parameter the trace does not carry
 * (opik#8220, OPIK-8292).
 *
 * `OnlineScoringEngine.toReplacements` drops an unresolvable mapping from the
 * argument map. When the corresponding `score()` parameter has a default that
 * is harmless; when it does not, the call used to die on a TypeError and the
 * trace ended up with NO SCORE AT ALL. The fix binds the missing argument to
 * `None` instead, so the metric runs and decides for itself.
 *
 * The estate structurally could not see this before. Every python rule it
 * builds comes from `core/metrics/python-metric-source.ts`, which renders each
 * parameter as `: Any = None` — so no existing spec can express a required one.
 * `buildRequiredParamMetric` exists for exactly this, and this is its only
 * caller.
 *
 * WHAT MAKES THE ASSERTION SHARP. The metric reports which of the two things
 * happened in the score itself: `ABSENT_VALUE` with reason "metadata=None" when
 * the parameter was filled with `None`, `PRESENT_VALUE` otherwise. So a pass
 * distinguishes three outcomes that "a score exists" collapses into one —
 * filled with None, filled with the real value, and never scored. Before the
 * fix the metadata-less trace scored nothing; a spec that only checked for the
 * presence of a score would report the regression, but not which way it went.
 *
 * TWO TRACES AND A CONTROL RULE, so a failure localises. The metadata-carrying
 * trace proves the rule and its mapping work at all; the control rule (which
 * requires only `output`, a field both traces have) proves the engine is alive
 * and reached both traces. Without them, "the metadata-less trace has no score"
 * would be satisfied by a dead evaluator.
 *
 * Deterministic and free: a python code metric returning fixed numbers, REST
 * seeding, no LLM and no provider key.
 */
test.describe(
  'Online Evaluation — required score() parameters',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    /** Online scoring is asynchronous end to end; two rules over two traces. */
    test.setTimeout(240_000);

    test(
      'a required parameter whose mapped field is absent is bound to None, not dropped',
      { tag: ['@cap:online-evaluation.python-rule-scores'] },
      async ({ project, backendClient, testNamespace, automationRulesCleanup, page }) => {
        const requiredScore = `${testNamespace}-required-param`;
        const controlScore = `${testNamespace}-control`;

        await test.step('Create the required-parameter rule and a control, before any trace', async () => {
          // Before, not after: `OnlineScoringSampler` binds the rule set at
          // trace-creation time, so a rule created later never sees these
          // traces and the whole test would go green having evaluated nothing.
          await backendClient.createAutomationRule({
            projectId: project.id,
            name: `${testNamespace}-required-rule`,
            samplingRate: 1,
            metric: buildRequiredParamMetric(
              requiredScore,
              'metadata',
              ABSENT_VALUE,
              PRESENT_VALUE,
            ),
            // `metadata` is mapped, but only one of the two traces has one —
            // which is what makes the mapping unresolvable for the other.
            arguments: { output: 'output.output', metadata: 'metadata' },
          });
          await backendClient.createAutomationRule({
            projectId: project.id,
            name: `${testNamespace}-control-rule`,
            samplingRate: 1,
            metric: buildConstantScoreMetric(controlScore),
            arguments: { output: 'output.output' },
          });
        });

        const withMetadataId = uuid7();
        const withoutMetadataId = uuid7();

        await test.step('Seed one trace with metadata and one with none', async () => {
          await backendClient.createTracesBatch({
            projectName: project.name,
            traces: [
              {
                id: withMetadataId,
                name: `${testNamespace}-with-metadata`,
                input: { q: 'capital of France' },
                output: { output: 'Paris' },
                metadata: { k: METADATA_MARKER },
              },
              {
                id: withoutMetadataId,
                name: `${testNamespace}-without-metadata`,
                input: { q: 'capital of France' },
                output: { output: 'Paris' },
                // No metadata key at all — not an empty object. An empty
                // object resolves and would be passed through, which is a
                // different case from the mapping failing to resolve.
              },
            ],
          });
        });

        await test.step('The seed really differs in the one field under test', async () => {
          // Asserted server-side before anything is polled. If ingest had
          // defaulted the missing metadata to `{}`, the mapping would resolve
          // and this spec would be testing the ordinary path under the name of
          // the absent-field one.
          const withMetadata = await backendClient.getTracePayload(withMetadataId);
          const withoutMetadata = await backendClient.getTracePayload(withoutMetadataId);
          expect(withMetadata?.metadata, 'the metadata-carrying trace').toEqual({
            k: METADATA_MARKER,
          });
          expect(
            withoutMetadata?.metadata,
            'the other trace must carry no metadata at all — an empty object would resolve',
          ).toBeNull();
        });

        await test.step('The control rule scored both traces, so the engine reached both', async () => {
          for (const [label, traceId] of [
            ['with metadata', withMetadataId],
            ['without metadata', withoutMetadataId],
          ] as const) {
            const score = await backendClient.pollTraceForFeedbackScore(traceId, controlScore, {
              timeoutMs: 120_000,
            });
            expect(score.value, `the control score on the trace ${label}`).toBe(1);
          }
        });

        await test.step('The metadata-carrying trace was scored with its real metadata', async () => {
          const score = await backendClient.pollTraceForFeedbackScore(
            withMetadataId,
            requiredScore,
            { timeoutMs: 120_000 },
          );
          expect(score.value, 'the score on the metadata-carrying trace').toBe(PRESENT_VALUE);
          // The reason carries the repr of what the metric actually received,
          // so this is what says the VALUE arrived and not merely a
          // placeholder. Asserted as a substring because the engine's own
          // serialisation of a metadata object is not this spec's subject.
          expect(
            score.reason,
            'the reason must show the metric received the seeded metadata',
          ).toContain(METADATA_MARKER);
        });

        await test.step('The metadata-LESS trace was scored too, with the parameter bound to None', async () => {
          const score = await backendClient.pollTraceForFeedbackScore(
            withoutMetadataId,
            requiredScore,
            { timeoutMs: 120_000 },
          );
          // Not merely "a score exists": the value is what says the metric ran
          // and saw `None`, rather than having somehow received a value.
          expect(
            score.value,
            'a required parameter whose mapped field is absent must be bound to None and the ' +
              'metric run anyway — before OPIK-8292 the call died binding and the trace ' +
              'carried no score at all',
          ).toBe(ABSENT_VALUE);
          expect(score.reason, 'the reason the metric wrote').toBe(ABSENT_REASON);
        });

        await test.step('The trace panel renders both scores with those values', async () => {
          // The panel is a separate projection of the same rows, and it is
          // where a user would look. Asserting only the API would leave the
          // rendering unchecked.
          const logs = new LogsPage(page);
          await logs.goto(project.id);
          const panel = await logs.openTraceById(withoutMetadataId);
          await panel.waitForFullyLoaded();
          await panel.openFeedbackScoresTab();

          expect(
            await panel.readFeedbackScoreValue(requiredScore),
            `${requiredScore} in the Feedback scores tab`,
          ).toBe(ABSENT_VALUE);
          expect(
            await panel.readFeedbackScoreValue(controlScore),
            `${controlScore} in the Feedback scores tab`,
          ).toBe(1);
        });
      },
    );
  },
);
