import OpenAI from 'openai';
import { trackOpenAI } from 'opik-openai';
import { test, expect } from '@e2e/fixtures';
import { uuid7, type TrackedSpanRef } from '@e2e/core/backend';

/**
 * `opik-openai`'s Responses-API usage mapping (opik#8782).
 *
 * `trackOpenAI` had `input_tokens` and `output_tokens` assigned to Opik's
 * `completion_tokens` and `prompt_tokens` — literally the wrong way round. The
 * span still appeared, still carried a usage map, still reported a cost; every
 * number was just attributed to the wrong bucket and therefore billed at the
 * wrong per-token rate. That is the canonical silent-wrongness failure: nothing
 * errors, and the only way to see it is to compare the counts against what the
 * provider actually reported.
 *
 * Nothing in the estate drove this. The TypeScript SDK is the suite's TRANSPORT
 * — `core/backend/client.ts` is built on `Opik` — but the integration packages
 * (`opik-openai`, `opik-gemini`, `opik-langchain`, `opik-otel`, `opik-vercel`)
 * were never imported by any spec, and `traces.span-model-cost-tokens` is about
 * server-side PRICE resolution over hand-seeded spans: its fixture writes the
 * usage map itself, so it cannot see a tracker that filled that map in wrongly.
 * The Python side's equivalent gap was closed for one tracker in 2.2.92
 * (`sdk-ollama-tracker-spans`); this is the TypeScript side's first.
 *
 * Deterministic and hermetic: `openaiResponsesStub` serves a Responses-shaped
 * payload from 127.0.0.1 with no key and no network, so both token counts and
 * the model name are fixed by the fixture. There is no LLM, no wall clock and no
 * dependence on what the workspace holds.
 *
 * **The cost assertion is the one that would have caught the bug on its own.**
 * The counters are asserted directly too, but a reader of a span panel sees the
 * money: at gpt-4o's published rates ($2.5e-06 in, $1e-05 out) this call is
 * $2.51, and with the two swapped it is $10.0025 — a 4x error on a page nobody
 * re-derives by hand. Both numbers are asserted, the second against a control
 * span this spec seeds with the counts deliberately transposed, so the test
 * proves the comparison can discriminate rather than merely asserting one side
 * of it.
 */
test.describe(
  'Trace Explore — the TypeScript OpenAI tracker\'s Responses usage',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    /** A tracked call, two flushes and an ingestion poll. */
    test.setTimeout(300_000);

    /** How long the tracked span may take to become queryable after the flush. */
    const SPAN_VISIBLE_TIMEOUT_MS = 120_000;

    /**
     * gpt-4o's published per-token rates, from the shipped price table
     * (`model_prices_and_context_window.json`). Spelled out here, as
     * `span-cost-resolution.spec.ts` spells out the rates it asserts on, so the
     * arithmetic below is pinned to the table rather than to itself.
     */
    const INPUT_RATE = 2.5e-6;
    const OUTPUT_RATE = 1e-5;

    /**
     * The one span the tracker wrote, found by exhaustion over the project.
     *
     * By exhaustion rather than by name or type: the point is that one tracked
     * call produces EXACTLY one span, so a build that wrote one per chunk, or two
     * for the request and the response, or none at all, fails here rather than
     * hiding behind whichever span was looked for.
     */
    const theOnlyTrackedSpan = (spans: TrackedSpanRef[]): TrackedSpanRef => {
      expect(
        spans.map((span) => `${span.name} (${span.type})`),
        'one tracked call wrote exactly one span',
      ).toHaveLength(1);
      return spans[0];
    };

    test(
      'maps input/output tokens the right way round, and prices the call on that mapping',
      { tag: ['@cap:traces.ts-sdk-openai-tracker-usage'] },
      async ({ openaiResponsesStub, project, backendClient, testNamespace }) => {
        const stub = openaiResponsesStub;
        const generationName = `${testNamespace}-responses`;

        await test.step('Call the stub through a tracked OpenAI client, then flush both', async () => {
          const raw = new OpenAI({
            // Not a credential: the stub never looks at it, and the OpenAI
            // client refuses to construct without one.
            apiKey: 'stub-key-unused-by-the-local-stub',
            baseURL: stub.baseUrl,
            maxRetries: 0,
          });
          const tracked = trackOpenAI(raw, {
            client: stub.opikClient,
            generationName,
          });

          const answer = await tracked.responses.create({
            model: stub.model,
            input: 'what did the provider report',
          });
          // The stub's own text, asserted before anything is read back: if the
          // call had not reached the stub at all, every assertion below would be
          // about a span that was never written, and the failure would surface as
          // an ingestion timeout rather than as a wiring problem.
          expect(answer.output_text, 'the tracked client reached the stub').toBe(stub.outputText);

          // Both flushes, in this order. `tracked.flush()` drains the tracker's
          // own queue into the Opik client; `opikClient.flush()` puts it on the
          // wire. Skipping either leaves the span unwritten, which is
          // indistinguishable from the tracker never having created one.
          await tracked.flush();
          await stub.opikClient.flush();
        });

        await test.step('Exactly one request reached the stub, on the Responses endpoint', async () => {
          expect(
            stub.requestCount(),
            'the tracked client made one upstream call, not zero and not a retry storm',
          ).toBe(1);
          // The negative half. A client that fell back to /v1/chat/completions
          // would be exercising a DIFFERENT usage shape
          // (`prompt_tokens`/`completion_tokens` upstream, which need no mapping
          // at all), so every assertion below would pass while saying nothing
          // about the Responses path this spec is named for.
          expect(
            stub.unexpectedRequests(),
            'and asked for nothing but POST /v1/responses',
          ).toEqual([]);
        });

        const span = await test.step('The tracker wrote exactly one span, on the right model', async () => {
          await expect
            .poll(
              async () =>
                (await backendClient.listTrackedSpans({ projectId: project.id })).length,
              {
                message: 'the tracked span must be queryable before it is asserted on',
                timeout: SPAN_VISIBLE_TIMEOUT_MS,
                intervals: [2_000, 2_000, 5_000],
              },
            )
            .toBe(1);

          const span = theOnlyTrackedSpan(
            await backendClient.listTrackedSpans({ projectId: project.id }),
          );
          // The model is what the server prices on, so a span priced correctly
          // for the wrong model is not the assertion this spec wants.
          expect(span.model, 'the span records the model the call named').toBe(stub.model);
          expect(span.name, 'and carries the generation name the tracker was given').toBe(
            generationName,
          );
          // Recorded, not endorsed. `trackOpenAI` types its generation span
          // `OpikSpanType.General`, where the Python `track_ollama` tracker types
          // the equivalent span `llm`. Whether the two should agree is a product
          // question this spec does not try to settle; it is asserted so that the
          // asymmetry is written down somewhere, and so a change to it arrives as
          // a review conversation rather than silently.
          expect(
            span.type,
            'the TypeScript OpenAI tracker records its generation as a general span',
          ).toBe('general');
          return span;
        });

        await test.step('The counters are on the right side of the map', async () => {
          // Required, not optional-chained. A tracker that recorded no usage at
          // all is a regression in its own right, and a `?? 0` here would turn it
          // into two passing comparisons against zero.
          expect(span.usage, 'the span carries a usage map').not.toBeNull();
          const usage = span.usage!;
          // Asserted as a whole object rather than key by key, so an extra
          // mis-mapped key is visible in the same failure as a wrong value — and
          // the native counters are in the same comparison on purpose. Keeping
          // `original_usage.input_tokens`/`output_tokens` is what makes this a
          // MAPPING rather than a rename: the provider's own words survive beside
          // Opik's, so a swap is recoverable after the fact and provable here.
          // Their presence is also what rules out the only other way the asserted
          // pair could be right — a tracker that had swapped BOTH sides.
          expect(
            usage,
            `the provider reported input_tokens=${stub.inputTokens} and ` +
              `output_tokens=${stub.outputTokens}; those must land on prompt_tokens and ` +
              'completion_tokens respectively, not the other way round, with the native ' +
              'counters kept under original_usage.*',
          ).toEqual({
            prompt_tokens: stub.inputTokens,
            completion_tokens: stub.outputTokens,
            total_tokens: stub.inputTokens + stub.outputTokens,
            'original_usage.input_tokens': stub.inputTokens,
            'original_usage.output_tokens': stub.outputTokens,
            'original_usage.total_tokens': stub.inputTokens + stub.outputTokens,
            'original_usage.input_tokens_details.cached_tokens': 0,
            'original_usage.output_tokens_details.reasoning_tokens': 0,
          });
        });

        await test.step('And the call is priced on that mapping, not on its transpose', async () => {
          const expectedCost =
            stub.inputTokens * INPUT_RATE + stub.outputTokens * OUTPUT_RATE;
          const swappedCost =
            stub.outputTokens * INPUT_RATE + stub.inputTokens * OUTPUT_RATE;

          // The two must be far apart, or this step proves nothing — which is
          // also an assertion about the MODEL being a suitable choice, since a
          // model whose input and output rates were equal could not observe the
          // defect at all.
          expect(
            swappedCost / expectedCost,
            'the chosen model\'s two rates are far enough apart that a swap is unmistakable',
          ).toBeGreaterThan(2);

          expect(span.totalEstimatedCost, 'the span was priced server-side').not.toBeNull();
          expect(
            span.totalEstimatedCost!,
            `${stub.inputTokens} x $${INPUT_RATE} + ${stub.outputTokens} x $${OUTPUT_RATE} = ` +
              `$${expectedCost}; the swap would have billed $${swappedCost}`,
          ).toBeCloseTo(expectedCost, 6);
        });

        await test.step('A span carrying the transposed counts really is priced differently', async () => {
          // The control, and the reason the cost assertion above is a real test
          // rather than a restatement of the one before it. An identical span
          // with the two counts swapped is written by hand through the backend,
          // so the server prices it by the same code path — and if the two came
          // out the same, the cost assertion could not have caught the defect no
          // matter what the tracker did.
          const controlTraceId = uuid7();
          const controlSpanId = uuid7();
          await backendClient.createTracesBatch({
            projectName: project.name,
            traces: [{ id: controlTraceId, name: `${testNamespace}-transposed-control` }],
          });
          await backendClient.createSpansBatch({
            projectName: project.name,
            spans: [
              {
                id: controlSpanId,
                traceId: controlTraceId,
                name: `${testNamespace}-transposed-control-span`,
                type: 'llm',
                model: stub.model,
                provider: 'openai',
                usage: {
                  prompt_tokens: stub.outputTokens,
                  completion_tokens: stub.inputTokens,
                  total_tokens: stub.inputTokens + stub.outputTokens,
                },
              },
            ],
          });

          const control = await test.step('Read the control span back', async () => {
            await expect
              .poll(
                async () =>
                  (await backendClient.listSpansPage({
                    projectId: project.id,
                    traceId: controlTraceId,
                  })).total,
                {
                  message: 'the control span must be queryable before it is compared',
                  timeout: SPAN_VISIBLE_TIMEOUT_MS,
                  intervals: [1_000, 2_000, 5_000],
                },
              )
              .toBe(1);
            const page = await backendClient.listSpansPage({
              projectId: project.id,
              traceId: controlTraceId,
            });
            expect(page.spans, 'the control read returned its one span').toHaveLength(1);
            return page.spans[0];
          });

          expect(control.totalEstimatedCost, 'the control was priced too').not.toBeNull();
          expect(
            control.totalEstimatedCost!,
            'the transposed control bills the same token totals at the other rates',
          ).toBeCloseTo(stub.outputTokens * INPUT_RATE + stub.inputTokens * OUTPUT_RATE, 6);
          expect(
            span.totalEstimatedCost!,
            'so the tracked span costs strictly less than its transpose — the difference the ' +
              'mapping makes, stated without reference to any published rate',
          ).toBeLessThan(control.totalEstimatedCost!);
        });
      },
    );
  },
);
