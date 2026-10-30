import { test, expect } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { mockAuthSkipReason } from '@e2e/core/mock-auth';

/**
 * opik#9238 (OPIK-9002): a provider error message reaches the user only when the
 * provider's envelope names an error that provider documents. Anything else is
 * replaced with a fixed line, with the HTTP status preserved either way.
 *
 * `ProviderErrorMessageValidator.DocumentedErrors` is the allowlist and
 * `CustomLlmErrorMessage.isDocumented()` the test applied to a custom-llm
 * gateway: an `error` object whose `type` or `code` is one OpenAI or Azure
 * OpenAI documents keeps its message; everything else — including the very
 * common bare `{"message": …}` shape, which is what GitHub's API answers —
 * loses its text entirely. This is deliberate (an undocumented body is not
 * necessarily safe to echo), but it is also the only thing a user has to debug
 * a broken gateway with, so the allowlist being neither too loose nor too tight
 * is a shipped contract and nothing asserted it.
 *
 * Why the existing run-error specs cannot. `playground-run-error.spec.ts`,
 * `playground-failed-run-output.spec.ts` and
 * `playground-dataset-run-failure.spec.ts` all point at an UNREACHABLE
 * provider, which returns no body at all — so none of them can tell a
 * suppressed message from a missing one, which is the entire distinction here.
 * Driving it needs a gateway that answers a body of the test's choosing, and
 * the two envelopes are asserted side by side on one provider so that nothing
 * but the body shape differs between them.
 *
 * SCOPE, stated plainly: this spec can only run where the Opik backend can
 * reach the suite's own mock gateway — `oss`, per `mockAuthSkipReason`. There
 * is no way around that and it is not an oversight: a chosen error body needs a
 * gateway the test controls, every loopback URL is refused on save by the
 * `strict` destination guard a cloud deployment runs, and the estate has
 * already established (see `PUBLIC_NOT_FOUND_BASE_URL`) that no public address
 * can serve a chosen body either. On a cloud target this skips with that
 * reason rather than passing vacuously.
 *
 * NOT asserted here, because it is already covered: that the automation-logs
 * ERROR row keeps the provider's RAW body whatever the envelope.
 * `online-evaluation-llm-judge-provider-failure.spec.ts` pins that, and it
 * holds for a different reason than anything below — the scorer logs
 * `error.getCause().getMessage()`, the langchain4j exception text, which never
 * passes through the allowlist at all. The release's own coverage report
 * predicted that row would lose its message; it does not, and this comment is
 * where that is written down.
 *
 * ## Two tests, because only one of the two surfaces holds
 *
 * The allowlist is applied on the BLOCKING `/chat/completions` call and is not
 * reached at all on the STREAMING one, which is the only path the Playground
 * uses — so the API contract below passes and the UI rendering of it does not.
 * The second test is `test.fail()` for that reason; see its own comment. They
 * are split rather than left as one test so that the API assertions stay live
 * assertions: under a single `test.fail()` they would stop guarding anything,
 * since a test marked expected-to-fail passes however early it fails.
 */

/**
 * Markers the gateway puts in each body. Plain single-line ASCII with no `<` or
 * `>`: `ProviderErrorMessageValidator.isPlainText` rejects those outright, and a
 * marker that tripped that check would be dropped for a reason that has nothing
 * to do with the allowlist this spec is about.
 */
const DOCUMENTED_MARKER = 'gateway says the documented thing';
const BARE_MARKER = 'gateway says the undocumented thing';

/** `ChatCompletionService.UNEXPECTED_ERROR_CALLING_LLM_PROVIDER`, verbatim. */
const GENERIC_MESSAGE = 'Unexpected error calling LLM provider';

/**
 * The status a DOCUMENTED envelope resolves to.
 *
 * Not the wire status, and that is the point of forcing the same 400 on the
 * wire: `CustomLlmErrorMessage.getStatusCode` maps from the body's `code`, and
 * the documented body deliberately carries none, so it falls back to 400. Wire
 * and mapped agreeing here keeps this assertion about the message rather than
 * about a status-mapping rule that is not this PR's.
 */
const DOCUMENTED_STATUS = 400;

/**
 * The status a BARE envelope must carry through.
 *
 * Chosen to be one no fallback could produce by accident: the bare body cannot
 * be parsed as a provider envelope at all, so the only way a 404 reaches the
 * caller is `failIfProviderReportedHttpStatus` recovering the wire status from
 * langchain4j's own `HttpException`. A 400 here would be indistinguishable from
 * the parse having silently defaulted.
 */
const BARE_STATUS = 404;

test.describe('Playground — provider error envelope allowlist', { tag: ['@t2-cuj', '@area:playground'] }, () => {
  test.use({ viewport: { width: 1600, height: 900 } });

  // The error bodies come from the mock gateway, which binds to the test
  // runner, so a backend that cannot reach it would fail on connectivity rather
  // than on anything this spec is about.
  test.beforeAll(async () => {
    const reason = await mockAuthSkipReason();
    test.skip(reason !== null, reason ?? '');
  });

  test('A documented envelope keeps the provider\'s message and a bare one is replaced, with the status preserved either way', { tag: ['@cap:playground.run-error-info'] }, async ({
    backendClient,
    testNamespace,
    providerKeys,
  }) => {
    test.setTimeout(180_000);

    const providerName = `${testNamespace}-envelope`;
    // Two models on ONE provider: the mock keys both its forced status and its
    // forced body shape on the model in the request body, so the envelope is
    // the only thing that differs between the two calls below. Two providers
    // would also differ in base URL, auth and creation order.
    const documentedModel = `${testNamespace}-documented`;
    const bareModel = `${testNamespace}-bare`;
    const qualified = (model: string) => `custom-llm/${providerName}/${model}`;

    await test.step('Register one gateway serving a documented and a bare error body', async () => {
      await providerKeys.createOauth({
        providerName,
        modelNames: [documentedModel, bareModel],
      });
      await providerKeys.forceChatStatus(documentedModel, DOCUMENTED_STATUS, {
        envelope: 'documented',
        message: DOCUMENTED_MARKER,
      });
      await providerKeys.forceChatStatus(bareModel, BARE_STATUS, {
        envelope: 'bare',
        message: BARE_MARKER,
      });
    });

    await test.step('The proxy passes a documented envelope\'s message through verbatim', async () => {
      const result = await backendClient.postChatCompletion({ model: qualified(documentedModel) });
      expect(
        result.status,
        'a documented envelope resolves to the status its shape implies',
      ).toBe(DOCUMENTED_STATUS);
      expect(
        result.message,
        'the provider\'s own text is what the caller reads, unchanged and unwrapped',
      ).toBe(DOCUMENTED_MARKER);
    });

    await test.step('The proxy replaces a bare envelope\'s message and keeps its status', async () => {
      const result = await backendClient.postChatCompletion({ model: qualified(bareModel) });
      expect(
        result.status,
        'the wire status survives even though the body could not be parsed',
      ).toBe(BARE_STATUS);
      expect(
        result.message,
        'an undocumented body\'s text is replaced wholesale, not merely truncated',
      ).toBe(GENERIC_MESSAGE);
      // The complement, and the half that makes the assertion above mean
      // suppression rather than reformatting: none of the gateway's own words
      // survive anywhere in what the caller is handed.
      expect(
        result.message,
        'no fragment of the undocumented body leaks into the replacement',
      ).not.toContain(BARE_MARKER);
    });

  });

  test('The Playground renders the kept message for a documented envelope and the generic one for a bare envelope', { tag: ['@cap:playground.run-error-info'] }, async ({
    page,
    project,
    backendClient,
    testNamespace,
    providerKeys,
  }) => {
    // QA: failing against a local OSS build for 2.2.125, and reproduced outside
    // Playwright entirely — one provider, one forced body, two calls:
    //
    //   stream=false -> HTTP 400 {"message": "<the gateway's own words>"}
    //   stream=true  -> HTTP 200, SSE event {"code": 500, "message":
    //                   "Unexpected error calling LLM provider"}
    //
    // The Playground only ever sends `stream: true`
    // (`useCompletionProxyStreaming`), so neither half of the contract this
    // spec is about reaches the user: the documented envelope's message is
    // dropped, AND the provider's 400 is downgraded to a 500 — which
    // `ChatCompletionService`'s own comment on the streaming branch says must
    // not happen ("an unparsed envelope must not downgrade a provider 4xx to
    // the 500 that a bare ErrorMessage(String) defaults to").
    //
    // Root cause is one step earlier and explains both halves at once. On the
    // streaming path the throwable the error consumer is handed is an
    // `EOFException`, not langchain4j's `HttpException`, so
    // `extractErrorJson` finds no body ("failed to parse CustomLlmErrorMessage
    // message" in the backend log) and `findProviderHttpStatus` finds no
    // status; both recoveries miss and the generic 500 falls out of the last
    // branch. The blocking path, over byte-identical gateway bytes, parses the
    // same body fine — the mock answers the forced status before it looks at
    // `stream` at all — so this is the product, not the fixture.
    //
    // NOT weakened to match: `playground.run-error-info` is a capability about
    // what a user READS when a run fails, and the generic line is the whole
    // failure. The assertions below are the CORRECT behaviour and are left
    // intact. Remove test.fail() once the streaming branch recovers the
    // provider's body; Playwright reports it the moment this passes.
    //
    // The bare-envelope step below never executes while this is marked, since
    // the documented one fails first. It is kept beside it so the pair reads as
    // one contract and both halves come back together.
    test.fail();
    test.setTimeout(180_000);

    const providerName = `${testNamespace}-envelope-ui`;
    const documentedModel = `${testNamespace}-documented-ui`;
    const bareModel = `${testNamespace}-bare-ui`;

    await test.step('Register one gateway serving a documented and a bare error body', async () => {
      await providerKeys.createOauth({
        providerName,
        modelNames: [documentedModel, bareModel],
      });
      await providerKeys.forceChatStatus(documentedModel, DOCUMENTED_STATUS, {
        envelope: 'documented',
        message: DOCUMENTED_MARKER,
      });
      await providerKeys.forceChatStatus(bareModel, BARE_STATUS, {
        envelope: 'bare',
        message: BARE_MARKER,
      });
    });

    await test.step('The blocking endpoint still keeps the documented message, so the gateway is set up right', async () => {
      // The premise the expected-to-fail assertions below rest on, asserted
      // here where a failure is loud rather than swallowed by `test.fail()`: a
      // gateway that was never reachable, or a body the mock refused to force,
      // would otherwise be indistinguishable from the streaming defect.
      const result = await backendClient.postChatCompletion({
        model: `custom-llm/${providerName}/${documentedModel}`,
      });
      expect(result.status).toBe(DOCUMENTED_STATUS);
      expect(result.message).toBe(DOCUMENTED_MARKER);
    });

    const playground = new PlaygroundPage(page, project.id);

    await test.step('The Playground shows the kept message for the documented envelope', async () => {
      await playground.goto();
      await playground.waitForReady();
      await playground.configureVariant(0, {
        userPrompt: 'Reply with the word OK.',
        modelDisplayName: documentedModel,
      });
      await playground.clickRun();
      await playground.waitForPromptOutputErrors(1);
      await expect(
        playground.promptOutputErrorTags(),
        'a user debugging a gateway that answers a documented envelope reads its own words',
      ).toHaveText([`Run failed: ${DOCUMENTED_MARKER}`]);
      await expect(
        playground.promptOutputMarkdownBlocks(),
        'a failed run renders no markdown answer',
      ).toHaveCount(0);
    });

    await test.step('The Playground shows the generic message for the bare envelope', async () => {
      await playground.configureVariant(0, {
        userPrompt: 'Reply with the word OK.',
        modelDisplayName: bareModel,
      });
      await playground.clickRun();
      await playground.waitForPromptOutputErrors(1);
      await expect(
        playground.promptOutputErrorTags(),
        'a gateway answering a bare body leaves the user with the generic line only',
      ).toHaveText([`Run failed: ${GENERIC_MESSAGE}`]);
    });
  });
});
