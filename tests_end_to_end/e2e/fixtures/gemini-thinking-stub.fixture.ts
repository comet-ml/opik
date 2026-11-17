import * as http from 'node:http';
import { expect } from '@playwright/test';
import { Opik } from 'opik';
import { test as baseTest } from './openai-stream-tools-stub.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

/**
 * The model the stub reports as `modelVersion`, and the one the span is priced
 * on.
 *
 * `gemini-2.5-flash` is not an arbitrary pick. Its shipped price row publishes
 * an output rate ($2.5e-06) more than eight times its input rate ($3e-07), and
 * the defect this fixture exists to observe moves 30 tokens from the OUTPUT
 * bucket into nothing and 5 from nowhere into the INPUT bucket — so the
 * asymmetry is what makes the under-count a visibly different bill rather than
 * a rounding difference. The `gemini/` and the bare row carry identical rates,
 * so the amount does not depend on which of the two the resolver reaches.
 */
export const GEMINI_STUB_MODEL = 'gemini-2.5-flash';

/**
 * The provider's own counters, as its `usageMetadata` reports them.
 *
 * Every one of the five is load-bearing:
 *  - `promptTokenCount` and `candidatesTokenCount` are the two a naive mapping
 *    reads, and are what the asserted pair must NOT equal;
 *  - `toolUsePromptTokenCount` is billed as input and belongs in the prompt
 *    bucket;
 *  - `thoughtsTokenCount` is billed as output and belongs in the completion
 *    bucket;
 *  - `totalTokenCount` is the provider's own sum, and it is 155 rather than the
 *    125 the naive pair adds up to — which is exactly the inequality
 *    `opik-gemini` tests before folding the thinking tokens in. A stub whose
 *    total happened to equal the naive sum would silently skip that branch.
 *
 * The two folds are deliberately DIFFERENT sizes (5 and 30) so a mapping that
 * folded one count into both buckets, or the wrong count into either, lands on
 * a number neither assertion accepts.
 */
export const GEMINI_STUB_USAGE = {
  promptTokenCount: 100,
  toolUsePromptTokenCount: 5,
  candidatesTokenCount: 20,
  thoughtsTokenCount: 30,
  totalTokenCount: 155,
} as const;

/** The assistant text the stub returns, so the call can be shown to have landed. */
export const GEMINI_STUB_TEXT = 'stubbed gemini answer';

export interface GeminiThinkingStubRef {
  /** The `httpOptions.baseUrl` a `GoogleGenAI` client is pointed at. */
  baseUrl: string;
  /** An Opik client configured for this run's workspace and the seeded project. */
  opikClient: Opik;
  model: string;
  usage: typeof GEMINI_STUB_USAGE;
  text: string;
  /** How many `:generateContent` calls reached the stub. */
  requestCount(): number;
  /** Paths the stub was asked for that it did not serve, if any. */
  unexpectedRequests(): string[];
}

export interface GeminiThinkingStubFixtures {
  geminiThinkingStub: GeminiThinkingStubRef;
}

export const test = baseTest.extend<GeminiThinkingStubFixtures>({
  /**
   * A local stub Gemini `generateContent` endpoint plus an Opik client, for
   * driving `opik-gemini`'s `trackGemini` (opik#8783).
   *
   * Hermetic by construction, exactly as the two OpenAI stubs in this chain
   * are: an ephemeral port on 127.0.0.1, no key, no network — so all five
   * counters, the model name and therefore the resolved price are fixed by this
   * file. A live Gemini call cannot be made to report a chosen
   * `thoughtsTokenCount`, so there is no non-stub way to pin this mapping.
   *
   * ## Why the path is matched loosely
   *
   * `@google/genai` builds its own URL under the base it is given —
   * `<baseUrl>/<apiVersion>/models/<model>:generateContent` — and both the API
   * version segment and the model segment are the client's business, not this
   * fixture's. Matching on the `:generateContent` suffix therefore pins the
   * OPERATION, which is what the mapping under test belongs to, without
   * freezing a URL shape a client upgrade is free to change. Everything else is
   * recorded as unexpected, so a client that reached `:streamGenerateContent` —
   * a different accumulator entirely — fails loudly rather than passing while
   * saying nothing.
   *
   * **Teardown deletes every trace in the project**, for the reason
   * `openaiResponsesStub` documents: `trackGemini` mints the trace and span ids
   * itself, so a seed fixture cannot register them, and deleting a project does
   * not delete its traces. The project is this test's own, created under the run
   * prefix, so everything in it belongs to this test.
   */
  geminiThinkingStub: async ({ envConfig, project, backendClient }, use, testInfo) => {
    let requestCount = 0;
    const unexpected: string[] = [];

    const server = http.createServer((req, res) => {
      // Drained even when unused: an unread request body hangs the client's
      // socket rather than failing it, which from the spec's side looks like
      // the tracker never returning.
      let body = '';
      req.on('data', (chunk) => {
        body += chunk;
      });
      req.on('end', () => {
        const path = (req.url ?? '').split('?')[0];
        if (req.method !== 'POST' || !path.endsWith(':generateContent')) {
          unexpected.push(`${req.method} ${path}`);
          res.writeHead(404, { 'content-type': 'application/json' });
          res.end(
            JSON.stringify({ error: { message: 'stub serves POST *:generateContent only' } }),
          );
          return;
        }
        requestCount += 1;
        // The model is answered fixed, which is what keeps the cost assertion
        // an exact number — but it also means a client that asked for a
        // different model would still be answered `gemini-2.5-flash` and still
        // price at the same amount. Recorded as unexpected rather than answered
        // differently, so the spec's `unexpectedRequests()` assertion catches it.
        if (!path.includes(`${GEMINI_STUB_MODEL}:generateContent`)) {
          unexpected.push(`POST ${path} named a model other than ${GEMINI_STUB_MODEL}`);
        }
        const payload = {
          candidates: [
            {
              content: { role: 'model', parts: [{ text: GEMINI_STUB_TEXT }] },
              finishReason: 'STOP',
              index: 0,
            },
          ],
          modelVersion: GEMINI_STUB_MODEL,
          responseId: 'resp-stub-0001',
          // No `promptTokensDetails` or `candidatesTokensDetails`: the tracker
          // strips both before flattening the rest under `original_usage.`, so
          // sending them would add keys to the usage map that the spec's
          // whole-object comparison would have to carry without asserting
          // anything about the mapping.
          usageMetadata: { ...GEMINI_STUB_USAGE },
        };
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end(JSON.stringify(payload));
      });
    });

    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (address === null || typeof address === 'string') {
      server.close();
      throw new Error('geminiThinkingStub: the stub server reported no numeric port');
    }
    const baseUrl = `http://127.0.0.1:${address.port}`;

    // Gated on the deployment exactly as `makeTypescriptSdk` gates the same
    // settings, and for the reason the two stub fixtures above spell out.
    if (envConfig.deployment !== 'oss') {
      expect(
        envConfig.apiKey,
        'geminiThinkingStub needs an API key to write traces on a ' +
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
        model: GEMINI_STUB_MODEL,
        usage: GEMINI_STUB_USAGE,
        text: GEMINI_STUB_TEXT,
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
          console.warn('[geminiThinkingStub fixture] trace delete warning:', err);
        }
      }
    }
  },
});

export { expect } from './openai-stream-tools-stub.fixture';
