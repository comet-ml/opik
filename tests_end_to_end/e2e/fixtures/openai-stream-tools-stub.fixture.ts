import * as http from 'node:http';
import { expect } from '@playwright/test';
import { Opik } from 'opik';
import { test as baseTest } from './openrouter-vision-models.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

/**
 * The model the stub echoes back, and the one the span must be priced on.
 *
 * `gpt-4o` for the same reason `openaiResponsesStub` picks it: its two
 * published rates are an order of magnitude apart, so the amount is a number a
 * reader of a span panel would notice.
 */
export const STREAM_TOOLS_MODEL = 'gpt-4o';

/** The counters the stub's final chunk reports. */
export const STREAM_TOOLS_PROMPT_TOKENS = 1_000_000;
export const STREAM_TOOLS_COMPLETION_TOKENS = 1_000;

/**
 * The two tool calls the stream carries, and the only two it may produce.
 *
 * Two DIFFERENT function names and two DIFFERENT argument objects, both valid
 * JSON on their own and neither a prefix of the other. That is what makes the
 * interleaving below observable: a parser keyed on arrival order rather than on
 * `index` concatenates the fragments into `{"city":{"tz":"Paris"}"UTC"}` — one
 * call, named for whichever fragment arrived last, whose arguments are not JSON
 * at all and yet still render as a tool call in the panel.
 */
export const STREAM_TOOL_CALLS = [
  { id: 'call_stub_weather', name: 'get_weather', arguments: '{"city":"Paris"}' },
  { id: 'call_stub_time', name: 'get_time', arguments: '{"tz":"UTC"}' },
] as const;

export interface OpenAiStreamToolsStubRef {
  /** The `baseURL` an OpenAI client is pointed at — `http://127.0.0.1:<port>/v1`. */
  baseUrl: string;
  /** An Opik client configured for this run's workspace and the seeded project. */
  opikClient: Opik;
  model: string;
  promptTokens: number;
  completionTokens: number;
  /** How many `POST /v1/chat/completions` calls reached the stub. */
  requestCount(): number;
  /** Paths the stub was asked for that it did not serve, if any. */
  unexpectedRequests(): string[];
}

export interface OpenAiStreamToolsStubFixtures {
  openaiStreamToolsStub: OpenAiStreamToolsStubRef;
}

/**
 * The SSE body, with the two calls' argument fragments INTERLEAVED.
 *
 * Order matters and is the whole point: the opening fragment of call 0, then
 * the opening fragment of call 1, then the closing fragment of call 0, then the
 * closing fragment of call 1. Each `delta.tool_calls` entry carries its own
 * `index`, which is the only thing that says which call a fragment belongs to —
 * arrival order says the opposite.
 *
 * Each delta holds ONE entry, as a real provider's stream does, so a reader
 * that took `delta.tool_calls[0]` and discarded the rest of the array cannot be
 * told apart from one that read the whole array. The discrimination comes from
 * the `index` keying, not from array length.
 */
const streamBody = (): string => {
  const chunk = (payload: unknown): string => `data: ${JSON.stringify(payload)}\n\n`;
  const base = {
    id: 'chatcmpl-stub-0001',
    object: 'chat.completion.chunk',
    created: Math.floor(Date.now() / 1000),
    model: STREAM_TOOLS_MODEL,
  };
  const delta = (toolCall: unknown): string =>
    chunk({ ...base, choices: [{ index: 0, delta: { tool_calls: [toolCall] }, finish_reason: null }] });

  return [
    chunk({
      ...base,
      choices: [{ index: 0, delta: { role: 'assistant', content: null }, finish_reason: null }],
    }),
    // Both calls are OPENED before either is filled in, which is what a
    // provider streaming parallel calls does.
    delta({
      index: 0,
      id: STREAM_TOOL_CALLS[0].id,
      type: 'function',
      function: { name: STREAM_TOOL_CALLS[0].name, arguments: '' },
    }),
    delta({
      index: 1,
      id: STREAM_TOOL_CALLS[1].id,
      type: 'function',
      function: { name: STREAM_TOOL_CALLS[1].name, arguments: '' },
    }),
    delta({ index: 0, function: { arguments: '{"city":' } }),
    delta({ index: 1, function: { arguments: '{"tz":' } }),
    delta({ index: 0, function: { arguments: '"Paris"}' } }),
    delta({ index: 1, function: { arguments: '"UTC"}' } }),
    chunk({ ...base, choices: [{ index: 0, delta: {}, finish_reason: 'tool_calls' }] }),
    // The usage chunk a provider sends last, with no choices at all — which is
    // also the shape the tracker's chunk reader has to survive without
    // mistaking it for content.
    chunk({
      ...base,
      choices: [],
      usage: {
        prompt_tokens: STREAM_TOOLS_PROMPT_TOKENS,
        completion_tokens: STREAM_TOOLS_COMPLETION_TOKENS,
        total_tokens: STREAM_TOOLS_PROMPT_TOKENS + STREAM_TOOLS_COMPLETION_TOKENS,
      },
    }),
    'data: [DONE]\n\n',
  ].join('');
};

export const test = baseTest.extend<OpenAiStreamToolsStubFixtures>({
  /**
   * A local stub OpenAI **Chat Completions** endpoint that STREAMS two parallel
   * tool calls, plus an Opik client, for driving `opik-openai`'s `trackOpenAI`
   * (opik#8999).
   *
   * Hermetic by construction, exactly as `openaiResponsesStub` is: the stub
   * binds an ephemeral port on 127.0.0.1, needs no API key and makes no network
   * call, so the chunk sequence — which IS the thing under test — is fixed by
   * this file rather than by what a live provider happened to emit. A real
   * provider cannot be made to interleave fragments on demand at all, so there
   * is no non-stub way to drive this.
   *
   * The stub answers only `POST /v1/chat/completions` and records anything else
   * it is asked for, so a tracked client that went to `/v1/responses` — a
   * different path with a different accumulator — fails loudly instead of
   * silently exercising the wrong one.
   *
   * **Teardown deletes every trace in the project**, for the reason
   * `openaiResponsesStub` documents: `trackOpenAI` mints the trace and span ids
   * itself, so a seed fixture cannot register them, and deleting a project does
   * not delete its traces. The project is this test's own, created under the run
   * prefix, so everything in it belongs to this test.
   */
  openaiStreamToolsStub: async ({ envConfig, project, backendClient }, use, testInfo) => {
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
        if (req.method !== 'POST' || path !== '/v1/chat/completions') {
          unexpected.push(`${req.method} ${path}`);
          res.writeHead(404, { 'content-type': 'application/json' });
          res.end(
            JSON.stringify({ error: { message: 'stub serves POST /v1/chat/completions only' } }),
          );
          return;
        }
        requestCount += 1;
        // The model and the stream flag are echoed back fixed, so a request
        // that asked for something else would still be answered this stream and
        // still pass every assertion. Recorded as unexpected rather than
        // answered differently, so the spec's `unexpectedRequests()` assertion
        // catches it while the stream stays fixed by this file.
        try {
          const asked = JSON.parse(body) as { model?: unknown; stream?: unknown };
          if (asked.model !== STREAM_TOOLS_MODEL) {
            unexpected.push(`POST /v1/chat/completions asked for model ${String(asked.model)}`);
          }
          if (asked.stream !== true) {
            unexpected.push('POST /v1/chat/completions did not ask for a stream');
          }
        } catch {
          unexpected.push('POST /v1/chat/completions with a body that is not JSON');
        }
        res.writeHead(200, {
          'content-type': 'text/event-stream',
          'cache-control': 'no-cache',
          connection: 'keep-alive',
        });
        res.end(streamBody());
      });
    });

    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (address === null || typeof address === 'string') {
      server.close();
      throw new Error('openaiStreamToolsStub: the stub server reported no numeric port');
    }
    const baseUrl = `http://127.0.0.1:${address.port}/v1`;

    // The same settings every other TS-SDK caller in the estate uses
    // (`core/sdk/typescript-sdk.ts`), plus `projectName` so the tracker's trace
    // lands in this test's own project rather than in Default Project — where
    // it would be indistinguishable from the tracker having written nothing.
    // Gated on the deployment exactly as `makeTypescriptSdk` gates it: an OSS
    // install authenticates nothing, so `envConfig.apiKey` is legitimately null
    // there and an unconditional assertion would make this spec unrunnable on
    // the deployment the whole estate defaults to.
    if (envConfig.deployment !== 'oss') {
      expect(
        envConfig.apiKey,
        'openaiStreamToolsStub needs an API key to write traces on a ' +
          `${envConfig.deployment} deployment — global-setup mints one into OPIK_API_KEY`,
      ).toBeTruthy();
    }
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
        model: STREAM_TOOLS_MODEL,
        promptTokens: STREAM_TOOLS_PROMPT_TOKENS,
        completionTokens: STREAM_TOOLS_COMPLETION_TOKENS,
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
          console.warn('[openaiStreamToolsStub fixture] trace delete warning:', err);
        }
      }
    }
  },
});

export { expect } from './openrouter-vision-models.fixture';
