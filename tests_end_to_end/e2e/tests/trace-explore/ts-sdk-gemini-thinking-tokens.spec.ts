import { GoogleGenAI } from '@google/genai';
import { trackGemini } from 'opik-gemini';
import { test, expect } from '@e2e/fixtures';
import { uuid7, type TrackedSpanRef } from '@e2e/core/backend';

/**
 * `opik-gemini` folds Gemini's tool-use tokens into `prompt_tokens` and its
 * THINKING tokens into `completion_tokens` (opik#8783).
 *
 * Gemini reports five counters, not two. `promptTokenCount` and
 * `candidatesTokenCount` are the obvious pair, but the provider bills
 * `toolUsePromptTokenCount` as input and `thoughtsTokenCount` as output, and
 * neither is included in the obvious pair — which is why the provider's own
 * `totalTokenCount` does not equal their sum. A tracker that recorded only the
 * obvious pair under-counts this call by 35 tokens and prices it accordingly.
 *
 * That is the canonical silent-wrongness failure for a usage mapping: the span
 * appears, carries a usage map, reports a cost, and every number is simply too
 * small. Nobody re-derives a token count by hand, and on a thinking model the
 * missing bucket is the EXPENSIVE one — thoughts are billed at the output rate,
 * which on `gemini-2.5-flash` is more than eight times the input rate.
 *
 * ## Why nothing in the estate covers it
 *
 * `opik-gemini` is imported by no spec. The only integration package the estate
 * drives is `opik-openai`, on one of its two endpoints, and
 * `traces.span-model-cost-tokens` is about server-side PRICE resolution over
 * hand-seeded spans: its fixtures write the usage map themselves, so they
 * cannot see a tracker that filled that map in wrongly. Nothing anywhere would
 * notice this mapping regressing.
 *
 * ## Deterministic and hermetic
 *
 * `geminiThinkingStub` serves a fixed `generateContent` response from 127.0.0.1
 * with no key and no network, so all five counters and the model name are fixed
 * by the fixture. A live Gemini call cannot be asked to report a chosen
 * `thoughtsTokenCount`, so there is no non-stub way to pin this.
 *
 * ## The cost is what makes it a real test rather than a restatement
 *
 * The counters are asserted directly, but a reader of a span panel sees the
 * money — so the resolved price is asserted too, against a control span this
 * spec seeds BY HAND with the naive two-counter mapping. If the two priced the
 * same, the cost assertion could not have caught the defect no matter what the
 * tracker did.
 */
test.describe(
  "Trace Explore — the TypeScript Gemini tracker's thinking and tool-use tokens",
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    /** A tracked call, two flushes and two ingestion polls. */
    test.setTimeout(300_000);

    /** How long a span may take to become queryable after the flush. */
    const SPAN_VISIBLE_TIMEOUT_MS = 120_000;

    /**
     * `gemini-2.5-flash`'s published per-token rates, from the shipped price
     * table. Spelled out here, as the sibling cost specs spell out the rates
     * they assert on, so the arithmetic below is pinned to the table rather
     * than to itself. The `gemini/` and the bare row carry identical rates, so
     * the amount does not depend on which the resolver reaches.
     */
    const INPUT_RATE = 3e-7;
    const OUTPUT_RATE = 2.5e-6;

    /**
     * The provider name the tracked span arrives under, and the one
     * `CostService` maps to the `gemini/`-keyed price rows.
     *
     * Named here because it is used twice: asserted on the tracked span, and
     * written onto the hand-seeded control, which must differ from the tracked
     * span in the two counters and in nothing else.
     */
    const GEMINI_PRICE_PROVIDER = 'google_ai';

    /**
     * The one span the tracker wrote, found by exhaustion over the project.
     *
     * By exhaustion rather than by name: one tracked call must produce EXACTLY
     * one span, so a build that also emitted a child span per part, or a
     * `function_call:` child for a response that declared none, fails here
     * rather than hiding behind whichever span was looked for.
     */
    const theOnlyTrackedSpan = (spans: TrackedSpanRef[]): TrackedSpanRef => {
      expect(
        spans.map((span) => `${span.name} (${span.type})`),
        'one tracked call wrote exactly one span',
      ).toHaveLength(1);
      return spans[0];
    };

    test(
      'tool-use tokens are billed as input and thinking tokens as output, and the call is priced on that',
      { tag: ['@cap:traces.ts-sdk-gemini-tracker-usage'] },
      async ({ geminiThinkingStub, project, backendClient, testNamespace }) => {
        const stub = geminiThinkingStub;
        const generationName = `${testNamespace}-gemini-thinking`;

        /** The two buckets the mapping must produce, from the stub's counters. */
        const expectedPromptTokens =
          stub.usage.promptTokenCount + stub.usage.toolUsePromptTokenCount;
        const expectedCompletionTokens =
          stub.usage.candidatesTokenCount + stub.usage.thoughtsTokenCount;

        await test.step("The stub's counters really can observe the fold", async () => {
          // Load-bearing, and asserted before anything is driven. The tracker
          // only folds the thinking tokens in when the provider's own total
          // disagrees with the naive pair — so a stub whose total happened to
          // equal `promptTokenCount + candidatesTokenCount` would skip that
          // branch entirely and this spec would go green having exercised
          // nothing. It also rules out the degenerate case where both folds are
          // zero.
          expect(
            stub.usage.toolUsePromptTokenCount,
            'there are tool-use tokens to fold into the prompt bucket',
          ).toBeGreaterThan(0);
          expect(
            stub.usage.thoughtsTokenCount,
            'there are thinking tokens to fold into the completion bucket',
          ).toBeGreaterThan(0);
          expect(
            stub.usage.totalTokenCount,
            "the provider's total must differ from the naive pair, or the fold never runs",
          ).not.toBe(stub.usage.promptTokenCount + stub.usage.candidatesTokenCount);
          expect(
            stub.usage.totalTokenCount,
            "and it must be the sum of all four, so the folded buckets add up to the provider's own total",
          ).toBe(expectedPromptTokens + expectedCompletionTokens);
        });

        await test.step('Call the stub through a tracked Gemini client, then flush both', async () => {
          const ai = new GoogleGenAI({
            // Not a credential: the stub never looks at it, and the client
            // refuses to construct without one.
            apiKey: 'stub-key-unused-by-the-local-stub',
            httpOptions: { baseUrl: stub.baseUrl },
          });
          // An explicit project-scoped Opik client, not the `projectName`
          // option: that option is carried on the tracker config and never
          // reaches the client, so a tracker given only a name logs to Default
          // Project — a shared project where the span would be
          // indistinguishable from the tracker having written nothing, and one
          // this suite must not be writing into.
          const tracked = trackGemini(ai, {
            client: stub.opikClient,
            generationName,
          });

          const answer = await tracked.models.generateContent({
            model: stub.model,
            contents: 'what did the provider report',
          });
          // The stub's own text, asserted before anything is read back: if the
          // call had not reached the stub, every assertion below would be about
          // a span that was never written, and the failure would surface as an
          // ingestion timeout rather than as a wiring problem.
          expect(answer.text, 'the tracked client reached the stub').toBe(stub.text);

          // Both flushes, in this order. `tracked.flush()` drains the tracker's
          // own queue into the Opik client; `opikClient.flush()` puts it on the
          // wire. Skipping either leaves the span unwritten.
          await tracked.flush();
          await stub.opikClient.flush();
        });

        await test.step('Exactly one request reached the stub, on generateContent', async () => {
          expect(
            stub.requestCount(),
            'the tracked client made one upstream call, not zero and not a retry storm',
          ).toBe(1);
          // The negative half. `:streamGenerateContent` is a different path
          // through a different accumulator, so a client that took it would
          // leave every assertion below passing while saying nothing about the
          // mapping this spec is named for.
          expect(
            stub.unexpectedRequests(),
            'and asked for nothing but POST *:generateContent on the stubbed model',
          ).toEqual([]);
        });

        const span = await test.step('The tracker wrote exactly one span, on the right model', async () => {
          await expect
            .poll(
              async () => (await backendClient.listTrackedSpans({ projectId: project.id })).length,
              {
                message: 'the tracked span must be queryable before it is asserted on',
                timeout: SPAN_VISIBLE_TIMEOUT_MS,
                intervals: [2_000, 2_000, 5_000],
              },
            )
            .toBe(1);

          const only = theOnlyTrackedSpan(
            await backendClient.listTrackedSpans({ projectId: project.id }),
          );
          // The model is what the server prices on, and the tracker takes it
          // from the response's `modelVersion` rather than from the request —
          // so a span priced correctly for the requested model would not be the
          // assertion this spec wants.
          expect(only.model, "the span records the model the response reported").toBe(stub.model);
          expect(only.name, 'and carries the generation name the tracker was given').toBe(
            generationName,
          );
          // Recorded, not endorsed. This tracker types its generation `llm`
          // where `trackOpenAI` types the equivalent span `general`. Whether
          // the two should agree is a product question this spec does not try
          // to settle; it is asserted so the asymmetry is written down
          // somewhere, and so a change to it arrives as a review conversation
          // rather than silently.
          expect(only.type, 'the TypeScript Gemini tracker records its generation as an llm span').toBe(
            'llm',
          );
          // Asserted because the price lookup reads it: `google_ai` is the
          // `CostService` alias for the `gemini/`-keyed price rows, so a span
          // that arrived under some other provider name would price from a
          // different row — or from none — and the amount below would be
          // saying something else. Both the `gemini/gemini-2.5-flash` row and
          // the bare one publish identical rates, so the expected number does
          // not depend on which of the two is reached; which provider is
          // RECORDED does.
          expect(only.provider, 'the span is attributed to the Gemini price alias').toBe(
            GEMINI_PRICE_PROVIDER,
          );
          return only;
        });

        await test.step('Both folds landed, and the provider’s own counters survive beside them', async () => {
          // Required, not optional-chained: a tracker that recorded no usage at
          // all is a regression in its own right, and a `?? 0` here would turn
          // it into comparisons against zero that pass.
          expect(span.usage, 'the span carries a usage map').not.toBeNull();
          // Asserted as a WHOLE object rather than key by key, so a counter the
          // tracker invented or dropped is visible in the same failure as a
          // wrong value. The native counters under `original_usage.*` are in
          // the same comparison on purpose: keeping the provider's own five
          // words beside Opik's two is what makes this a MAPPING rather than a
          // rename, and their presence is what rules out the only other way the
          // asserted pair could be right — a tracker that had simply relabelled
          // two of them.
          expect(
            span.usage!,
            `the provider reported promptTokenCount=${stub.usage.promptTokenCount}, ` +
              `toolUsePromptTokenCount=${stub.usage.toolUsePromptTokenCount}, ` +
              `candidatesTokenCount=${stub.usage.candidatesTokenCount} and ` +
              `thoughtsTokenCount=${stub.usage.thoughtsTokenCount}; tool-use tokens belong in ` +
              `prompt_tokens (${expectedPromptTokens}) and thinking tokens in completion_tokens ` +
              `(${expectedCompletionTokens}), not the naive ` +
              `${stub.usage.promptTokenCount}/${stub.usage.candidatesTokenCount} pair`,
          ).toEqual({
            prompt_tokens: expectedPromptTokens,
            completion_tokens: expectedCompletionTokens,
            total_tokens: stub.usage.totalTokenCount,
            'original_usage.promptTokenCount': stub.usage.promptTokenCount,
            'original_usage.toolUsePromptTokenCount': stub.usage.toolUsePromptTokenCount,
            'original_usage.candidatesTokenCount': stub.usage.candidatesTokenCount,
            'original_usage.thoughtsTokenCount': stub.usage.thoughtsTokenCount,
            'original_usage.totalTokenCount': stub.usage.totalTokenCount,
          });
        });

        await test.step('…and the call is priced on the folded buckets', async () => {
          const expectedCost =
            expectedPromptTokens * INPUT_RATE + expectedCompletionTokens * OUTPUT_RATE;
          expect(span.totalEstimatedCost, 'the span was priced server-side').not.toBeNull();
          expect(
            span.totalEstimatedCost!,
            `${expectedPromptTokens} x $${INPUT_RATE} + ${expectedCompletionTokens} x $${OUTPUT_RATE} = $${expectedCost}`,
          ).toBeCloseTo(expectedCost, 10);
        });

        await test.step('A span carrying the naive two-counter mapping really is priced lower', async () => {
          // The control, and the reason the cost assertion above is a real test
          // rather than a restatement of the one before it. An identical span
          // carrying only `promptTokenCount` and `candidatesTokenCount` — the
          // pre-8783 mapping — is written by hand through the backend, so the
          // server prices it by the same code path. If the two came out the
          // same, the cost assertion could not have caught the defect.
          //
          // It carries the SAME model and the SAME provider as the tracked
          // span, so the only thing it differs by is the two counters. A
          // control seeded without the provider would price from a different
          // row, or from none, and the comparison would be about the lookup
          // rather than about the mapping.
          const controlTraceId = uuid7();
          const controlSpanId = uuid7();
          await backendClient.createTracesBatch({
            projectName: project.name,
            traces: [{ id: controlTraceId, name: `${testNamespace}-naive-mapping-control` }],
          });
          await backendClient.createSpansBatch({
            projectName: project.name,
            spans: [
              {
                id: controlSpanId,
                traceId: controlTraceId,
                name: `${testNamespace}-naive-mapping-control-span`,
                type: 'llm',
                model: stub.model,
                provider: GEMINI_PRICE_PROVIDER,
                usage: {
                  prompt_tokens: stub.usage.promptTokenCount,
                  completion_tokens: stub.usage.candidatesTokenCount,
                  total_tokens:
                    stub.usage.promptTokenCount + stub.usage.candidatesTokenCount,
                },
              },
            ],
          });

          const control = await test.step('Read the control span back', async () => {
            await expect
              .poll(
                async () =>
                  (
                    await backendClient.listSpansPage({
                      projectId: project.id,
                      traceId: controlTraceId,
                    })
                  ).total,
                {
                  message: 'the control span must be queryable before it is compared',
                  timeout: SPAN_VISIBLE_TIMEOUT_MS,
                  intervals: [1_000, 2_000, 5_000],
                },
              )
              .toBe(1);
            const read = await backendClient.listSpansPage({
              projectId: project.id,
              traceId: controlTraceId,
            });
            expect(read.spans, 'the control read returned its one span').toHaveLength(1);
            return read.spans[0];
          });

          expect(control.totalEstimatedCost, 'the control was priced too').not.toBeNull();
          expect(
            control.totalEstimatedCost!,
            'the naive mapping bills the two obvious counters at the same rates',
          ).toBeCloseTo(
            stub.usage.promptTokenCount * INPUT_RATE +
              stub.usage.candidatesTokenCount * OUTPUT_RATE,
            10,
          );
          expect(
            span.totalEstimatedCost!,
            'so the tracked span costs strictly MORE than the naive mapping would have billed — ' +
              'the 35 tokens the fold recovers, stated without reference to any published rate',
          ).toBeGreaterThan(control.totalEstimatedCost!);
        });
      },
    );
  },
);
