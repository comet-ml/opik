import { test, expect, PROVIDER_GROUP } from '@e2e/fixtures';
import type { SpanCostRef } from '@e2e/core/backend';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { PlaygroundLogsSidebarPage } from '@e2e/pom/playground-logs-sidebar.page';
import { VERTEX_AI_MODEL, ensureVertexAiProviderConfigured } from '@e2e/pom/model-availability';

/**
 * Can Opik still reach Vertex AI at all, and does a run through it record what
 * it cost (opik#9356, OPIK-7855)?
 *
 * That PR replaced the whole Vertex AI client — GAX + langchain4j's
 * `VertexAiGeminiChatModel` out, the `google-genai` `Client` +
 * `GoogleGenAiChatModel` in, with the endpoint now derived by the SDK instead of
 * pinned per region — and nothing in the estate made a real Vertex call.
 * `data/playground-models.yaml` has rows for OpenAI, Anthropic, Gemini,
 * OpenRouter and the two mock-gateway providers and none for Vertex, and every
 * other Vertex-touching spec (`playground-max-output-tokens-no-limit`,
 * `playground-reasoning-model-parameters`) short-circuits `chat/completions` in
 * the browser, so each asserts what the FE SENDS and none that anything answers.
 *
 * Why not a row in that matrix, which is where a "provider X still answers"
 * check belongs: its loop selects by display name alone (`selectModel`, which
 * takes the first matching option), and "Gemini 2.5 Flash" is offered by BOTH
 * the Gemini and the Vertex AI groups. An enabled `vertex-ai` row there would
 * run on whichever group the picker listed first and report it as Vertex
 * coverage. This spec names the group (`selectModelFromProvider`), so it cannot
 * pass on the wrong provider, and it asserts two things the matrix cannot
 * express:
 *
 *  - the trace the run logged, server-side: one span, attributed to `vertex-ai`
 *    and to the provider-qualified model id, carrying mapped usage and a
 *    resolved price. A client that answered but stopped reporting usage leaves
 *    an unpriced span, which no amount of rendered answer reveals.
 *  - the BLOCKING completion path. The Playground only ever sends
 *    `stream: true`, so a UI-only pass says nothing about the non-streaming
 *    branch of the rewritten client — which is the branch the SDK's own
 *    `generateContent` takes and the one every API caller uses.
 *
 * `@llm-daily`, with no tier, for the reason `playground-providers.spec.ts` has
 * no tier: this makes a real, billable provider call, so it runs on the daily
 * real-provider cadence and is excluded from t1/t2/t3. It skips cleanly without
 * a `VERTEX_AI_API_KEY` — see `vertexAiKeyOrSkip` for why no mock can stand in.
 *
 * Deterministic in what it asserts: that an answer rendered, that no failure was
 * reported, and what the span and the usage block say. Never what the model
 * said — the prompt asks for a one-word answer and the spec reads none of it.
 */

/** One short answer, so the run is cheap and the token counters stay small. */
const SYSTEM_PROMPT = 'You are a concise assistant. Answer in one short word.';
const USER_PROMPT = 'What is the capital of France?';

test.describe(
  'Playground — Vertex AI through the Gen AI SDK',
  { tag: ['@provider-sanity', '@llm-daily', '@area:playground'] },
  () => {
    test.use({ viewport: { width: 1600, height: 900 } });

    test(
      'a Vertex AI run renders an answer and logs a priced span attributed to vertex-ai, and the blocking endpoint answers with usage',
      {
        tag: [
          '@cap:playground.select-model-provider',
          '@cap:playground.verify-trace-from-run',
        ],
      },
      async ({ page, project, backendClient }) => {
        test.setTimeout(300_000);

        await ensureVertexAiProviderConfigured(page);

        const playground = new PlaygroundPage(page, project.id);

        await test.step(
          `Run a prompt on "${VERTEX_AI_MODEL.displayName}" in the ${PROVIDER_GROUP.vertexAi} group`,
          async () => {
            await playground.goto();
            await playground.waitForReady();
            // By group, not by display name: see the file header.
            await playground.selectModelFromProvider(
              0,
              PROVIDER_GROUP.vertexAi,
              VERTEX_AI_MODEL.displayName,
            );
            await playground.configureVariant(0, {
              systemPrompt: SYSTEM_PROMPT,
              userPrompt: USER_PROMPT,
            });

            // Armed before Run: the batch POST is in flight the moment the
            // button is pressed, and the trace has to be committed before
            // anything below can read it back.
            const traceLogged = page.waitForResponse(
              (r) => r.url().includes('/traces/batch') && r.ok(),
              { timeout: 180_000 },
            );
            await playground.runFreeMode(180_000);
            await traceLogged;
          },
        );

        await test.step('The run rendered a model answer and reported no failure', async () => {
          await expect(
            playground.promptOutputErrorTags(),
            'no "Run failed:" tag — a refused provider call renders one instead of an answer',
          ).toHaveCount(0);
          // Exact counts, measured against this build: one variant renders one
          // answer and one chip of each kind. A floor would pass on a second
          // render, which is what a client retrying under the covers looks like
          // from the page.
          const answer = playground.promptOutputMarkdownBlocks();
          await expect(answer, 'the output panel rendered exactly one reply').toHaveCount(1);
          await expect(answer, 'and it is not an empty render').not.toBeEmpty();
          // The chips are drawn from the usage the provider path reported, so a
          // client that answered without mapping usage shows an answer and no
          // token chip.
          await expect(playground.tokenChips(), 'a token chip').toHaveCount(1);
          await expect(playground.durationChips(), 'a duration chip').toHaveCount(1);
        });

        const sidebar = new PlaygroundLogsSidebarPage(page);

        const traceId = await test.step('The logged trace carries no error block', async () => {
          await playground.openLogsPanel();
          await sidebar.waitForOpen();
          await sidebar.waitForTraceRow(60_000);
          // The project is this test's own and one run happened in it, so an
          // extra row would mean the run logged twice.
          await expect(sidebar.traceRows(), 'the run logged exactly one trace').toHaveCount(1);
          await sidebar.openFirstTrace();

          await expect(
            sidebar.errorCallout(),
            'a trace the provider path marked errored renders an Error block',
          ).toHaveCount(0);
          await sidebar.clickMessagesTab();
          await expect(sidebar.messageRole('Assistant')).toBeVisible();
          await expect(
            sidebar.messageBody('Assistant'),
            "the assistant turn carries the model's reply",
          ).not.toBeEmpty();

          const opened = new URL(page.url()).searchParams.get('tls_trace');
          // Read from the URL rather than from a listing: it ties everything
          // below to the trace whose panel was just asserted about, so the two
          // halves cannot drift onto different traces.
          expect(opened, 'the sidebar addresses the opened trace by id').toBeTruthy();
          return opened!;
        });

        await test.step('Server-side, the run logged one span attributed to Vertex AI and priced', async () => {
          let spans: SpanCostRef[] = [];
          await expect
            .poll(
              async () => {
                spans = await backendClient.listSpanCosts({
                  projectId: project.id,
                  traceId,
                });
                return spans.length;
              },
              {
                timeout: 60_000,
                intervals: [1_000, 2_000, 5_000],
                message: "the run's span becomes queryable",
              },
            )
            // Exactly one, not "at least one": a client that retried the call
            // under the covers would leave a second span and bill twice.
            .toBe(1);

          const span = spans[0]!;
          expect(
            span.provider,
            'the span names the provider that served the run — a Gemini-attributed span would ' +
              'mean the picker resolved to the wrong group',
          ).toBe(VERTEX_AI_MODEL.provider);
          expect(
            span.model,
            'and the provider-qualified model id, which is what prices a Vertex row',
          ).toBe(VERTEX_AI_MODEL.modelId);
          expect(
            span.usage,
            "the provider's token counters were mapped onto the span",
          ).not.toBeNull();
          expect(
            Object.keys(span.usage!),
            'and the usage map is not empty — an empty one still renders as a span',
          ).not.toHaveLength(0);
          expect(
            span.totalEstimatedCost,
            'the span priced: null is what a usage/model/provider triple that did not resolve leaves',
          ).not.toBeNull();
          expect(span.totalEstimatedCost!, 'and the price is above zero').toBeGreaterThan(0);
        });

        await test.step('The blocking completion endpoint answers 200 with a usage block', async () => {
          const answer = await backendClient.postChatCompletion({
            model: VERTEX_AI_MODEL.modelId,
            messages: [{ role: 'user', content: 'Reply with the word OK.' }],
          });

          expect(
            answer.status,
            `POST /v1/private/chat/completions answered ${answer.status}: ${answer.message}`,
          ).toBe(200);
          expect(
            answer.completion,
            'a 200 carries a completion, not an error envelope',
          ).not.toBeNull();
          const completion = answer.completion!;

          expect(completion.model, 'the server echoes the model it resolved').toBe(
            VERTEX_AI_MODEL.modelId,
          );
          expect(
            completion.content,
            'the blocking branch returns the assistant text, not only a stream of deltas',
          ).not.toBeNull();
          expect(completion.content!.length, 'and the text is not empty').toBeGreaterThan(0);

          expect(completion.usage, 'the blocking branch reports usage').not.toBeNull();
          const usage = completion.usage!;
          expect(usage.prompt_tokens, 'prompt tokens counted').toBeGreaterThan(0);
          expect(usage.completion_tokens, 'completion tokens counted').toBeGreaterThan(0);
          // `>=`, not `===` of the two halves: Gemini bills thinking tokens into
          // the total and into neither half, so the observed blocking answer is
          // routinely 29 total for 7 + 1 (see opik#9356's exploration evidence).
          expect(
            usage.total_tokens,
            'the total is at least the two halves it reports',
          ).toBeGreaterThanOrEqual(usage.prompt_tokens + usage.completion_tokens);
        });
      },
    );
  },
);
