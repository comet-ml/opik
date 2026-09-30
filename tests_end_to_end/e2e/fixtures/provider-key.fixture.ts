import { test as baseTest } from './automation-rules.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import {
  MOCK_AUTH_CLIENT_ID,
  MOCK_AUTH_CLIENT_SECRET,
  mockAuthClearChatStatus,
  mockAuthForceChatStatus,
  mockGatewayUrlForBackend,
  mockTokenUrlForBackend,
} from '../core/mock-auth';
import {
  createProviderKey,
  deleteProviderKeyById,
  deleteProviderKeyByName,
  findProviderKeyByProvider,
  notFoundProviderBaseUrl,
} from '../core/provider-keys';
import { registerUnbilledModel } from '../core/llm-model-policy';

export interface OauthProviderSeed {
  providerName: string;
  /**
   * Models the mock gateway will echo; unique per test so parallel specs never collide.
   *
   * A list rather than a single name because the mock's counters and its forced-status
   * hook are both keyed on the model in the request body, so several models on ONE
   * provider is how a spec gets several gateway behaviours over one trace without
   * changing anything else about the rule.
   */
  modelNames?: string[];
}

export interface UnreachableProviderSeed {
  providerName: string;
  /** Model name; unique per test so parallel specs never collide. */
  modelName?: string;
}

export interface FailingProviderSeed {
  providerName: string;
  /**
   * Model segment of the qualified id. Needs no per-test uniqueness of its own —
   * `providerName` already namespaces the qualified id — so it defaults to a
   * readable constant.
   */
  modelName?: string;
}

/**
 * Seed for `createUnresponsive`. Structurally identical to
 * {@link UnreachableProviderSeed} and named separately on purpose: the two
 * fixtures produce OPPOSITE failure shapes — refused vs hung — and a caller
 * reading `createUnresponsive(seed: UnreachableProviderSeed)` in the editor or
 * in generated docs is told the wrong one.
 */
export type UnresponsiveProviderSeed = UnreachableProviderSeed;

/**
 * Base URL for a provider that can never answer.
 *
 * The discard port on the backend's own loopback: nothing listens there, so the
 * connect is REFUSED immediately and every scoring call through this provider
 * fails in milliseconds, with no dependency on the mock gateway, on an external
 * host, or on the runner being reachable from the backend at all. That last
 * point is why this is not `mockGatewayUrlForBackend` with a forced status: the
 * mock binds to the test runner, which a remote deployment cannot reach (see
 * `mockAuthSkipReason`), so a mock-based failure spec can only ever run locally.
 *
 * A blackholed address (`192.0.2.1`) would also fail, but by TIMING OUT — that
 * turns a one-second failure into a connect-timeout wait and, worse, can leave
 * the provider call still in flight when the scoring message is reclaimed.
 * Refusal is the deterministic shape.
 */
const UNREACHABLE_BASE_URL = 'http://127.0.0.1:9/v1';

/**
 * Base URL for a provider that never answers and never refuses.
 *
 * `192.0.2.1` is TEST-NET-1 (RFC 5737) — reserved for documentation, routed
 * nowhere — so a connect to it hangs until it times out instead of being
 * refused. That is the opposite of what `UNREACHABLE_BASE_URL` wants, and
 * deliberately so: a refused connect ends a Playground run in milliseconds,
 * which leaves no window in which a spec can click Stop. Holding the run open
 * is the whole point here.
 *
 * Only for specs that abort the run themselves and assert on the FRONTEND's
 * reaction. Do NOT point a scoring rule at this — the warning on
 * `UNREACHABLE_BASE_URL` stands, and an online-scoring message whose provider
 * call is still connecting when the message is reclaimed is exactly the
 * non-determinism that URL exists to avoid. Stop aborts the browser's own
 * request; whether the backend's upstream connect is still timing out behind it
 * is not something a caller here may depend on either way.
 */
const UNRESPONSIVE_BASE_URL = 'http://192.0.2.1/v1';

export interface ProviderKeysFixture {
  /**
   * REST-seeds a Custom provider in OAuth2 token-auth mode against the suite's mock
   * token service; registered for teardown deletion.
   */
  createOauth(seed: OauthProviderSeed): Promise<void>;
  /**
   * REST-seeds a Custom provider whose base URL refuses every connection, so any
   * rule pointed at it fails deterministically; registered for teardown deletion.
   *
   * Sibling of `createPermanentlyFailing`, and the choice between them is the
   * error SHAPE: this one is refused at connect, so the scorer reports a
   * transport failure with no provider body to quote. Use the other when the
   * assertion is about the status the provider itself answered.
   *
   * Returns the fully-qualified model id to put on a rule, rather than leaving
   * the caller to rebuild `custom-llm/<provider>/<model>`: a rule naming a model
   * string the provider does not declare fails for the wrong reason, and the
   * two spellings drifting apart would be invisible in the log stream.
   */
  createUnreachable(seed: UnreachableProviderSeed): Promise<string>;
  /**
   * REST-seeds a Custom provider whose base URL is blackholed, so a call to it
   * hangs rather than failing — which is what keeps a Playground run open long
   * enough for a spec to stop it. Registered for teardown deletion.
   *
   * Returns the fully-qualified model id, for the same reason
   * `createUnreachable` does.
   */
  createUnresponsive(seed: UnresponsiveProviderSeed): Promise<string>;
  /**
   * Makes the mock gateway answer `status` for every chat request naming `modelName`,
   * and clears it at teardown.
   *
   * The hook lives on the mock process, which outlives the test, so it is registered
   * here rather than reset in the test body — a trailing reset step is skipped exactly
   * when an assertion has already failed.
   */
  forceChatStatus(modelName: string, status: number): Promise<void>;
  /**
   * REST-seeds a Custom provider whose endpoint always answers a permanent HTTP
   * 404, and returns the qualified model id (`custom-llm/<provider>/<model>`) to
   * put in a rule or a Playground run.
   *
   * For specs about what Opik does when a provider REFUSES, which is otherwise
   * awkward to stage: a real provider needs credentials, and the suite's mock
   * gateway only exists on the test runner, so a remote backend can never reach
   * it (see `mockAuthSkipReason`). This one has no such gate — see
   * `notFoundProviderBaseUrl` for how.
   */
  createPermanentlyFailing(seed: FailingProviderSeed): Promise<string>;
  /**
   * Registers a provider name for teardown deletion without seeding — for tests where
   * UI creation is itself the behavior under test. Cleanup runs even when the test fails.
   */
  register(providerName: string): void;
  /**
   * Make sure a BUILT-IN provider (`openrouter`, `openai`, …) has a key,
   * reusing whatever the workspace already has.
   *
   * Built-ins are not like the custom providers above, and the difference
   * matters for cleanup: there is one key per provider per workspace and it
   * carries no `provider_name`, so a spec cannot namespace itself a private
   * one. This therefore REUSES an existing key untouched and seeds one only
   * when none exists — and teardown deletes only a key it seeded itself.
   * Deleting someone's real OpenRouter key on a shared workspace is not
   * something a test run can undo.
   *
   * The seeded key's secret is junk, which is fine for every caller so far:
   * `saveApiKey` performs no upstream check, so a key that is never used to
   * make a call is indistinguishable from a real one. `models` are registered
   * as unbilled for the same reason — a spec that CREATES a rule on one of
   * them never invokes it.
   *
   * Returns whether it seeded, so a caller can say so in its own diagnostics.
   */
  ensureBuiltIn(provider: string, models?: readonly string[]): Promise<{ seeded: boolean }>;
}

export interface ProviderKeyFixtures {
  providerKeys: ProviderKeysFixture;
}

/**
 * Provider keys are WORKSPACE-GLOBAL, so every spec must use testNamespace-prefixed
 * names and delete what it creates — this fixture owns the delete half.
 */
export const test = baseTest.extend<ProviderKeyFixtures>({
  // eslint-disable-next-line no-empty-pattern
  providerKeys: async ({}, use, testInfo) => {
    const registered: string[] = [];
    const forcedStatusModels: string[] = [];
    /** Built-in keys THIS test created, and therefore the only ones it may delete. */
    const seededBuiltInIds: string[] = [];

    await use({
      async createOauth({ providerName, modelNames = ['mock-model'] }) {
        registered.push(providerName);
        // The mock gateway answers these; nothing reaches a paid API.
        for (const m of modelNames) registerUnbilledModel(m);
        await createProviderKey({
          provider: 'custom-llm',
          provider_name: providerName,
          base_url: mockGatewayUrlForBackend,
          configuration: {
            models: modelNames.map((model) => `custom-llm/${providerName}/${model}`).join(','),
          },
          auth_config: {
            token_url: mockTokenUrlForBackend,
            send_as: 'basic',
            credentials: [
              { key: 'grant_type', value: 'client_credentials', secret: false },
              { key: 'client_id', value: MOCK_AUTH_CLIENT_ID, secret: false },
              { key: 'client_secret', value: MOCK_AUTH_CLIENT_SECRET, secret: true },
            ],
          },
        });
      },
      async createUnreachable({ providerName, modelName = 'unreachable-model' }) {
        registered.push(providerName);
        registerUnbilledModel(modelName);
        const model = `custom-llm/${providerName}/${modelName}`;
        await createProviderKey({
          provider: 'custom-llm',
          provider_name: providerName,
          base_url: UNREACHABLE_BASE_URL,
          // Required whenever `auth_config` is absent. Never sent anywhere: the
          // connection is refused before a request is written.
          api_key: 'unused-the-connection-is-refused',
          configuration: { models: model },
        });
        return model;
      },
      async createUnresponsive({ providerName, modelName = 'unresponsive-model' }) {
        registered.push(providerName);
        registerUnbilledModel(modelName);
        const model = `custom-llm/${providerName}/${modelName}`;
        await createProviderKey({
          provider: 'custom-llm',
          provider_name: providerName,
          base_url: UNRESPONSIVE_BASE_URL,
          // Required whenever `auth_config` is absent. Never sent anywhere: the
          // connect never completes, so no request is ever written.
          api_key: 'unused-the-connection-never-completes',
          configuration: { models: model },
        });
        return model;
      },
      async forceChatStatus(modelName, status) {
        forcedStatusModels.push(modelName);
        registerUnbilledModel(modelName);
        await mockAuthForceChatStatus(modelName, status);
      },
      async createPermanentlyFailing({ providerName, modelName = 'always-404-model' }) {
        registered.push(providerName);
        registerUnbilledModel(modelName);
        const qualifiedModel = `custom-llm/${providerName}/${modelName}`;
        await createProviderKey({
          provider: 'custom-llm',
          provider_name: providerName,
          base_url: notFoundProviderBaseUrl,
          // Never presented to a real provider — the request 404s at routing —
          // but the field is required for a provider that is not in token mode.
          api_key: 'not-a-real-key',
          configuration: { models: qualifiedModel },
        });
        return qualifiedModel;
      },
      register(providerName) {
        registered.push(providerName);
      },
      async ensureBuiltIn(provider, models = []) {
        for (const model of models) registerUnbilledModel(model);
        const existing = await findProviderKeyByProvider(provider);
        if (existing) return { seeded: false };
        try {
          await createProviderKey({
            provider,
            // Neither `provider_name` nor `base_url` is sent: a built-in provider
            // is keyed by its provider type and calls the vendor's own endpoint,
            // and the API rejects either field blank ("baseUrl must not be
            // blank"). Omitted, not empty.
            api_key: `qa-placeholder-${provider}-never-called`,
          });
        } catch (err) {
          // The check above and this create are not atomic, and the config runs
          // `fullyParallel`, so two workers can both look, both find nothing and
          // both try to create. A built-in provider holds ONE key per workspace,
          // so the loser of that race is refused. Losing is not a failure — the
          // key it wanted now exists — but the loser must NOT record it as
          // seeded, or its teardown would delete a key the winner is still
          // using. Re-read rather than trusting the status code, so this only
          // swallows a refusal that really did leave a usable key behind.
          const raced = await findProviderKeyByProvider(provider);
          if (!raced) throw err;
          return { seeded: false };
        }
        const created = await findProviderKeyByProvider(provider);
        if (!created) {
          throw new Error(
            `[provider-key fixture] seeded a ${provider} key but it does not list back — ` +
              'refusing to continue, since teardown could not then remove it',
          );
        }
        seededBuiltInIds.push(created.id);
        return { seeded: true };
      },
    });

    // Cleared unconditionally, unlike the provider keys below. Retention exists so a
    // failed run leaves INSPECTABLE state in the workspace; a forced status is not that.
    // It is mutable global state on the mock process, which outlives this test and
    // serves every other spec in the run, so leaving one set turns one failure into a
    // second, unrelated one that is very hard to read.
    for (const modelName of forcedStatusModels) {
      try {
        await mockAuthClearChatStatus(modelName);
      } catch (err) {
        console.warn(`[provider-key fixture] status-hook reset warning for ${modelName}:`, err);
      }
    }

    if (!shouldLeaveArtifacts(testInfo)) {
      for (const name of registered) {
        try {
          await deleteProviderKeyByName(name);
        } catch (err) {
          console.warn(`[provider-key fixture] delete warning for ${name}:`, err);
        }
      }
      // Only ids this test seeded — a pre-existing built-in key is left alone.
      for (const id of seededBuiltInIds) {
        try {
          await deleteProviderKeyById(id);
        } catch (err) {
          console.warn(`[provider-key fixture] delete warning for built-in key ${id}:`, err);
        }
      }
    }
  },
});

export { expect } from './automation-rules.fixture';
