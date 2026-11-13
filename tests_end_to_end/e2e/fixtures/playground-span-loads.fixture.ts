import { expect } from '@playwright/test';
import { test as baseTest } from './truncated-thread-messages.fixture';
import { REGISTRY_MODEL, PROVIDER_GROUP } from './model-registry-providers.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7, type SpanBatchSeed, type TraceBatchSeed } from '../core/backend';
import {
  fetchRegistryModels,
  listsInPicker,
  type RegistryModel,
  type RegistryModelsMap,
} from '../core/model-registry';

/**
 * The provider names the SDKs log for the two Google routes.
 *
 * `useLoadSpanIntoPlayground.LOGGED_PROVIDER_KEYS` maps these to
 * `PROVIDER_TYPE.GEMINI` and `PROVIDER_TYPE.VERTEX_AI`; every other logged
 * provider name already matches a provider key. These two are the only logged
 * names that need translating, and they are the pair that resolves to the same
 * display LABEL under two different providers — which is what makes a
 * label-only assertion unable to tell them apart.
 */
export const LOGGED_GOOGLE_AI = 'google_ai';
export const LOGGED_GOOGLE_VERTEXAI = 'google_vertexai';

/** One seeded span, and what loading it into the Playground must produce. */
export interface PlaygroundSpanSeedRef {
  /** Stable handle the spec looks this span up by. */
  key:
    | 'configured-model'
    | 'google-ai'
    | 'google-vertexai'
    | 'unknown-model'
    | 'not-an-llm-span'
    | 'llm-without-messages';
  spanId: string;
  /** The span's `name`, which is also its span-tree node label. */
  name: string;
  /** What was logged as the span's `model`, or null when none was. */
  model: string | null;
  /** What was logged as the span's `provider`, or null when none was. */
  provider: string | null;
  /** Whether the toolbar must offer "Open in Playground" for this span. */
  loadable: boolean;
  /**
   * The user message text this span's input carries, when it carries messages.
   *
   * Distinct per span so "the right span's messages were loaded" is assertable
   * rather than only "some messages were loaded".
   */
  userMessage: string | null;
  /**
   * The `{model, provider}` pair `PLAYGROUND_STATE` must hold after this span
   * is loaded, when the load is expected to resolve the span's own model.
   *
   * Absent for the spans whose model should NOT be adopted — the unknown one,
   * whose resolution leaves the last picked model in place. Absent rather than
   * a sentinel, so a spec cannot compare against it by accident.
   */
  expectedStored?: { model: string; provider: string };
  /** The label the model picker's trigger must read. */
  expectedModelLabel?: string;
}

export interface PlaygroundSpanLoadsRef {
  projectId: string;
  projectName: string;
  /** The one trace all the spans below hang off. */
  traceId: string;
  traceName: string;
  /** The trace's thread, so the panel can also be opened over the Threads tab. */
  threadId: string;
  spans: PlaygroundSpanSeedRef[];
  /** Lookup by `key`, so a spec reads `seed.span('google-ai')`. */
  span: (key: PlaygroundSpanSeedRef['key']) => PlaygroundSpanSeedRef;
  /**
   * The model the specs SELECT through the picker, to establish a known
   * last-picked model before loading the unknown-model span.
   */
  pickerModel: { label: string; providerGroup: string; id: string; provider: string };
  /** The display label both Google providers render for the shared model. */
  sharedGoogleLabel: string;
}

export interface PlaygroundSpanLoadsFixtures {
  playgroundSpanLoads: PlaygroundSpanLoadsRef;
}

/** The dropdown-visible row one provider serves under an exact display label. */
const rowByLabel = (
  registry: RegistryModelsMap,
  provider: string,
  label: string,
): RegistryModel => {
  const rows = registry[provider];
  if (!Array.isArray(rows)) {
    throw new Error(
      `playgroundSpanLoads: the model registry carries no '${provider}' provider ` +
        `(it has: ${Object.keys(registry).join(', ') || 'nothing'})`,
    );
  }
  const matches = rows.filter((row) => listsInPicker(row) && row.label === label);
  if (matches.length !== 1) {
    throw new Error(
      `playgroundSpanLoads: '${provider}' offers ${matches.length} dropdown models ` +
        `labelled '${label}', expected exactly 1 — a spec that picked one of several ` +
        'would be asserting against whichever the catalog ordered first',
    );
  }
  return matches[0];
};

/**
 * The value a provider's dropdown holds for a registry row — the frontend's own
 * rule, `getRoutableProviderModelValue(provider, qualified_name || id)`.
 *
 * This is the whole mechanism behind the Gemini / Vertex AI collision: both
 * providers serve the same bare `id` under the same `label`, and only Vertex
 * AI's row carries a `qualified_name`, so the two resolve to different stored
 * values. Mirrored here rather than hard-coded so the expectation is derived
 * from the deployment's own catalog.
 */
const routableValue = (provider: string, row: RegistryModel): string => {
  const value = row.qualified_name || row.id;
  return provider === 'vertex-ai' && !value.includes('/') ? `vertex_ai/${value}` : value;
};

/**
 * One trace carrying six spans, each a different answer to "can this be opened
 * in the Playground, and on which model?" — the feature opik#8725 shipped
 * (issue-3411).
 *
 * The "Open in Playground" (Play) toolbar action loads any logged LLM span's
 * input messages into the Playground as a chat prompt, on the span's own model.
 * Nothing in the estate mentions it: `playground` reads 19/19 capabilities but
 * none of those keys names this entry point, and the in-PR coverage is unit
 * tests (`spanPlaygroundMessages.test.ts`, `useLoadSpanIntoPlayground.test.ts`,
 * seven `TraceDataToolbar.test.tsx` visibility cases) that MOCK the model
 * catalog — which is precisely the thing model resolution runs against.
 *
 * ## The six spans, and why each is load-bearing
 *
 *  - **`configured-model`** — logged on a model a configured provider offers,
 *    under that provider's own name. The ordinary case, and the control for the
 *    two below: if it did not resolve, nothing about the Google pair would mean
 *    anything.
 *  - **`google-ai` and `google-vertexai`** — the SAME bare model id, logged
 *    under the two names the SDKs use for Google. Both render the IDENTICAL
 *    display label in the picker, because both providers serve the model under
 *    it, so this pair is the one case a label assertion cannot decide: the test
 *    has to read the resolved `{model, provider}` out of the store. These are
 *    also the only two logged provider names `LOGGED_PROVIDER_KEYS` translates
 *    at all.
 *  - **`unknown-model`** — logged on a model no provider offers. Its model must
 *    NOT be adopted; `createPromptFromContent` finds no `preferredModelProvider`
 *    and falls back to the last picked model, so the card keeps whatever the
 *    user last chose rather than emptying the picker.
 *  - **`not-an-llm-span`** — a `general` span carrying a perfectly good messages
 *    input. `getSpanPlaygroundMessages` gates on `type === llm` FIRST, so this
 *    separates "the type gate works" from "the payload gate works". A span with
 *    an unreadable payload would satisfy both at once.
 *  - **`llm-without-messages`** — an `llm` span whose input is not messages, so
 *    `detectLLMMessages` yields nothing. The mirror of the previous one.
 *
 * The TRACE itself carries a messages-shaped input, so "the action is absent
 * when only the trace is selected" is a statement about `canOpenSpanInPlayground`'s
 * `"type" in data` gate rather than about the trace happening to hold a payload
 * nobody could read.
 *
 * The trace is given a `thread_id` so the same panel can be opened over the
 * Threads tab, which is the other layout that mounts this toolbar.
 *
 * ## Why the ids come from the live catalog
 *
 * `useLoadPlayground` resolves `preferredModel` against `useLLMProviderModelsData`,
 * i.e. `GET /v1/private/llm/models` as this deployment serves it. A spec naming a
 * model id would be asserting the in-tree constant instead, and would go
 * vacuous the moment a provider-model sync moved it — `scripts/sync_provider_models.py`
 * regenerates which rows carry a label on every run. So the ids are read from
 * the registry by the curated LABEL (`REGISTRY_MODEL`, which the rest of the
 * playground suite already selects by), and the fixture asserts the premises it
 * needs before a single span is written: that both Google providers offer the
 * model, that they offer it under one label, that their routable values differ,
 * and that the "unknown" id is in nobody's list.
 *
 * ## Seeding and teardown
 *
 * One `/traces/batch` then one `/spans/batch`, so the six spans and their trace
 * land in two requests with ids the fixture chose. Teardown deletes the trace,
 * which takes its spans with it; nothing else is created. The provider keys the
 * load resolves against come from `modelRegistryProviders`, which is strictly
 * additive and deliberately tears nothing down (a built-in provider is one row
 * per workspace).
 */
export const test = baseTest.extend<PlaygroundSpanLoadsFixtures>({
  playgroundSpanLoads: async (
    { backendClient, project, testNamespace, modelRegistryProviders },
    use,
    testInfo,
  ) => {
    const traceIds: string[] = [];

    try {
      const registry = await fetchRegistryModels();

      // ---- resolve the models from the deployment's own catalog ----

      const openAiRow = rowByLabel(registry, 'openai', REGISTRY_MODEL.openAiStandard);
      const geminiRow = rowByLabel(registry, 'gemini', REGISTRY_MODEL.gemini2);
      const vertexRow = rowByLabel(registry, 'vertex-ai', REGISTRY_MODEL.gemini2);

      const geminiValue = routableValue('gemini', geminiRow);
      const vertexValue = routableValue('vertex-ai', vertexRow);

      // The premise the whole Google half rests on: one bare model id, one
      // display label, two providers, two different stored values. Asserted
      // against the live catalog because every clause of it is something a
      // provider-model sync can change.
      expect(
        geminiRow.id,
        'both Google providers serve the shared model under the same bare id — ' +
          'that is what makes the logged `model` ambiguous in the first place',
      ).toBe(vertexRow.id);
      expect(
        geminiRow.label,
        'and under the same display label, so the picker trigger cannot tell them apart',
      ).toBe(vertexRow.label);
      expect(
        vertexValue,
        'while their dropdown VALUES differ — Vertex AI qualifies the id',
      ).not.toBe(geminiValue);
      expect(
        vertexValue,
        'with the vertex_ai route prefix the frontend adds',
      ).toBe(`vertex_ai/${geminiRow.id}`);

      // And that the providers the resolution needs are really on the workspace.
      // `modelRegistryProviders` ensures a key for each, but it reports what it
      // had to ADD rather than what is usable, so the catalog read above is the
      // confirmation that matters and this is the configuration half of it.
      for (const provider of ['openai', 'gemini', 'vertex-ai'] as const) {
        expect(
          modelRegistryProviders.providers,
          `the ${provider} provider is configured, so its models are offered`,
        ).toContain(provider);
      }

      // An id nobody serves, namespaced to this run so it cannot collide with a
      // model a sync adds later. Asserted absent across EVERY provider the
      // registry answers for, not just the one the span logs it under: the
      // resolver searches all configured provider keys.
      const unknownModelId = `${testNamespace}-no-such-model`;
      for (const [provider, rows] of Object.entries(registry)) {
        expect(
          rows.map((row) => row.id),
          `no '${provider}' model is called '${unknownModelId}' — the span's model ` +
            'must be unresolvable or it would be adopted',
        ).not.toContain(unknownModelId);
      }

      // ---- build the seed ----

      const traceId = uuid7();
      const traceName = `${testNamespace}-span-loads`;
      const threadId = `${testNamespace}-span-loads-thread`;
      const messagesInput = (text: string) => ({
        messages: [{ role: 'user', content: text }],
      });

      const spanSeeds: Array<
        PlaygroundSpanSeedRef & { type: 'general' | 'llm'; input: Record<string, unknown> }
      > = [
        {
          key: 'configured-model',
          spanId: uuid7(),
          name: `${testNamespace}-span-configured`,
          type: 'llm',
          model: openAiRow.id,
          provider: 'openai',
          loadable: true,
          userMessage: `${testNamespace} configured-model turn`,
          input: messagesInput(`${testNamespace} configured-model turn`),
          expectedStored: { model: routableValue('openai', openAiRow), provider: 'openai' },
          expectedModelLabel: openAiRow.label!,
        },
        {
          key: 'google-ai',
          spanId: uuid7(),
          name: `${testNamespace}-span-google-ai`,
          type: 'llm',
          model: geminiRow.id,
          provider: LOGGED_GOOGLE_AI,
          loadable: true,
          userMessage: `${testNamespace} google-ai turn`,
          input: messagesInput(`${testNamespace} google-ai turn`),
          expectedStored: { model: geminiValue, provider: 'gemini' },
          expectedModelLabel: geminiRow.label!,
        },
        {
          key: 'google-vertexai',
          spanId: uuid7(),
          name: `${testNamespace}-span-google-vertexai`,
          type: 'llm',
          model: vertexRow.id,
          provider: LOGGED_GOOGLE_VERTEXAI,
          loadable: true,
          userMessage: `${testNamespace} google-vertexai turn`,
          input: messagesInput(`${testNamespace} google-vertexai turn`),
          expectedStored: { model: vertexValue, provider: 'vertex-ai' },
          expectedModelLabel: vertexRow.label!,
        },
        {
          key: 'unknown-model',
          spanId: uuid7(),
          name: `${testNamespace}-span-unknown-model`,
          type: 'llm',
          model: unknownModelId,
          provider: 'openai',
          loadable: true,
          userMessage: `${testNamespace} unknown-model turn`,
          input: messagesInput(`${testNamespace} unknown-model turn`),
        },
        {
          key: 'not-an-llm-span',
          spanId: uuid7(),
          name: `${testNamespace}-span-not-llm`,
          type: 'general',
          model: null,
          provider: null,
          loadable: false,
          userMessage: `${testNamespace} not-an-llm turn`,
          input: messagesInput(`${testNamespace} not-an-llm turn`),
        },
        {
          key: 'llm-without-messages',
          spanId: uuid7(),
          name: `${testNamespace}-span-no-messages`,
          type: 'llm',
          model: openAiRow.id,
          provider: 'openai',
          loadable: false,
          userMessage: null,
          // Deliberately NOT a messages array: `detectLLMMessages` reads nothing
          // from it, so `getSpanPlaygroundMessages` answers `[]` and the action
          // is hidden — on an otherwise perfectly ordinary llm span.
          input: { prompt: `${testNamespace} a bare prompt string, not messages` },
        },
      ];

      // The two gates are separated, not merely both present: the non-llm span
      // must carry a READABLE payload and the payload-less span must be an llm,
      // or one gate could hide the other failing.
      const notLlm = spanSeeds.find((s) => s.key === 'not-an-llm-span')!;
      const noMessages = spanSeeds.find((s) => s.key === 'llm-without-messages')!;
      expect(notLlm.type, 'the type-gate span is not an llm span').toBe('general');
      expect(
        Array.isArray((notLlm.input as { messages?: unknown }).messages),
        'but it does carry a messages input, so only its TYPE can be hiding the action',
      ).toBe(true);
      expect(noMessages.type, 'the payload-gate span IS an llm span').toBe('llm');
      expect(
        (noMessages.input as { messages?: unknown }).messages,
        'and carries no messages, so only its PAYLOAD can be hiding the action',
      ).toBeUndefined();

      const userMessages = spanSeeds
        .map((s) => s.userMessage)
        .filter((m): m is string => m !== null);
      expect(
        new Set(userMessages).size,
        'every loadable span carries its own message text, so "the right span loaded" is assertable',
      ).toBe(userMessages.length);

      const traces: TraceBatchSeed[] = [
        {
          id: traceId,
          name: traceName,
          // Messages-shaped, so the trace-selected case is about the type gate.
          input: messagesInput(`${testNamespace} the trace's own input`),
          output: { choices: [{ message: { role: 'assistant', content: 'ok' } }] },
          threadId,
        },
      ];
      const spans: SpanBatchSeed[] = spanSeeds.map((seed) => ({
        id: seed.spanId,
        traceId,
        name: seed.name,
        type: seed.type,
        input: seed.input,
        output: { choices: [{ message: { role: 'assistant', content: 'ok' } }] },
        ...(seed.model === null ? {} : { model: seed.model }),
        ...(seed.provider === null ? {} : { provider: seed.provider }),
      }));

      const ids = [traceId, ...spans.map((s) => s.id)];
      expect(new Set(ids).size, 'the seed minted no duplicate id').toBe(ids.length);

      // ---- write ----

      await backendClient.createTracesBatch({ projectName: project.name, traces });
      traceIds.push(traceId);
      await backendClient.createSpansBatch({ projectName: project.name, spans });

      // Every span must be readable before the browser opens: the panel fills
      // its tree from one `GET /v1/private/spans?trace_id=…`, and a span that
      // had not landed would read as the action being absent.
      await expect
        .poll(
          async () =>
            (await backendClient.listSpanRefs({ projectId: project.id, traceId }))
              .map((s) => s.name)
              .sort()
              .join(','),
          {
            message: 'all six seeded spans must be readable before the spec opens the panel',
            timeout: 60_000,
            intervals: [1_000, 2_000, 3_000],
          },
        )
        .toBe(
          spans
            .map((s) => s.name)
            .sort()
            .join(','),
        );

      const byKey = new Map(spanSeeds.map((seed) => [seed.key, seed as PlaygroundSpanSeedRef]));
      const ref: PlaygroundSpanLoadsRef = {
        projectId: project.id,
        projectName: project.name,
        traceId,
        traceName,
        threadId,
        spans: spanSeeds.map((seed) => seed as PlaygroundSpanSeedRef),
        span: (key) => {
          const found = byKey.get(key);
          if (!found) throw new Error(`playgroundSpanLoads: no seeded span '${key}'`);
          return found;
        },
        pickerModel: {
          label: REGISTRY_MODEL.openAiStandard,
          providerGroup: PROVIDER_GROUP.openAi,
          id: routableValue('openai', openAiRow),
          provider: 'openai',
        },
        sharedGoogleLabel: geminiRow.label!,
      };

      await testInfo.attach('opik.playgroundSpanLoads', {
        body: JSON.stringify(
          {
            projectId: ref.projectId,
            projectName: ref.projectName,
            traceId: ref.traceId,
            threadId: ref.threadId,
            sharedGoogleLabel: ref.sharedGoogleLabel,
            pickerModel: ref.pickerModel,
            spans: ref.spans,
          },
          null,
          2,
        ),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo) && traceIds.length > 0) {
        // Swallow-and-warn, never rethrow: a delete that failed here would
        // replace whichever seeding or assertion error actually broke the test.
        try {
          await backendClient.deleteTraces(traceIds);
        } catch (err) {
          console.warn('[playgroundSpanLoads fixture] trace delete warning:', err);
        }
      }
    }
  },
});

export { expect } from './truncated-thread-messages.fixture';
