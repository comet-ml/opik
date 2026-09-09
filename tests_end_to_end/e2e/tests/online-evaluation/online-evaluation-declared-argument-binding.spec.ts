import { test, expect } from '@e2e/fixtures';
import { uuid7 } from '@e2e/core/backend';
import {
  buildMetadataBindingProbeMetric,
  buildSpansAndMetadataMetric,
} from '@e2e/core/metrics';

/** The probe metric's encoding of what `metadata` was bound to. */
const BOUND_NULL = 1.0;
const BOUND_A_VALUE = 2.0;

/**
 * The mapping the dialog ships, and the one under test. Every declared argument
 * resolves for an entity that logged all three sections; `metadata` is the one
 * that does not for the shape most traces actually have.
 */
const DEFAULT_ARGUMENTS = { input: 'input', output: 'output', metadata: 'metadata' };

const SCORING_TIMEOUT_MS = 180_000;

/**
 * `bindDeclaredArguments` itself, pinned end to end.
 *
 * No page is opened. The subject is which arguments the engine passes to the
 * metric, which the backend states in the score it stores and in the rule's log
 * stream; driving a browser to observe it second-hand would be slower and would
 * add a rendering failure mode to an assertion that has nothing to do with
 * rendering. The dialog half — that the shipped template and mapping are what a
 * user gets — is covered by
 * `online-evaluation-default-code-metric-template.spec.ts`.
 *
 * The metrics here declare `metadata` as a REQUIRED positional with no default:
 * the PRE-FIX shape of the shipped template, and therefore the shape every rule
 * saved before OPIK-8292 still holds. The frontend half of that fix
 * (`metadata: Optional[str] = None`) does nothing for those rules — only the
 * backend binding rescues them — so this signature is what protects an estate of
 * already-saved rules from a regression that the newer template would hide.
 *
 * `OnlineScoringEngineBindDeclaredArgumentsTest` pins the pure function. Nothing
 * reached it end to end, through a real Redis stream and a real sandbox, until
 * this.
 */
test.describe('Online Evaluation — declared argument binding', { tag: ['@t2-cuj', '@area:online-evaluation'] }, () => {
  test('An argument the entity carries no value for binds as null rather than being dropped, for the trace and the span scorer alike', { tag: ['@cap:online-evaluation.python-rule-scores'] }, async ({
    project,
    backendClient,
    testNamespace,
    automationRulesCleanup,
  }) => {
    test.setTimeout(600_000);

    const traceRuleName = `${testNamespace}-bind-trace`;
    const spanRuleName = `${testNamespace}-bind-span`;

    const rules = await test.step(
      'Create a trace-scope and a span-scope rule on the pre-fix signature',
      async () => {
        // The same metric source and the same mapping for both scopes: the two
        // scorers are separate code paths over separate Redis streams, so a fix
        // applied to one and not the other is a real and easy regression.
        return {
          trace: await backendClient.createAutomationRule({
            projectId: project.id,
            name: traceRuleName,
            samplingRate: 1,
            metric: buildMetadataBindingProbeMetric(traceRuleName),
            arguments: DEFAULT_ARGUMENTS,
          }),
          span: await backendClient.createAutomationRule({
            projectId: project.id,
            name: spanRuleName,
            type: 'span_user_defined_metric_python',
            samplingRate: 1,
            metric: buildMetadataBindingProbeMetric(spanRuleName),
            arguments: DEFAULT_ARGUMENTS,
          }),
        };
      },
    );

    await test.step('The two rules really are on the two scopes, enabled and sampling everything', async () => {
      // Without this, a span rule that silently defaulted to trace scope would
      // leave the span half of this test asserting the trace half twice — and at
      // any rate below 1 an unscored entity would be a legitimate outcome, which
      // would empty every assertion below.
      const trace = await backendClient.getAutomationRule(rules.trace);
      expect(trace.type).toBe('user_defined_metric_python');
      expect(trace.samplingRate, 'every trace must be eligible').toBe(1);
      expect(trace.enabled, 'a disabled rule scores nothing at all').toBe(true);

      const span = await backendClient.getAutomationRule(rules.span);
      expect(span.type).toBe('span_user_defined_metric_python');
      expect(span.samplingRate, 'every span must be eligible').toBe(1);
      expect(span.enabled, 'a disabled rule scores nothing at all').toBe(true);
    });

    const seed = async (label: string, metadata?: Record<string, unknown>) => {
      const traceId = uuid7();
      const spanId = uuid7();
      const now = new Date();
      await backendClient.createTraceWithSource({
        id: traceId,
        projectName: project.name,
        name: `${testNamespace}-${label}-trace`,
        source: 'sdk',
        input: { question: 'seed' },
        output: { output: 'seed' },
        ...(metadata ? { metadata } : {}),
        startTime: now,
        // A trace with no end_time is dropped by the sampler as a partial write.
        endTime: now,
      });
      await backendClient.createSpan({
        id: spanId,
        traceId,
        projectName: project.name,
        name: `${testNamespace}-${label}-span`,
        // Only a logging source survives `OnlineScoringSpanSampler`.
        source: 'sdk',
        input: { question: 'seed' },
        output: { output: 'seed' },
        ...(metadata ? { metadata } : {}),
        startTime: now,
        endTime: now,
      });
      return { traceId, spanId };
    };

    const seeded = await test.step(
      'Seed a trace and span without metadata, and a trace and span with it',
      async () => ({
        noMetadata: await seed('no-metadata'),
        withMetadata: await seed('with-metadata', { env: 'test' }),
      }),
    );

    await test.step('The seeds really are in the two states this test needs', async () => {
      // The four assertions below distinguish "bound null" from "carried a
      // value". If ingest stored `{}` for the absent case, or dropped the value
      // in the present one, they would still pass while proving nothing.
      await expect
        .poll(async () => (await backendClient.getTracePayload(seeded.noMetadata.traceId))?.metadata, {
          timeout: 60_000,
          intervals: [1_000, 2_000],
          message: 'the no-metadata trace never became readable',
        })
        .toBeNull();
      await expect
        .poll(async () => (await backendClient.getSpan(seeded.noMetadata.spanId))?.metadata, {
          timeout: 60_000,
          intervals: [1_000, 2_000],
          message: 'the no-metadata span never became readable',
        })
        .toBeNull();
      await expect
        .poll(
          async () => (await backendClient.getTracePayload(seeded.withMetadata.traceId))?.metadata,
          {
            timeout: 60_000,
            intervals: [1_000, 2_000],
            message: 'the with-metadata trace never became readable',
          },
        )
        .toEqual({ env: 'test' });
      await expect
        .poll(async () => (await backendClient.getSpan(seeded.withMetadata.spanId))?.metadata, {
          timeout: 60_000,
          intervals: [1_000, 2_000],
          message: 'the with-metadata span never became readable',
        })
        .toEqual({ env: 'test' });
    });

    await test.step('The trace scorer bound null for the absent metadata, and the real value for the present one', async () => {
      const absent = await backendClient.pollTraceForFeedbackScore(
        seeded.noMetadata.traceId,
        traceRuleName,
        { timeoutMs: SCORING_TIMEOUT_MS },
      );
      expect(
        absent.value,
        'a metric requiring metadata must still be callable for a trace that logged none',
      ).toBe(BOUND_NULL);
      expect(
        absent.reason,
        'the absent argument must arrive as None, not as an empty string',
      ).toBe('metadata_type=NoneType');

      const present = await backendClient.pollTraceForFeedbackScore(
        seeded.withMetadata.traceId,
        traceRuleName,
        { timeoutMs: SCORING_TIMEOUT_MS },
      );
      expect(
        present.value,
        'binding an absent argument to null must not null a value the trace really logged',
      ).toBe(BOUND_A_VALUE);
      expect(
        present.reason,
        'these arguments reach the metric as JSON strings, which is what the shipped annotation now says',
      ).toBe('metadata_type=str');
    });

    await test.step('The span scorer did the same', async () => {
      const absent = await backendClient.pollSpanForFeedbackScore(
        seeded.noMetadata.spanId,
        spanRuleName,
        { timeoutMs: SCORING_TIMEOUT_MS },
      );
      expect(
        absent.value,
        'a metric requiring metadata must still be callable for a span that logged none',
      ).toBe(BOUND_NULL);
      expect(absent.reason).toBe('metadata_type=NoneType');

      const present = await backendClient.pollSpanForFeedbackScore(
        seeded.withMetadata.spanId,
        spanRuleName,
        { timeoutMs: SCORING_TIMEOUT_MS },
      );
      expect(
        present.value,
        'binding an absent argument to null must not null a value the span really logged',
      ).toBe(BOUND_A_VALUE);
      expect(present.reason).toBe('metadata_type=str');
    });

    await test.step('Neither rule reported a failure', async () => {
      // Read after the scores landed: the scores prove both rules ran, which is
      // what makes an empty ERROR set mean "nothing failed" rather than "nothing
      // happened".
      for (const [name, ruleId] of [
        [traceRuleName, rules.trace],
        [spanRuleName, rules.span],
      ] as const) {
        const lines = await backendClient.getAutomationRuleLogs(ruleId);
        expect(
          lines.filter((l) => l.level === 'ERROR').map((l) => l.message),
          `rule '${name}' scored everything it was given, so it cannot also have failed`,
        ).toEqual([]);
      }
    });
  });

  test('A rule declaring spans keeps the injected list while binding the absent metadata to null', { tag: ['@cap:online-evaluation.python-rule-scores'] }, async ({
    project,
    backendClient,
    testNamespace,
    automationRulesCleanup,
  }) => {
    test.setTimeout(600_000);

    // The guard on the other side of the same change. `spans` is not resolved
    // from an extraction path — it is injected as a typed `List<Span>` when the
    // rule's arguments name it — so `bindDeclaredArguments` skips it. Getting
    // that skip wrong would null a typed list on every rule that declares
    // `spans`, which is a regression this fix could plausibly have introduced
    // and which no other spec would catch.
    //
    // Both behaviours are asserted from ONE call: the same invocation has to
    // keep the injected list and bind the absent metadata to None.
    const ruleName = `${testNamespace}-spans-guard`;
    const SPAN_COUNT = 2;

    const ruleId = await test.step('Create a rule declaring output, spans and metadata', async () => {
      return backendClient.createAutomationRule({
        projectId: project.id,
        name: ruleName,
        samplingRate: 1,
        metric: buildSpansAndMetadataMetric(ruleName),
        // `spans` maps to the reserved `spans` variable; the backend opts into a
        // SpanService fetch when the argument map names it.
        arguments: { output: 'output.output', spans: 'spans', metadata: 'metadata' },
      });
    });

    const traceId = uuid7();
    const now = new Date();

    const spanIds = await test.step(
      `Seed ${SPAN_COUNT} child spans, BEFORE the trace that will trigger scoring`,
      async () => {
        // Order matters and is not a style choice. `OnlineScoringSampler`
        // enqueues on trace CREATION, and the scorer fetches the trace's spans
        // when it consumes that message — so a trace written first races its own
        // spans into the fetch, and the metric is handed an empty list. That is
        // not the behaviour under test: an empty `spans` and a nulled `spans`
        // are different failures, and only the second one is this fix's.
        const ids: string[] = [];
        for (let i = 0; i < SPAN_COUNT; i++) {
          const id = uuid7();
          await backendClient.createSpan({
            id,
            traceId,
            projectName: project.name,
            name: `${testNamespace}-child-span-${i}`,
            source: 'sdk',
            input: { question: 'seed' },
            output: { output: 'seed' },
            startTime: now,
            endTime: now,
          });
          ids.push(id);
        }
        return ids;
      },
    );

    await test.step('Every span is queryable before the trace is written', async () => {
      // The barrier the ordering above needs to be worth anything: the REST
      // write answers 201 before the row is readable, so "written" is not
      // "fetchable". Without this the spec could still hand the metric a short
      // list and read the resulting value as a product failure.
      for (const spanId of spanIds) {
        await expect
          .poll(async () => (await backendClient.getSpan(spanId))?.id ?? null, {
            timeout: 60_000,
            intervals: [1_000, 2_000],
            message: `span ${spanId} never became readable`,
          })
          .toBe(spanId);
      }
    });

    await test.step('Seed the trace itself, with no metadata', async () => {
      await backendClient.createTraceWithSource({
        id: traceId,
        projectName: project.name,
        name: `${testNamespace}-spans-trace`,
        source: 'sdk',
        input: { question: 'seed' },
        output: { output: 'seed' },
        startTime: now,
        // A trace with no end_time is dropped by the sampler as a partial write.
        endTime: now,
      });
    });

    await test.step('The seeded trace really carries no metadata', async () => {
      await expect
        .poll(async () => (await backendClient.getTracePayload(traceId))?.metadata, {
          timeout: 60_000,
          intervals: [1_000, 2_000],
          message: 'the seeded trace never became readable',
        })
        .toBeNull();
    });

    await test.step('The metric received a list of both spans, and metadata as None', async () => {
      const score = await backendClient.pollTraceForFeedbackScore(traceId, ruleName, {
        timeoutMs: SCORING_TIMEOUT_MS,
      });
      expect(
        score.value,
        'the injected span list must arrive whole — a nulled `spans` cannot be counted at all',
      ).toBe(SPAN_COUNT);
      expect(
        score.reason,
        'the same call must keep `spans` a list and bind the absent `metadata` to None',
      ).toBe('spans_type=list metadata_type=NoneType');
    });

    await test.step('The rule reported no failure', async () => {
      const lines = await backendClient.getAutomationRuleLogs(ruleId);
      expect(
        lines.filter((l) => l.level === 'ERROR').map((l) => l.message),
        'the rule scored the trace, so it cannot also have failed',
      ).toEqual([]);
    });
  });
});
