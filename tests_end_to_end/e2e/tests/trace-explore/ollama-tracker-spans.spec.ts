import { test, expect } from '@e2e/fixtures';
import type { TrackedSpanRef } from '@e2e/core/backend';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * The model name the mock echoes back. Not a real model, and deliberately so:
 * the price table has no row for it, so no cost is attributed and nothing here
 * depends on a published rate. A span whose `model` read back as anything else
 * means the tracker stopped recording what the caller asked for.
 */
const MODEL = 'cuj-mock-model';

/** The reply the mock composes, and what a streamed call must aggregate to. */
const FULL_CONTENT = 'Hello, world!';

/** Chunks the mock streams: four carrying text, then a `done` one carrying none. */
const STREAM_CHUNKS = 5;

/**
 * The counters the mock reports, and what they must become on the span.
 * `total_tokens` is NOT reported by the provider — ollama has no such counter —
 * so it is the SDK's to derive, which is why it is listed separately here.
 */
const PROMPT_TOKENS = 11;
const COMPLETION_TOKENS = 7;
const TOTAL_TOKENS = PROMPT_TOKENS + COMPLETION_TOKENS;

/** Ollama's own token counters, which have to survive under `original_usage.*`. */
const NATIVE_USAGE_KEYS = [
  'original_usage.prompt_eval_count',
  'original_usage.eval_count',
] as const;

/**
 * Ollama's timings, which must NOT reach the usage map — and the reason this
 * list is here rather than in the one above.
 *
 * opik#8771 (2.2.94) deliberately stopped forwarding them. Ollama reports them
 * in NANOSECONDS, so any call longer than about 2.1 seconds overflows the
 * backend's 32-bit usage values and the whole span batch is rejected — which
 * presents to a user as the SDK having logged nothing at all. They are kept in
 * the span's metadata instead, which the spec asserts separately.
 *
 * Asserted as an absence rather than simply dropped from the spec, because
 * "these four keys are not in the usage map" is the whole of what opik#8771
 * changed, and a build that put them back would be reintroducing the overflow.
 */
const TIMING_KEYS_EXCLUDED_FROM_USAGE = [
  'original_usage.prompt_eval_duration',
  'original_usage.eval_duration',
  'original_usage.total_duration',
  'original_usage.load_duration',
] as const;

/** The timing fields themselves, as ollama names them in its own reply. */
const NATIVE_TIMING_FIELDS = [
  'prompt_eval_duration',
  'eval_duration',
  'total_duration',
  'load_duration',
] as const;

/** How long the spans may take to become queryable after the bridge flushed. */
const SPAN_VISIBLE_TIMEOUT_MS = 90_000;

/**
 * The spans `opik.integrations.ollama.track_ollama` writes (opik#8368).
 *
 * The estate drove no `opik.integrations.*` tracker before this: the
 * `opik-sdk-driver` had no `integrations` route and no spec imported one, so the
 * only guard was the PR's own `test_ollama.py` behind a brand-new workflow that
 * needs a live Ollama — a job that has never been green in this repo. The two
 * `ollama` hits that did exist (`pom/configuration.page.ts`,
 * `core/provider-keys.ts`) are Ollama as an **LLM provider** in the
 * Configuration list, which is a different surface from this tracker.
 *
 * Driven against a mock `/api/chat` the bridge serves itself rather than a live
 * model, and that is what makes the assertions possible at all: the chunk
 * boundaries of a streamed reply and the token counts are precisely what the
 * aggregation and the usage mapping are asserted on, and both are a function of
 * the model and the machine otherwise. See the route's own header for why the
 * mock is not a separate service.
 *
 * The failures this guards are the quiet kind. A wrong usage key surfaces as a
 * wrong token count in a span panel, and a span-per-chunk surfaces as a trace
 * that looks busy — neither gets reported quickly, and both are invisible to a
 * test that only checks a span exists.
 *
 * Updated during the 2.2.94 release pass. This spec was written against 2.2.92
 * and asserted that all six of ollama's native fields survived under
 * `original_usage.*`. opik#8771 then deliberately stopped forwarding the four
 * `*_duration` ones: ollama reports them in nanoseconds, so any call longer than
 * about 2.1 seconds overflowed the backend's 32-bit usage values and the whole
 * span batch was rejected — the SDK appearing to log nothing. The assertion is
 * now the pair it is: the two token counters must still be in `usage`, the four
 * timings must NOT be, and they must still be recorded in the span's metadata.
 * The exploration could not reach #8771 (no Ollama on staging), so this is the
 * first place the change was observed.
 */
test.describe(
  "Trace Explore — the Ollama tracker's spans",
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test.setTimeout(300_000);

    /** A span's usage map, asserted whole rather than key by key. */
    const assertMappedUsage = (span: TrackedSpanRef): void => {
      // Asserted present before being read. A `?.` or a `?? 0` here would turn
      // a tracker that reported no usage at all into a silent pass, which is
      // one of the two regressions this spec exists for.
      expect(span.usage, `span '${span.name}' must carry a usage map`).not.toBeNull();
      const usage = span.usage!;
      expect(
        {
          prompt_tokens: usage.prompt_tokens,
          completion_tokens: usage.completion_tokens,
          total_tokens: usage.total_tokens,
        },
        `span '${span.name}' must map ollama's prompt_eval_count/eval_count onto Opik's counts`,
      ).toEqual({
        prompt_tokens: PROMPT_TOKENS,
        completion_tokens: COMPLETION_TOKENS,
        total_tokens: TOTAL_TOKENS,
      });
      // The native counters are kept rather than dropped. Asserted as the set
      // of keys that are MISSING, so the failure names which ones went.
      expect(
        NATIVE_USAGE_KEYS.filter((key) => usage[key] === undefined),
        `span '${span.name}' must keep ollama's own counters under original_usage.*`,
      ).toEqual([]);
      expect(
        usage['original_usage.prompt_eval_count'],
        'the native prompt counter must survive with its own value',
      ).toBe(PROMPT_TOKENS);
      expect(
        usage['original_usage.eval_count'],
        'the native completion counter must survive with its own value',
      ).toBe(COMPLETION_TOKENS);
      // And the timings are kept OUT of it (opik#8771). Stated as the set of
      // forbidden keys that are present, so a failure names which one came back
      // rather than only that the map was the wrong size.
      expect(
        TIMING_KEYS_EXCLUDED_FROM_USAGE.filter((key) => usage[key] !== undefined),
        `span '${span.name}' must keep ollama's nanosecond timings out of the usage map — ` +
          'they overflow the backend\'s 32-bit usage values and take the whole span batch ' +
          'down with them',
      ).toEqual([]);
    };

    /**
     * The timings are still recorded, in the span's metadata.
     *
     * The other half of opik#8771, and the reason excluding them from `usage` is
     * not a loss of information: `split_dict_by_keys(result_dict, ["message"])`
     * puts everything ollama reported except the message into the metadata. A
     * spec that only asserted the absence above would be equally satisfied by a
     * build that had stopped recording the timings altogether.
     */
    const assertTimingsInMetadata = (span: TrackedSpanRef): void => {
      expect(span.metadata, `span '${span.name}' must carry metadata`).not.toBeNull();
      const metadata = span.metadata!;
      expect(
        NATIVE_TIMING_FIELDS.filter((key) => typeof metadata[key] !== 'number'),
        `span '${span.name}' records ollama's timings in its metadata, where a nanosecond ` +
          'value is not constrained to 32 bits',
      ).toEqual([]);
    };

    /** The text a chat span recorded as its answer. */
    const outputContent = (span: TrackedSpanRef): unknown =>
      ((span.output ?? {}) as { message?: { content?: unknown } }).message?.content;

    /**
     * How long the span count must stay at the expected number before it counts
     * as the whole population. Spans from one flush land together, so an extra
     * one is seconds away at most — but `expect.poll` succeeds on its FIRST
     * matching read, so without this a world where the tracker opened a span
     * per chunk would pass the instant the count passed through the expected
     * number on its way up.
     */
    const SPAN_COUNT_QUIET_MS = 10_000;

    /**
     * Poll until the project holds exactly `expected` spans and stays there,
     * then return them.
     *
     * Exact, never a lower bound: "one span per call" is the claim, and a
     * tracker that opened a span per streamed chunk would satisfy any
     * at-least-N check while being exactly the bug.
     */
    const spansOnceSettled = async (
      backendClient: { listTrackedSpans: (a: { projectId: string }) => Promise<TrackedSpanRef[]> },
      projectId: string,
      expected: number,
    ): Promise<TrackedSpanRef[]> => {
      let spans: TrackedSpanRef[] = [];
      await expect
        .poll(
          async () => {
            spans = await backendClient.listTrackedSpans({ projectId });
            return spans.length;
          },
          {
            message: `spans in the project — one per chat() call, and no more`,
            timeout: SPAN_VISIBLE_TIMEOUT_MS,
            intervals: [1_000, 2_000, 5_000],
          },
        )
        .toBe(expected);

      const firstIds = spans.map((s) => s.id).sort();
      await new Promise((resolve) => setTimeout(resolve, SPAN_COUNT_QUIET_MS));
      spans = await backendClient.listTrackedSpans({ projectId });
      expect(
        spans.map((s) => s.id).sort(),
        'the span population must be the same set after a quiet period — a count read ' +
          'on its way up would otherwise pass for the final one',
      ).toEqual(firstIds);
      return spans;
    };

    test(
      'one llm span per chat() call, with a streamed call aggregated into a single span',
      { tag: ['@cap:traces.sdk-ollama-tracker-spans'] },
      async ({ project, sdkClient, backendClient, testNamespace }) => {
        // Five calls across every axis the tracker branches on: sync vs async
        // client, streamed vs not, and the provider default vs an override.
        // The override call is non-streamed, so the (name, provider) pairs
        // below partition the five spans unambiguously.
        const result = await test.step('Make five tracked chat() calls', () =>
          sdkClient.python.trackedOllamaChats({
            project_name: project.name,
            model: MODEL,
            prompt: `${testNamespace} says hello`,
            calls: [
              { label: 'sync-plain' },
              { label: 'sync-stream', stream: true },
              { label: 'async-plain', use_async: true },
              { label: 'async-stream', use_async: true, stream: true },
              { label: 'provider-override', provider: 'my-ollama-host' },
            ],
          }),
        );

        const byLabel = new Map(result.calls.map((call) => [call.label, call]));

        await test.step('track_ollama on an already-tracked client is a no-op', () => {
          // The `opik_tracked` guard. Observing it through the spans would mean
          // asserting an absence of duplicates, which passes equally well when
          // the tracker never ran at all.
          expect(
            result.double_track_is_noop,
            'a second track_ollama must return the same client with the same bound chat',
          ).toBe(true);
        });

        await test.step('Every call returned the full reply to its caller', () => {
          // The tracker wraps the caller's own return value, so a stream
          // wrapper that swallowed a chunk would be a product bug the caller
          // feels directly — before anything reaches Opik.
          expect(
            result.calls.map((call) => `${call.label}=${call.content}`).sort(),
            'the tracker must not change what chat() answers',
          ).toEqual(
            [
              'async-plain',
              'async-stream',
              'provider-override',
              'sync-plain',
              'sync-stream',
            ].map((label) => `${label}=${FULL_CONTENT}`),
          );
        });

        await test.step('The streamed calls really arrived in several chunks', () => {
          // The barrier that makes "one span" a claim about aggregation. If the
          // mock had answered a streamed call in one chunk, every assertion
          // below would pass without the aggregator having folded anything.
          for (const label of ['sync-stream', 'async-stream']) {
            expect(
              byLabel.get(label)?.chunk_count,
              `the ${label} call must have yielded ${STREAM_CHUNKS} chunks, or ` +
                'the single-span assertion below proves nothing',
            ).toBe(STREAM_CHUNKS);
          }
          for (const label of ['sync-plain', 'async-plain', 'provider-override']) {
            expect(
              byLabel.get(label)?.chunk_count,
              `the ${label} call is not streamed and must report no chunks`,
            ).toBe(0);
          }
        });

        const spans = await test.step('Five calls produced five spans, not one per chunk', () =>
          spansOnceSettled(backendClient, project.id, 5),
        );

        await test.step('Each is an llm span, tagged ollama, naming the requested model', () => {
          expect(
            spans.map((s) => s.type).sort(),
            'every chat span is an llm span',
          ).toEqual(['llm', 'llm', 'llm', 'llm', 'llm']);
          expect(
            spans.filter((s) => JSON.stringify(s.tags) !== JSON.stringify(['ollama'])).map((s) => ({
              name: s.name,
              tags: s.tags,
            })),
            "every chat span carries exactly the ['ollama'] tag",
          ).toEqual([]);
          expect(
            spans.filter((s) => s.model !== MODEL).map((s) => ({ name: s.name, model: s.model })),
            'every chat span records the model the request asked for',
          ).toEqual([]);
          expect(
            spans.filter((s) => s.parentSpanId !== null).map((s) => s.name),
            'a top-level chat() call is a root span, with no parent',
          ).toEqual([]);
        });

        await test.step('A streamed call is named chat_stream, and the override is honoured', () => {
          // By exhaustion over (name, provider) pairs, which partition the five
          // spans: the two claims are made together because either one alone
          // could be satisfied by a span that belongs to the other call.
          const pairs = spans.map((s) => `${s.name}|${s.provider}`).sort();
          expect(
            pairs,
            'two streamed spans named chat_stream, two plain ones on the default provider, ' +
              'and one plain one on the override',
          ).toEqual([
            'chat_stream|ollama',
            'chat_stream|ollama',
            'chat|my-ollama-host',
            'chat|ollama',
            'chat|ollama',
          ]);
        });

        await test.step("Every span's output is the whole reply, concatenated", () => {
          // For the two streamed spans this is the aggregation itself: ollama
          // puts no text on the `done` chunk, so a decorator that ended the
          // span on that chunk alone records an EMPTY answer while still
          // producing one span with correct token counts.
          expect(
            spans.map((s) => `${s.name}=${String(outputContent(s))}`).sort(),
            'a streamed span must carry the concatenation of its chunks, not the done chunk',
          ).toEqual([
            `chat=${FULL_CONTENT}`,
            `chat=${FULL_CONTENT}`,
            `chat=${FULL_CONTENT}`,
            `chat_stream=${FULL_CONTENT}`,
            `chat_stream=${FULL_CONTENT}`,
          ]);
        });

        await test.step("Every span maps ollama's token counters onto Opik's", () => {
          for (const span of spans) {
            assertMappedUsage(span);
          }
        });

        await test.step("Every span keeps ollama's timings in its metadata instead", () => {
          for (const span of spans) {
            assertTimingsInMetadata(span);
          }
        });

        await test.step('Every span is attributed to the integration in its metadata', () => {
          expect(
            spans.map((s) => {
              const metadata = (s.metadata ?? {}) as Record<string, unknown>;
              return `${metadata.created_from}|${metadata.type}`;
            }),
            'the tracker stamps created_from and type on every span it writes',
          ).toEqual(Array(5).fill('ollama|ollama_chat'));
        });
      },
    );

    test(
      'a chat() call inside an @opik.track function is parented under it, and its span panel shows the provider, model and tokens',
      {
        tag: [
          '@cap:traces.sdk-ollama-tracker-spans',
          '@cap:traces.span-model-cost-tokens',
        ],
      },
      async ({ project, sdkClient, backendClient, testNamespace, page }) => {
        const parentName = `${testNamespace}-parent`;

        await test.step('Make one tracked chat() call from inside an @opik.track function', () =>
          sdkClient.python.trackedOllamaChats({
            project_name: project.name,
            model: MODEL,
            prompt: `${testNamespace} says hello`,
            calls: [{ label: 'nested', parent_name: parentName }],
          }),
        );

        const spans = await test.step('The call produced the tracked span and the chat span', () =>
          spansOnceSettled(backendClient, project.id, 2),
        );

        const { parent, chat } = await test.step('The chat span hangs off the tracked one', () => {
          const parentSpan = spans.find((s) => s.name === parentName);
          const chatSpan = spans.find((s) => s.name === 'chat');
          expect(parentSpan, `a span named '${parentName}' from the @opik.track frame`).toBeDefined();
          expect(chatSpan, "an llm span named 'chat' from the tracked client").toBeDefined();

          expect(parentSpan!.type, 'the @opik.track frame is a general span').toBe('general');
          expect(parentSpan!.parentSpanId, 'the tracked function is the root of its trace').toBeNull();
          expect(chatSpan!.type, 'the chat call is an llm span').toBe('llm');
          // The regression: an orphaned chat span still exists, still has the
          // right usage, and is simply no longer attached to the work that made
          // it — so the trace reads as two unrelated operations.
          expect(
            chatSpan!.parentSpanId,
            'the chat span must be attached to the @opik.track span, not orphaned at the root',
          ).toBe(parentSpan!.id);
          expect(
            chatSpan!.traceId,
            'both spans belong to the one trace the tracked function opened',
          ).toBe(parentSpan!.traceId);
          return { parent: parentSpan!, chat: chatSpan! };
        });

        await test.step('The nested chat span carries the same mapped usage', () => {
          assertMappedUsage(chat);
          assertTimingsInMetadata(chat);
        });

        const panel = await test.step('Open the trace and select the chat span', async () => {
          const logs = new LogsPage(page);
          await logs.goto(project.id);
          const opened = await logs.openTraceById(parent.traceId);
          await opened.waitForFullyLoaded();
          await expect(
            opened.spansCountLabel(2),
            'the tree reports the two spans the call produced',
          ).toBeVisible();
          // Deliberately addressed by the chat span's own node and not by the
          // parent's: `@opik.track` names the trace and its root span the same
          // thing, and the tree stamps `trace-tree-node-<name>` on both — so
          // `spanTreeNode(parentName)` is ambiguous by construction. The
          // parenting itself is asserted at the API above, where ids are
          // unambiguous; what is left to the UI is what only the UI renders.
          await expect(
            opened.spanTreeNode('chat'),
            'the chat span renders as its own node in the span tree',
          ).toBeVisible();
          await opened.selectSpan('chat');
          await expect
            .poll(() => new URL(page.url()).searchParams.get('span'), {
              message: 'the panel must be showing the chat span itself',
            })
            .toBe(chat.id);
          return opened;
        });

        await test.step('The panel shows the provider, the model and the total token count', async () => {
          await expect(
            panel.spanModelChip,
            'the inspect header names the provider and the model the tracker recorded',
          ).toContainText(MODEL);
          await expect(panel.spanModelChip).toContainText('ollama');
          // Anchored to the whole line so it cannot also match the
          // `original_usage.total_tokens` entry rendered in the same section.
          await expect(
            panel.panelText(new RegExp(`^total_tokens: ${TOTAL_TOKENS}$`)),
            'the derived total renders in the Token usage section',
          ).toBeVisible();
        });
      },
    );
  },
);
