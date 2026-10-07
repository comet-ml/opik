import * as http from 'node:http';
import { expect } from '@playwright/test';
import { Opik } from 'opik';
import { test as baseTest } from './dataset-upload-files.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

/**
 * The model the stub echoes back, and the one the span must be priced on.
 *
 * `gpt-4o` deliberately, because its two published rates are an order of
 * magnitude apart (`input_cost_per_token` $2.5e-06, `output_cost_per_token`
 * $1e-05). That asymmetry is the whole point: it is what makes a swapped
 * input/output mapping produce a visibly different bill rather than the same
 * one. A model with equal rates could not observe the defect at all.
 */
export const STUB_RESPONSES_MODEL = 'gpt-4o';

/**
 * Deliberately lopsided counters, three orders of magnitude apart.
 *
 * Large enough that the resulting cost is a number a human would notice, and
 * asymmetric enough that reading them the wrong way round is unmistakable: at
 * gpt-4o's rates this pair prices at $2.51 and the swap at $10.0025.
 */
export const STUB_INPUT_TOKENS = 1_000_000;
export const STUB_OUTPUT_TOKENS = 1_000;

/** The assistant text the stub returns, so the span's output can be asserted. */
export const STUB_OUTPUT_TEXT = 'stubbed responses-api answer';

export interface OpenAiResponsesStubRef {
  /** The `baseURL` an OpenAI client is pointed at — `http://127.0.0.1:<port>/v1`. */
  baseUrl: string;
  /** An Opik client configured for this run's workspace and the seeded project. */
  opikClient: Opik;
  model: string;
  inputTokens: number;
  outputTokens: number;
  outputText: string;
  /** How many `POST /v1/responses` calls reached the stub. */
  requestCount(): number;
  /** Paths the stub was asked for that it did not serve, if any. */
  unexpectedRequests(): string[];
}

export interface OpenAiResponsesStubFixtures {
  openaiResponsesStub: OpenAiResponsesStubRef;
}

export const test = baseTest.extend<OpenAiResponsesStubFixtures>({
  /**
   * A local stub OpenAI **Responses API** plus an Opik client, for driving
   * `opik-openai`'s `trackOpenAI` (opik#8782).
   *
   * Hermetic by construction: the stub binds an ephemeral port on 127.0.0.1,
   * needs no API key and makes no network call, so the token counts and the model
   * name — the two things the usage mapping is asserted on — are fixed by this
   * file rather than by whatever a live provider happened to return. That is the
   * same reason `services/mock-token-auth` and the bridge's mock `/api/chat`
   * exist, and it is what makes the cost assertion an exact number instead of an
   * approximation.
   *
   * The stub answers only `POST /v1/responses` and records anything else it is
   * asked for, so a tracked client that fell back to `/v1/chat/completions` — a
   * different code path with a different usage shape — fails loudly instead of
   * silently exercising the wrong one.
   *
   * **Teardown deletes every trace in the project.** `trackOpenAI` mints the
   * trace and span ids itself, so they cannot be known upfront and a seed fixture
   * cannot register them; and deleting a project does not delete its traces,
   * while `global-teardown`'s run-prefix sweep only knows about projects,
   * datasets and experiments. Sweeping the project is the shape that works here:
   * the project is this test's own, created under the run prefix, so everything
   * in it belongs to this test.
   */
  openaiResponsesStub: async ({ envConfig, project, backendClient }, use, testInfo) => {
    let requestCount = 0;
    const unexpected: string[] = [];

    const server = http.createServer((req, res) => {
      // The body is drained even when unused: leaving a request body unread
      // makes the client's socket hang rather than fail, which from the spec's
      // side looks like the tracker never returning.
      let body = '';
      req.on('data', (chunk) => {
        body += chunk;
      });
      req.on('end', () => {
        const path = (req.url ?? '').split('?')[0];
        if (req.method !== 'POST' || path !== '/v1/responses') {
          unexpected.push(`${req.method} ${path}`);
          res.writeHead(404, { 'content-type': 'application/json' });
          res.end(JSON.stringify({ error: { message: `stub serves POST /v1/responses only` } }));
          return;
        }
        requestCount += 1;
        // A Responses-shaped payload, not a chat-completions one: `usage` here
        // carries `input_tokens`/`output_tokens`, which is exactly the pair
        // opik#8782 had mapped the wrong way round onto Opik's
        // `prompt_tokens`/`completion_tokens`.
        const payload = {
          id: 'resp_stub_0001',
          object: 'response',
          created_at: Math.floor(Date.now() / 1000),
          status: 'completed',
          model: STUB_RESPONSES_MODEL,
          output: [
            {
              id: 'msg_stub_0001',
              type: 'message',
              role: 'assistant',
              status: 'completed',
              content: [{ type: 'output_text', text: STUB_OUTPUT_TEXT, annotations: [] }],
            },
          ],
          output_text: STUB_OUTPUT_TEXT,
          usage: {
            input_tokens: STUB_INPUT_TOKENS,
            input_tokens_details: { cached_tokens: 0 },
            output_tokens: STUB_OUTPUT_TOKENS,
            output_tokens_details: { reasoning_tokens: 0 },
            total_tokens: STUB_INPUT_TOKENS + STUB_OUTPUT_TOKENS,
          },
        };
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end(JSON.stringify(payload));
        // `body` is read only to be sure the request arrived whole; nothing is
        // asserted on it, because what the SDK sends upstream is not this
        // spec's subject.
        void body;
      });
    });

    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (address === null || typeof address === 'string') {
      server.close();
      throw new Error('openaiResponsesStub: the stub server reported no numeric port');
    }
    const baseUrl = `http://127.0.0.1:${address.port}/v1`;

    // The same three settings every other TS-SDK caller in the estate uses
    // (`core/sdk/typescript-sdk.ts`), plus `projectName` so the tracker's trace
    // lands in this test's own project rather than in Default Project — where it
    // would be indistinguishable from the tracker having written nothing.
    expect(
      envConfig.apiKey,
      'openaiResponsesStub needs an API key to write traces — global-setup mints one into ' +
        'OPIK_API_KEY for cloud and self-hosted deployments',
    ).toBeTruthy();
    const opikClient = new Opik({
      apiKey: envConfig.apiKey ?? undefined,
      workspaceName: envConfig.workspace,
      apiUrl: envConfig.apiBaseUrl,
      projectName: project.name,
    });

    try {
      await use({
        baseUrl,
        opikClient,
        model: STUB_RESPONSES_MODEL,
        inputTokens: STUB_INPUT_TOKENS,
        outputTokens: STUB_OUTPUT_TOKENS,
        outputText: STUB_OUTPUT_TEXT,
        requestCount: () => requestCount,
        unexpectedRequests: () => [...unexpected],
      });
    } finally {
      await new Promise<void>((resolve) => server.close(() => resolve()));
      if (!shouldLeaveArtifacts(testInfo)) {
        try {
          const traceIds = await backendClient.listTraceIds({ projectId: project.id, size: 200 });
          if (traceIds.length > 0) await backendClient.deleteTraces(traceIds);
        } catch (err) {
          // Never rethrow from teardown: a cleanup failure must not replace the
          // test's own error.
          console.warn('[openaiResponsesStub fixture] trace delete warning:', err);
        }
      }
    }
  },
});

export { expect } from './dataset-upload-files.fixture';
