import { test, expect, PROVIDER_GROUP } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { VERTEX_AI_MODEL } from '@e2e/pom/model-availability';

/**
 * What a user is told when Opik cannot read the workspace's Vertex AI
 * service-account key — and that the attempt was routed to Vertex AI at all
 * (opik#9356, OPIK-7855).
 *
 * That PR replaced the whole Vertex client: GAX and langchain4j's
 * `VertexAiGeminiChatModel` out, the `google-genai` `Client` in, with the
 * endpoint derived by the SDK and every error status re-derived from
 * `ApiException.code()` instead of GAX's. `VertexAIClientGenerator` is the new
 * seam, and its first act on every call is to parse the stored key:
 * `ServiceAccountCredentials.fromStream` on an unparseable key raises
 * `ProviderRequestRefusedException(VERTEX_AI_KEY_UNREADABLE)` before any
 * provider HTTP call is made.
 *
 * ## Why this is not the Vertex spec the branch already has
 *
 * `playground-vertex-ai-run.spec.ts` covers the SUCCESS arm and needs a real
 * service-account key, so it carries `@llm-daily` and `test.skip`s on every
 * environment that holds none — which is CI and staging both. The refusal arm
 * is the complement, and it is the arm that can be driven everywhere for free:
 * the credential it needs is a key that cannot work, which is exactly what the
 * estate's placeholder is. The two partition the space and the gate below is
 * the mirror image of `vertexAiKeyOrSkip`.
 *
 * Nothing else in the estate reaches this code. `model-registry-providers.fixture.ts`
 * seeds placeholder keys so the picker will OFFER Vertex models, and every
 * spec built on it (`playground-gemini-thinking-level`,
 * `playground-reasoning-model-parameters`, `playground-max-output-tokens-no-limit`)
 * short-circuits `chat/completions` in the browser with `captureCompletionBody`
 * — so each asserts what the frontend SENDS and none that anything answers.
 *
 * ## The three claims, and why each is its own
 *
 * 1. **The blocking branch answers 400, never a flat 500.** This is the status
 *    re-derivation the PR rewrote, read at the one endpoint that returns a
 *    status at all. `failIfRequestRefused` says in its own comment that a
 *    request Opik refused before sending "is the caller's 400, never a 500".
 *
 * 2. **The message is the fixed sentence and nothing else.** Load-bearing for a
 *    reason that is not cosmetic: the thing being parsed is a private key, and
 *    `loadCredentials` catches the `IOException` precisely because "the parser
 *    can quote the key file back, so the refusal carries none of it". Asserted
 *    as EQUALITY rather than as a `not.toContain` over key fragments, because
 *    equality is the only form that also excludes a stack frame, a file path or
 *    a byte offset nobody thought to look for. The release report's manual
 *    item 4 asks a human to confirm this PR's log-redaction work; this is the
 *    half of it a test can hold.
 *
 * 3. **A run picked from the Vertex AI group is answered by vertex-ai.** The
 *    response's `X-Opik-Provider` / `X-Opik-Actual-Model` pair, which is the
 *    only honest way to assert the routing: "Gemini 2.5 Flash" is offered by
 *    BOTH the Gemini and the Vertex AI groups, so a picker label proves
 *    nothing, and selecting by display name alone lands on AI Studio's
 *    `gemini-2.5-flash` and comes back with a plausible-looking Google key
 *    error that is not a Vertex failure at all. (Found the hard way during this
 *    release's staging exploration; also why `selectModelFromProvider` exists.)
 *
 * ## Cost
 *
 * Zero, by construction rather than by stubbing: the refusal is raised inside
 * Opik before a socket is opened, so no provider is reached on either branch
 * and no token is billed. `providerKeys.ensureBuiltIn` is what makes that a
 * fact and not a hope — it REUSES a key the workspace already has only when
 * `isPlaceholderApiKey` says it cannot work, and throws with an actionable
 * message when the workspace holds a real Vertex credential. This is the first
 * caller whose subject IS the junk key, so the fixture's "indistinguishable
 * from a real one" note does not apply here: a real key would answer, and the
 * fixture refuses to let that happen silently.
 *
 * `@t2-cuj` and not `@llm-daily`, unlike its sibling, for exactly that reason.
 */

/**
 * `ProviderRequestRefusedException.Reason.VERTEX_AI_KEY_UNREADABLE`, verbatim.
 *
 * Pinned as a literal rather than derived, because the point of the assertion
 * is that this exact text is what a user reads: it is a fixed string in the
 * backend by design — "the message is returned to the caller, so it is always
 * one of the fixed texts below and never carries input or detail" — so a build
 * that started interpolating the key, the parser's complaint or the model id
 * into it must fail here.
 */
const REFUSAL = [
  'Vertex AI service account key could not be read:',
  "it must be a service account's JSON key file",
].join(' ');

/** `PlaygroundOutputError`'s prefix, as the tag renders it. */
const RUN_FAILED = `Run failed: ${REFUSAL}`;

/** One short message. Never generated, never read — the run is refused. */
const USER_PROMPT = 'Reply with the word OK.';

test.describe(
  'Playground — a Vertex AI service-account key the Gen AI SDK cannot read',
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    test.use({ viewport: { width: 1600, height: 900 } });

    /**
     * The mirror image of `vertexAiKeyOrSkip`: the success spec needs a real
     * key, this one needs a key that cannot work. A runner holding
     * `VERTEX_AI_API_KEY` is one where `modelRegistryProviders` may have
     * written that real key into an empty workspace slot, and a working key
     * answers instead of refusing — so the premise of everything below is gone
     * and the honest thing is to stand down rather than report a Vertex
     * regression.
     *
     * A workspace that holds a real key for some OTHER reason is caught by
     * `ensureBuiltIn` instead, which throws rather than skipping. The two
     * together mean this spec never runs against a billable credential and
     * never passes vacuously.
     */
    test.beforeAll(() => {
      test.skip(
        !!process.env.VERTEX_AI_API_KEY,
        'VERTEX_AI_API_KEY is set, so this workspace may hold a WORKING Vertex AI key — ' +
          'the refusal arm needs a key that cannot be read. playground-vertex-ai-run.spec.ts ' +
          'covers the success arm on exactly the environments this skips on',
      );
    });

    test(
      'the blocking completion endpoint refuses it with a 400 whose message quotes none of the key',
      { tag: ['@cap:playground.run-error-info'] },
      async ({ providerKeys, backendClient }) => {
        await test.step('The workspace has a Vertex AI key that cannot be read', async () => {
          // `VERTEX_AI_MODEL` carries all three names this spec needs — the
          // picker's label, the backend's model id and the provider key's own
          // type — and they differ; see its own comment.
          await providerKeys.ensureBuiltIn(VERTEX_AI_MODEL.provider, [
            VERTEX_AI_MODEL.displayName,
          ]);
        });

        await test.step('POST /v1/private/chat/completions answers 400 and the fixed sentence', async () => {
          const result = await backendClient.postChatCompletion({
            model: VERTEX_AI_MODEL.modelId,
            messages: [{ role: 'user', content: USER_PROMPT }],
          });

          expect(
            result.status,
            'a request Opik refused before sending is the caller\'s 400 — a 500 here is the ' +
              'status re-derivation this PR rewrote failing to classify its own exception',
          ).toBe(400);
          // Equality, not containment: see claim 2 in the file header. Anything
          // appended — the key, the parser's own complaint, a stack frame — is
          // a leak, and only an exact match rules all of them out at once.
          expect(
            result.message,
            'the refusal is the fixed sentence and nothing else, because what it declined to ' +
              'parse was a private key',
          ).toBe(REFUSAL);
        });
      },
    );

    test(
      'a run picked from the Vertex AI group is answered by vertex-ai and renders the refusal verbatim with no answer',
      {
        tag: [
          '@cap:playground.select-model-provider',
          '@cap:playground.run-error-info',
        ],
      },
      async ({ providerKeys, backendClient, project, page }) => {
        // A page load, a picker that remounts when /llm/models resolves, and a
        // round trip to the backend — past the 90s default.
        test.setTimeout(180_000);

        await test.step('The workspace has a Vertex AI key that cannot be read', async () => {
          await providerKeys.ensureBuiltIn(VERTEX_AI_MODEL.provider, [
            VERTEX_AI_MODEL.displayName,
          ]);
        });

        await test.step('Confirm server-side that the key really is refused, before opening a browser', async () => {
          // The premise the UI assertions rest on, asserted where a failure
          // names its own cause. Without it, a workspace whose Vertex key had
          // started working would surface below as "the failure tag never
          // rendered" — indistinguishable from the frontend having dropped the
          // message, which is the defect this spec exists to catch.
          const probe = await backendClient.postChatCompletion({
            model: VERTEX_AI_MODEL.modelId,
            messages: [{ role: 'user', content: USER_PROMPT }],
          });
          expect(
            probe.message,
            'this workspace\'s Vertex AI key must be one the backend cannot read, or there is ' +
              'no refusal for the Playground to render',
          ).toBe(REFUSAL);
        });

        const playground = new PlaygroundPage(page, project.id);

        const completion = await test.step(
          `Run a prompt on "${VERTEX_AI_MODEL.displayName}" in the ${PROVIDER_GROUP.vertexAi} group`,
          async () => {
            await playground.goto();
            await playground.waitForReady();
            // By group, not by display name: the Gemini group offers the same
            // label for `gemini-2.5-flash`, and a selection that landed there
            // would fail with Google's own key error — which reads like a
            // Vertex failure and is not one.
            await playground.selectModelFromProvider(
              0,
              PROVIDER_GROUP.vertexAi,
              VERTEX_AI_MODEL.displayName,
            );
            await playground.configureVariant(0, { userPrompt: USER_PROMPT });

            // Armed before Run: the proxy call is in flight the moment the
            // button is pressed, and its headers are the only place the
            // resolved provider appears.
            const answered = page.waitForResponse(
              (r) => r.url().includes('/v1/private/chat/completions'),
              { timeout: 120_000 },
            );
            await playground.clickRun();
            return await answered;
          },
        );

        await test.step('The completion was routed to Vertex AI, not to AI Studio', async () => {
          const headers = completion.headers();
          expect(
            headers['x-opik-provider'],
            'the provider the backend resolved the request onto — `gemini` here would mean the ' +
              'picker selected the AI Studio twin of this display name',
          ).toBe(VERTEX_AI_MODEL.provider);
          expect(
            headers['x-opik-actual-model'],
            'and the provider-qualified model id it resolved, the other half of the same pair',
          ).toBe(VERTEX_AI_MODEL.modelId);
        });

        await test.step("The output panel shows Opik's own refusal and no answer", async () => {
          // Exactly one: a second tag would mean the frontend retried the
          // refused run, which bills nothing here but would on a working key.
          await playground.waitForPromptOutputErrors(1);
          await expect(
            playground.promptOutputErrorTags(),
            'the sentence reaches the user intact — a generic "Unexpected error calling LLM ' +
              'provider" here is the streaming branch losing a message it never had to recover',
          ).toHaveText([RUN_FAILED]);
          await expect(
            playground.promptOutputMarkdownBlocks(),
            'and no MarkdownPreview answer rendered beside it — a dropped error falling back ' +
              'into the ordinary output path is what reads to a user like a reply',
          ).toHaveCount(0);
        });
      },
    );
  },
);
