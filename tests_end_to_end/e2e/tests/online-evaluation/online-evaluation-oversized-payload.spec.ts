import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import { uuid7 } from '@e2e/core/backend';
import { buildConstantScoreMetric } from '@e2e/core/metrics';

/**
 * The output field the rule maps. Deliberately small and constant: the rule
 * reads only this, so the evaluator's own payload never grows, and the ONLY
 * thing the oversized span makes bigger is the message on the scoring stream —
 * which is the thing under test.
 */
const ANSWER = 'ok';

/**
 * 21,000,000 characters: one step over Jackson's `DEFAULT_MAX_STRING_LEN`
 * (20,000,000), which is the limit the online-scoring Redis codec actually ran
 * on before OPIK-8164 whatever `JACKSON_MAX_STRING_LENGTH` was configured to.
 *
 * The size is what makes this spec worth its runtime. A decode failure at that
 * ceiling happens inside Redisson's `CommandDecoder`, below the subscriber and
 * with no `StreamMessageId`, so the entry can never be acked, retried or
 * removed: the stream stops delivering every message queued behind it. The
 * regression this guards is therefore not "one span went unscored" but "online
 * scoring stopped for the whole deployment, permanently, and nothing in the UI
 * says so".
 *
 * Deliberately not larger. Just over the limit is all the property needs, and a
 * 25,000,000-character span was measured to take the ingest backend itself down
 * — 502 for every caller for around two minutes, repeatably, after two or three
 * such writes. A spec that has to break the environment to run is not a spec
 * anyone can keep. 21,000,000 was written six times in a row with the API
 * healthy throughout.
 */
const OVERSIZED_CHARS = 21_000_000;

/**
 * How long a span may take to be scored. Generous on purpose: the property
 * asserted is that scoring still HAPPENS after an oversized payload, not that
 * it happens quickly, and a tight budget would turn a slow environment into a
 * failure that reads like a wedged stream. Observed latency is ~5s.
 */
const SCORING_TIMEOUT_MS = 180_000;

/**
 * Span scope, not trace scope, and that is a deliberate narrowing.
 *
 * The two scopes ride separate Redis streams
 * (`stream_scoring_span_user_defined_metric_python` vs
 * `stream_scoring_user_defined_metric_python`) through the same codec, so
 * either proves the property. Span scope is the one that was driven end to end
 * repeatedly on a real deployment — including several oversized payloads in a
 * row — without the stream ever failing to recover, which is what makes this
 * spec safe to run against a shared environment. The trace-scope equivalent is
 * NOT written: on the environment this was verified against, a wedged
 * trace-scope stream stays wedged, so a single run of that spec would take
 * online scoring down for every other spec sharing the deployment.
 *
 * OPERATIONAL WARNING, and read it before scheduling this anywhere shared: on
 * `pr-8060.dev.comet.com` the oversized write below was measured to degrade the
 * whole deployment — ordinary small writes start answering 500/502 within
 * seconds and stay that way for around two minutes. The same payload written to
 * a project with NO automation rule is harmless (five consecutive writes, API
 * healthy throughout), and ordinary traffic against an untouched deployment is
 * clean (0 failures in 340 requests, twice) — so the trigger is specifically an
 * oversized span meeting an active online-scoring rule. Until that is explained,
 * treat this spec as needing a disposable environment or its own shard.
 */
test.describe('Online Evaluation — oversized payloads', { tag: ['@t3-nightly', '@area:online-evaluation'] }, () => {
  test('A span too large for the scoring stream does not stop the rule scoring the spans behind it', { tag: ['@cap:online-evaluation.python-rule-scores', '@cap:online-evaluation.scores-in-trace-panel'] }, async ({
    project,
    backendClient,
    testNamespace,
    page,
    automationRulesCleanup,
  }) => {
    test.setTimeout(600_000);

    const ruleName = `${testNamespace}-span-rule`;

    /**
     * One span under its own trace, written one at a time rather than through
     * `createNestedTrace`: the order in which spans reach the stream is the
     * whole subject, and a batched write settles that order itself.
     *
     * Trace and span are named apart because the panel's tree stamps
     * `trace-tree-node-<name>` on both, and a shared name would make the span
     * node ambiguous to address.
     */
    const seedSpan = async (label: string, output: Record<string, unknown>) => {
      const traceId = uuid7();
      const spanId = uuid7();
      const spanName = `${testNamespace}-${label}-span`;
      const now = new Date();
      await backendClient.createTraceWithSource({
        id: traceId,
        projectName: project.name,
        name: `${testNamespace}-${label}-trace`,
        source: 'sdk',
        input: { q: 'seed' },
        output: { answer: ANSWER },
        startTime: now,
        // No end_time would make this a partial write the trace sampler drops.
        // Irrelevant to a span-scope rule, but a half-written trace would also
        // not render in the panel this test finishes on.
        endTime: now,
      });
      await backendClient.createSpan({
        id: spanId,
        traceId,
        projectName: project.name,
        name: spanName,
        source: 'sdk',
        input: { q: 'seed' },
        output,
        startTime: now,
        endTime: now,
      });
      return { traceId, spanId, spanName };
    };

    const ruleId = await test.step('Create a 100% span-scope python rule', async () => {
      return backendClient.createAutomationRule({
        projectId: project.id,
        name: ruleName,
        type: 'span_user_defined_metric_python',
        samplingRate: 1,
        // A constant score, so a missing score means "never evaluated" rather
        // than "evaluated and disagreed" — the two failures are different and
        // only the first is what this spec is about.
        metric: buildConstantScoreMetric(ruleName, ['answer']),
        arguments: { answer: 'output.answer' },
      });
    });

    await test.step('The rule really is span-scope, enabled, and sampling everything', async () => {
      // Without this the spec could silently create a TRACE-scope rule — the
      // API's default type — and then spend ten minutes proving a property
      // about a stream it never touched. Sampling and enabled are read back for
      // the same reason: at any rate below 1 an unscored span is a legitimate
      // outcome, so the assertions further down would stop meaning anything.
      const rule = await backendClient.getAutomationRule(ruleId);
      expect(rule.type, 'the rule must score spans, not traces').toBe(
        'span_user_defined_metric_python',
      );
      expect(rule.samplingRate, 'every span must be eligible').toBe(1);
      expect(rule.enabled, 'a disabled rule scores nothing at all').toBe(true);
    });

    const before = await test.step('Control: an ordinary span is scored', async () => {
      // Establishes that the stream is alive and this rule fires BEFORE the
      // oversized payload exists. Without it, an unscored span at the end would
      // be indistinguishable from a rule that never worked here in the first
      // place.
      const seeded = await seedSpan('before', { answer: ANSWER });
      const score = await backendClient.pollSpanForFeedbackScore(seeded.spanId, ruleName, {
        timeoutMs: SCORING_TIMEOUT_MS,
      });
      expect(score.value, 'the constant metric returns 1.0 for anything it is handed').toBe(1.0);
      return seeded;
    });

    await test.step(`Seed a span carrying a ${OVERSIZED_CHARS}-character output field`, async () => {
      const oversized = await seedSpan('oversized', {
        answer: ANSWER,
        blob: 'x'.repeat(OVERSIZED_CHARS),
      });

      // The seed has to prove it discriminates. If ingest had truncated the
      // blob — or refused it, or stored it as something other than a string —
      // the span behind it would score for the most boring possible reason and
      // this spec would read as coverage forever. Asserting the exact stored
      // length is the only way to know the payload that reached the stream was
      // really over the codec's ceiling.
      //
      // Polled because the REST write answers 201 before the row is queryable.
      // The round trip doubles as the barrier that puts this span on the stream
      // ahead of the one seeded next.
      await expect
        .poll(
          async () => {
            const stored = await backendClient.getSpan(oversized.spanId);
            const output = (stored?.output ?? null) as { blob?: unknown } | null;
            return typeof output?.blob === 'string' ? output.blob.length : null;
          },
          {
            timeout: 180_000,
            intervals: [2_000, 5_000],
            message:
              `span ${oversized.spanId} never read back a full-length blob — the payload ` +
              'reaching the scoring stream was not the oversized one this spec needs',
          },
        )
        .toBe(OVERSIZED_CHARS);

      // Deliberately NOT asserted: whether this span itself gets a score.
      // Whether an over-ceiling message is dropped, decoded or refused depends
      // on the deployment's configured Jackson limits and on the opt-in
      // `REDIS_SCORING_DROP_OVERSIZED_PAYLOADS` guard, so pinning it either way
      // here would assert the environment rather than the product. What must
      // hold on every configuration is the step below.
    });

    const after = await test.step(
      'Ingest and scoring survived: an ordinary span logged afterwards is written and scored',
      async () => {
        // Two failures live in this step and they are worth telling apart when
        // one fires. A non-201 from `seedSpan` means the oversized payload took
        // ORDINARY INGEST down — nothing to do with scoring, and far worse. A
        // 201 followed by no score means the ingest path is fine and the
        // scoring stream is the thing that stopped, which is the wedge
        // OPIK-8164 is about.
        const seeded = await seedSpan('after', { answer: ANSWER });
        const score = await backendClient.pollSpanForFeedbackScore(seeded.spanId, ruleName, {
          timeoutMs: SCORING_TIMEOUT_MS,
        });
        expect(
          score.value,
          'the oversized message must not have stopped the consumer behind it',
        ).toBe(1.0);

        const detail = await backendClient.getSpan(seeded.spanId);
        expect(detail, 'the seeded span must still exist to be asserted about').not.toBeNull();
        expect(
          detail!.feedbackScores.map((s) => s.name).sort(),
          'exactly one rule ran, so exactly one score may be present',
        ).toEqual([ruleName]);
        return seeded;
      },
    );

    await test.step('Both control spans show their score in the trace panel', async () => {
      // Written through REST, read back through the UI. A backend-only pass
      // would hide the failure a user actually reports: a Feedback scores tab
      // that says "no feedback scores yet" while the API holds the score.
      const logs = new LogsPage(page);
      await logs.goto(project.id);
      await logs.waitForReady();

      for (const seeded of [before, after]) {
        const panel = await logs.openTraceById(seeded.traceId);
        await panel.waitForFullyLoaded();
        await panel.selectSpan(seeded.spanName);
        await panel.openFeedbackScoresTab();
        await expect(
          panel.feedbackScoreRow(ruleName),
          `the panel must show exactly one ${ruleName} row for ${seeded.spanName}`,
        ).toHaveCount(1);
        expect(await panel.readFeedbackScoreValue(ruleName)).toBe(1.0);
      }
    });
  });
});
