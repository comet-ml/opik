import OpenAI from 'openai';
import { trackOpenAI } from 'opik-openai';
import { test, expect, STREAM_TOOL_CALLS } from '@e2e/fixtures';
import type { TrackedSpanRef } from '@e2e/core/backend';

/**
 * `opik-openai` keeps STREAMED PARALLEL tool calls apart on the span
 * (opik#8999).
 *
 * When a model answers with two tool calls at once, the chat-completions stream
 * carries their argument fragments interleaved, and the only thing that says
 * which call a fragment belongs to is the `index` on each `delta.tool_calls`
 * entry. The accumulator this PR added keys on that index. The one before it
 * took `delta.tool_calls[0]` and concatenated every fragment it saw into a
 * single call — so two calls arrived as ONE, named for whichever fragment
 * carried a name last, with arguments like `{"city":{"tz":"Paris"}"UTC"}`.
 *
 * That is the silent-wrongness shape this spec exists for. Nothing errors: the
 * span is written, the panel renders a tool call, and the only way to see the
 * damage is to compare the recorded calls against what the provider actually
 * streamed. Anyone debugging an agent from that trace is reading a turn that
 * never happened.
 *
 * ## Why no existing spec covers it
 *
 * `traces.ts-sdk-openai-tracker-usage` is `covered: false`, and the one spec
 * that imports an integration package — `ts-sdk-openai-responses-usage.spec.ts`
 * — drives the NON-STREAMING Responses path, whose own note records streaming,
 * the chat-completions endpoint and tool calls as explicitly not covered. A
 * build that merged streamed parallel calls passes it untouched.
 *
 * ## Deterministic and hermetic
 *
 * `openaiStreamToolsStub` serves a fixed SSE stream from 127.0.0.1 with no key
 * and no network, so the chunk sequence — which IS the subject — is fixed by
 * the fixture. A live provider cannot be asked to interleave fragments in a
 * chosen order at all, so there is no non-stub way to drive this.
 */
test.describe(
  "Trace Explore — the TypeScript OpenAI tracker's streamed parallel tool calls",
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    /** A tracked stream, two flushes and an ingestion poll. */
    test.setTimeout(300_000);

    /** How long the tracked span may take to become queryable after the flush. */
    const SPAN_VISIBLE_TIMEOUT_MS = 120_000;

    /**
     * gpt-4o's published per-token rates, from the shipped price table
     * (`model_prices_and_context_window.json`). Spelled out here, as the
     * sibling cost specs spell out the rates they assert on, so the arithmetic
     * below is pinned to the table rather than to itself.
     */
    const INPUT_RATE = 2.5e-6;
    const OUTPUT_RATE = 1e-5;

    /** The tool declarations the request carries, so the ask looks like the answer. */
    const TOOLS = [
      {
        type: 'function' as const,
        function: {
          name: STREAM_TOOL_CALLS[0].name,
          parameters: {
            type: 'object',
            properties: { city: { type: 'string' } },
            required: ['city'],
          },
        },
      },
      {
        type: 'function' as const,
        function: {
          name: STREAM_TOOL_CALLS[1].name,
          parameters: {
            type: 'object',
            properties: { tz: { type: 'string' } },
            required: ['tz'],
          },
        },
      },
    ];

    /**
     * The one span the tracker wrote, found by exhaustion over the project.
     *
     * By exhaustion rather than by name: the point is that one tracked stream
     * produces EXACTLY one span, so a build that wrote one per chunk — nine
     * here — or one per tool call fails here rather than hiding behind
     * whichever span was looked for.
     */
    const theOnlyTrackedSpan = (spans: TrackedSpanRef[]): TrackedSpanRef => {
      expect(
        spans.map((span) => `${span.name} (${span.type})`),
        'one tracked stream wrote exactly one span',
      ).toHaveLength(1);
      return spans[0];
    };

    test(
      'two interleaved tool calls arrive as two calls, each with its own id, name and complete arguments',
      { tag: ['@cap:traces.ts-sdk-openai-tracker-usage'] },
      async ({ openaiStreamToolsStub, project, backendClient, testNamespace }) => {
        const stub = openaiStreamToolsStub;
        const generationName = `${testNamespace}-streamed-tools`;

        const chunksSeen = await test.step(
          'Consume the stub stream through a tracked OpenAI client, then flush both',
          async () => {
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

            const stream = await tracked.chat.completions.create({
              model: stub.model,
              stream: true,
              messages: [{ role: 'user', content: 'weather and time, please' }],
              tools: TOOLS,
            });

            // Consumed to exhaustion. The tracker writes its span from the
            // generator's tail, so a stream left part-read writes nothing at
            // all — which is indistinguishable from a tracker that never
            // created a span.
            const seen: unknown[] = [];
            for await (const chunk of stream) seen.push(chunk);

            // Both flushes, in this order. `tracked.flush()` drains the
            // tracker's own queue into the Opik client; `opikClient.flush()`
            // puts it on the wire. Skipping either leaves the span unwritten.
            await tracked.flush();
            await stub.opikClient.flush();
            return seen;
          },
        );

        await test.step('The caller still saw every chunk the stub sent', async () => {
          // The tracker wraps the stream in a generator of its own, so it is
          // in a position to swallow or reorder what the caller receives.
          // Asserted before anything about the span, because a tracker that
          // broke the stream would be a worse regression than a mis-keyed
          // accumulator — and because every assertion below would otherwise be
          // about a span built from chunks the caller never got.
          expect(
            chunksSeen,
            'nine chunks reach the caller: role, two opens, four argument fragments, finish, usage',
          ).toHaveLength(9);
        });

        await test.step('Exactly one request reached the stub, on the streaming chat endpoint', async () => {
          expect(
            stub.requestCount(),
            'the tracked client made one upstream call, not zero and not a retry storm',
          ).toBe(1);
          // The negative half. A client that fell back to /v1/responses would
          // be exercising a DIFFERENT accumulator with a different output
          // shape, so every assertion below would pass while saying nothing
          // about the streamed chat path this spec is named for. The stub also
          // records a request that did not ask for a stream, which is the other
          // way this could silently become a test of something else.
          expect(
            stub.unexpectedRequests(),
            'and asked for nothing but a streamed POST /v1/chat/completions',
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
          expect(only.model, 'the span records the model the call named').toBe(stub.model);
          expect(only.provider, 'and attributes it to OpenAI').toBe('openai');
          expect(only.name, 'and carries the generation name the tracker was given').toBe(
            generationName,
          );
          return only;
        });

        await test.step('The span records BOTH calls, each one whole', async () => {
          // The assertion the spec exists for, written as the WHOLE output
          // object rather than call by call. Three things fail together here
          // and would each pass a narrower check:
          //
          //  - a single merged call (the pre-PR behaviour) has the wrong array
          //    length;
          //  - two calls whose fragments were assigned by arrival order carry
          //    each other's arguments, so the array length is right and the
          //    contents are not;
          //  - an accumulator that dropped the ids or the `type` while getting
          //    the arguments right would satisfy both of the above.
          //
          // The order is asserted too: the tracker sorts by `index`, which is
          // what makes "the first call" mean the provider's first call rather
          // than whichever fragment happened to arrive first.
          expect(span.output, 'the span carries an output').not.toBeNull();
          expect(
            span.output,
            `the stub streamed ${STREAM_TOOL_CALLS[0].name}(${STREAM_TOOL_CALLS[0].arguments}) and ` +
              `${STREAM_TOOL_CALLS[1].name}(${STREAM_TOOL_CALLS[1].arguments}) with their argument ` +
              'fragments interleaved; both must survive as separate calls, in index order',
          ).toEqual({
            tool_calls: STREAM_TOOL_CALLS.map((call) => ({
              id: call.id,
              type: 'function',
              function: { name: call.name, arguments: call.arguments },
            })),
          });
        });

        await test.step("…and each call's arguments are valid JSON on their own", async () => {
          // What a consumer of the trace actually needs, stated independently
          // of the comparison above. The failure mode this rules out is the
          // concatenation one: `{"city":{"tz":"Paris"}"UTC"}` is a string that
          // renders perfectly well in a panel and parses as nothing.
          const calls = (span.output as { tool_calls?: unknown }).tool_calls;
          expect(Array.isArray(calls), 'the output carries a tool_calls array').toBe(true);
          const parsed = (calls as Array<{ function: { arguments: string } }>).map((c) =>
            JSON.parse(c.function.arguments),
          );
          expect(parsed, 'each call parses back to the object the stub streamed').toEqual([
            { city: 'Paris' },
            { tz: 'UTC' },
          ]);
        });

        await test.step("The stream's final usage chunk lands on the span", async () => {
          // A usage chunk arrives with `choices: []`, which is a shape the
          // chunk reader has to survive without mistaking it for content —
          // and a tracker that dropped it writes a span with tool calls and no
          // tokens, which prices at nothing.
          expect(span.usage, 'the span carries a usage map').not.toBeNull();
          // Asserted as a whole object, so a key the accumulator invented is
          // visible in the same failure as a wrong value. The native counters
          // under `original_usage.*` are in the same comparison on purpose:
          // their presence is what makes this a mapping rather than a rename.
          expect(span.usage!, "the stub's final chunk, mapped and kept").toEqual({
            prompt_tokens: stub.promptTokens,
            completion_tokens: stub.completionTokens,
            total_tokens: stub.promptTokens + stub.completionTokens,
            'original_usage.prompt_tokens': stub.promptTokens,
            'original_usage.completion_tokens': stub.completionTokens,
            'original_usage.total_tokens': stub.promptTokens + stub.completionTokens,
          });
        });

        await test.step('…and the call is priced on it', async () => {
          // Not a second cost spec: the point is that a streamed tool-call span
          // is a fully-formed LLM span, so the usage it recorded reaches the
          // server's price resolution like any other. A tracker that wrote the
          // counters under keys the backend does not read leaves the span
          // unpriced beside identical siblings that are.
          const expectedCost =
            stub.promptTokens * INPUT_RATE + stub.completionTokens * OUTPUT_RATE;
          expect(span.totalEstimatedCost, 'the span was priced server-side').not.toBeNull();
          expect(
            span.totalEstimatedCost!,
            `${stub.promptTokens} x $${INPUT_RATE} + ${stub.completionTokens} x $${OUTPUT_RATE} = $${expectedCost}`,
          ).toBeCloseTo(expectedCost, 6);
        });
      },
    );
  },
);
