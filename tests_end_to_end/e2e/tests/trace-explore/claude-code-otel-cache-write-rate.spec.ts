import {
  test,
  expect,
  CACHE_CREATION_1H_USAGE_KEY,
  CLAUDE_CODE_CACHE_WRITE_RATES,
  CLAUDE_CODE_LLM_SPAN_NAME,
  CLAUDE_CODE_MODEL,
  CLAUDE_CODE_SCOPE,
  CLAUDE_CODE_UNMAPPED_ATTR,
  CLAUDE_CODE_UNMAPPED_VALUE,
} from '@e2e/fixtures';
import type { ClaudeCodeOtelCacheSpansRef, ClaudeCodeOtelSpanSeed } from '@e2e/fixtures';
import type { BackendClient, SpanCostRef } from '@e2e/core/backend';

/**
 * A Claude Code OTel span that reports cache-creation tokens and NO TTL bills
 * its writes at the 5-minute rate (opik#9068 / OPIK-8897).
 *
 * Claude Code sends one `cache_creation_tokens` count and never says which
 * retention it bought, so `CacheWrites.of` finds neither TTL key and attributes
 * the whole total to the 5-minute bucket — the cheaper of the model's two write
 * rates. opik#9068 arrived at that by REVERTING a change made earlier in the
 * same PR, which had priced every Claude Code write at the 1-hour rate and so
 * overcharged per-token users by 60% on the write term.
 *
 * ## Why this is not already covered
 *
 * Nothing in the estate had a Claude Code vector of any kind.
 * `span-cache-write-ttl-tier.spec.ts` drives writes that DO report a TTL,
 * `span-cache-dialect-parity.spec.ts` drives one Anthropic usage through eight
 * client dialects but states a TTL in each, and both reach the price table
 * through `POST /v1/private/spans` with a finished `usage` map. None of them
 * can see this, because the no-TTL default is a property of the usage SHAPE and
 * of the ingestion that produces it.
 *
 * ## Why this is API-only, deliberately
 *
 * The amounts are $0.0043095 and $0.0044220. Both are under `formatCost`'s
 * `<$0.01` floor, so the trace panel renders the two as the same string —
 * confirmed on screen during the 2.2.131 staging pass. A UI assertion could not
 * tell the 5-minute rate from the 1-hour one here, which is the entire subject,
 * so this spec opens no page. (The panel's rendering of cache-write costs is
 * covered, at amounts that clear the floor, by `span-cache-write-ttl-tier`.)
 *
 * ## Why the control is load-bearing
 *
 * "A write with no TTL bills at the 5-minute rate" and "the 1-hour rate is
 * never applied to anything" give the identical number on the vector under
 * test, and only the second is a bug. The control differs from it in one
 * attribute — an explicit `cache_creation.ephemeral_1h_input_tokens` — and must
 * come out dearer by exactly the written tokens times the rate difference.
 *
 * Deterministic: every count is fixed by the fixture, no provider is reached,
 * and the export carries no `gen_ai.usage.cost`, so the price is the server's.
 */

/**
 * The one span the fixture exported onto `seed`'s trace, read back.
 *
 * The trace is asserted to hold EXACTLY one span rather than merely to contain
 * a matching name: Claude Code names every LLM call the same thing, so a read
 * that also returned a sibling would have no way to say which cost belonged to
 * which vector — and each vector having its own trace is what makes this
 * unambiguous in the first place.
 */
async function readVectorSpan(
  backendClient: BackendClient,
  projectId: string,
  seed: ClaudeCodeOtelSpanSeed,
): Promise<SpanCostRef> {
  await expect
    .poll(
      async () =>
        (await backendClient.listSpanCosts({ projectId, traceId: seed.traceId })).length,
      {
        message: `the ${seed.key} export must land exactly one span on its own trace`,
        timeout: 60_000,
        intervals: [500, 1_000, 2_000],
      },
    )
    .toBe(1);

  const spans = await backendClient.listSpanCosts({ projectId, traceId: seed.traceId });
  expect(spans, `the ${seed.key} trace carries exactly the exported span`).toHaveLength(1);
  expect(spans[0].name, 'exported under the name Claude Code gives an LLM call').toBe(
    CLAUDE_CODE_LLM_SPAN_NAME,
  );
  return spans[0];
}

/** Both vectors' spans, by key, once each has landed. */
async function readVectors(
  backendClient: BackendClient,
  projectId: string,
  seed: ClaudeCodeOtelCacheSpansRef,
): Promise<Map<string, SpanCostRef>> {
  const entries: Array<[string, SpanCostRef]> = [];
  for (const vector of seed.spans) {
    entries.push([vector.key, await readVectorSpan(backendClient, projectId, vector)]);
  }
  return new Map(entries);
}

/**
 * The vector seeded under `key`, asserted present before it is used.
 *
 * A vector the fixture failed to seed would otherwise surface as an
 * `undefined` the arithmetic below had to code around, which is the shape that
 * turns a broken seed into a green run.
 */
function vectorFor(seed: ClaudeCodeOtelCacheSpansRef, key: string): ClaudeCodeOtelSpanSeed {
  const vector = seed.spans.find((s) => s.key === key);
  expect(vector, `the fixture must seed a '${key}' vector`).toBeDefined();
  return vector!;
}

/**
 * The cost the server attributed, asserted present first.
 *
 * Every vector here is on a model the price table resolves, so an absent cost
 * IS the regression — and a `?? 0` would report it as a comparison between two
 * zeros.
 */
function costOf(byKey: Map<string, SpanCostRef>, key: string): number {
  const span = byKey.get(key);
  expect(span, `the ${key} span was read back`).toBeDefined();
  expect(
    span!.totalEstimatedCost,
    `${key} must be priced server-side — an absent cost is a failure, not a zero`,
  ).not.toBeNull();
  return span!.totalEstimatedCost!;
}

/**
 * Both vectors really arrived as Claude Code spans, with the usage map the
 * price calculator reads.
 *
 * This runs before any cost is compared, and it is what stops the pricing test
 * passing for the wrong reason. The cache-write amount can only be right if the
 * count landed under `cache_creation_input_tokens` — a count left under Claude
 * Code's own short `cache_creation_tokens` name would be billed nothing while
 * the span still looked complete — and the whole map is asserted as a strict
 * equality, so the no-TTL vector carrying no TTL key AT ALL is part of the
 * claim rather than an assumption behind it.
 */
function assertIngestedShape(
  byKey: Map<string, SpanCostRef>,
  seed: ClaudeCodeOtelCacheSpansRef,
): void {
  for (const vector of seed.spans) {
    const span = byKey.get(vector.key);
    expect(span, `the ${vector.key} span was read back`).toBeDefined();

    expect(span!.model, `${vector.key}: the model attribute reached the span`).toBe(
      CLAUDE_CODE_MODEL,
    );
    // Never reported by Claude Code — `OpenTelemetryMapper` hard-sets it for
    // any `claude_code.`-prefixed span. Without it the price lookup has no
    // provider at all and the span bills nothing, so this is the premise of
    // every amount below.
    expect(
      span!.provider,
      `${vector.key}: a Claude Code span is attributed to anthropic, which no attribute carries`,
    ).toBe('anthropic');

    expect(span!.usage, `${vector.key}: usage was ingested`).not.toBeNull();
    expect(
      span!.usage,
      `${vector.key}: Claude Code's short usage names must be normalised to the ones ` +
        'SpanCostCalculator reads, and nothing else may appear in the map',
    ).toEqual(vector.storedUsage);
  }
}

test.describe(
  'Span cost — Claude Code OTel cache writes',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      'a Claude Code export lands as an LLM span whose short usage names are normalised, with its session attributes kept out of input',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ claudeCodeOtelCacheSpans, project, backendClient }) => {
        const byKey = await test.step('Read both exported spans back', async () =>
          readVectors(backendClient, project.id, claudeCodeOtelCacheSpans));

        await test.step('Each span carries the model, the hard-set provider and the normalised usage map', async () => {
          assertIngestedShape(byKey, claudeCodeOtelCacheSpans);
        });

        await test.step('…and is typed as an LLM call, attributed to the Claude Code scope', async () => {
          for (const vector of claudeCodeOtelCacheSpans.spans) {
            const payload = await backendClient.getSpanPayload(byKey.get(vector.key)!.id);
            expect(payload, `${vector.key}: the span is readable by id`).not.toBeNull();
            // `type` comes from the usage and model rules' own `spanType`, so a
            // rule set that stopped matching would leave this `general` while
            // the span still existed.
            expect(payload!.type, `${vector.key}: typed from the Claude Code rules`).toBe('llm');
            expect(
              payload!.metadata?.integration,
              `${vector.key}: the instrumentation scope is recorded as the integration`,
            ).toBe(CLAUDE_CODE_SCOPE);

            // `session.id` is Claude Code's thread grouping key, and its rule
            // set moves it to `metadata.thread_id` rather than leaving it
            // alone — so a session that stopped being read would scatter one
            // Claude Code conversation across unthreaded spans.
            expect(
              payload!.metadata?.thread_id,
              `${vector.key}: session.id becomes the span's thread_id`,
            ).toBe(vector.sessionId);

            // The default-bucket choice, which is the reason Claude Code has a
            // rule set of its own: for these spans an UNMAPPED attribute goes
            // to METADATA, where for every other producer it goes to INPUT.
            // Claude Code puts a lot of session and config attributes on every
            // span, and in `input` they are rendered as the model's prompt.
            expect(
              payload!.metadata?.[CLAUDE_CODE_UNMAPPED_ATTR],
              `${vector.key}: an attribute no rule names lands in metadata`,
            ).toBe(CLAUDE_CODE_UNMAPPED_VALUE);
            expect(
              payload!.input ?? {},
              `${vector.key}: …and not in input, which is the whole point of the ` +
                'integration having its own default bucket',
            ).not.toHaveProperty(CLAUDE_CODE_UNMAPPED_ATTR);
          }
        });
      },
    );

    test(
      'a cache write reported with no TTL bills at the 5-minute rate, and an explicit 1-hour write beside it is dearer by exactly the rate difference',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ claudeCodeOtelCacheSpans, project, backendClient }) => {
        const byKey = await test.step('Read both exported spans back', async () =>
          readVectors(backendClient, project.id, claudeCodeOtelCacheSpans));

        await test.step('Both vectors really carry the usage they claim (seed shape)', async () => {
          // Without this, "the 1h key never arrived" and "a 1h write bills at
          // the 5m rate" are the same observation: the control's extra
          // attribute has to be shown to have reached the stored usage before
          // the amounts below mean anything.
          assertIngestedShape(byKey, claudeCodeOtelCacheSpans);
        });

        const noTtl = vectorFor(claudeCodeOtelCacheSpans, 'no-ttl');
        const oneHour = vectorFor(claudeCodeOtelCacheSpans, 'explicit-1h');

        await test.step('The no-TTL write is billed at the 5-minute rate, to the cent of the table', async () => {
          // The absolute amount, not just the ordering: $0.0043095 is the
          // 5-minute answer and $0.004422 the 1-hour one, and the whole point
          // of this vector is which of the two a report with no TTL gets.
          expect(costOf(byKey, noTtl.key), `${noTtl.key}: ${noTtl.workings}`).toBeCloseTo(
            noTtl.expectedCost,
            7,
          );
        });

        await test.step('The explicit 1-hour control is billed at the dearer rate', async () => {
          expect(costOf(byKey, oneHour.key), `${oneHour.key}: ${oneHour.workings}`).toBeCloseTo(
            oneHour.expectedCost,
            7,
          );
        });

        await test.step('…and the gap between them is the written tokens at the difference between the two rates', async () => {
          // What makes the pair evidence rather than two independent numbers: a
          // build that stopped applying the 1-hour rate anywhere would satisfy
          // the no-TTL amount above and fail here.
          const premium = costOf(byKey, oneHour.key) - costOf(byKey, noTtl.key);
          expect(
            premium,
            'the only difference between the two spans is the reported TTL, so the only ' +
              'difference in the bill is the write rate it selects',
          ).toBeCloseTo(
            claudeCodeOtelCacheSpans.cacheCreationTokens *
              (CLAUDE_CODE_CACHE_WRITE_RATES.oneHour -
                CLAUDE_CODE_CACHE_WRITE_RATES.fiveMinutes),
            7,
          );
          expect(
            premium,
            'a no-TTL report must be the CHEAPER of the two — pricing it as a 1-hour ' +
              'write is the 60% overcharge opik#9068 reverted',
          ).toBeGreaterThan(0);
        });

        await test.step('The no-TTL vector never acquired a 1-hour key of its own', async () => {
          // Asserted on its own, beside the strict map equality above, because
          // it is the single fact the amount depends on and the one a reader of
          // a failure needs named.
          expect(
            byKey.get(noTtl.key)!.usage,
            'a Claude Code report carries no retention at all; inventing one is what ' +
              'billed these writes at the 1-hour rate',
          ).not.toHaveProperty(CACHE_CREATION_1H_USAGE_KEY);
          expect(
            byKey.get(oneHour.key)!.usage?.[CACHE_CREATION_1H_USAGE_KEY],
            'the control does carry one, so the absence above is a fact about the vector ' +
              'rather than about the ingestion path',
          ).toBe(claudeCodeOtelCacheSpans.cacheCreationTokens);
        });
      },
    );
  },
);
