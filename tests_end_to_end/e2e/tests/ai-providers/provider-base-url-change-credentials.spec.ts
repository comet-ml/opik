import { test, expect } from '@e2e/fixtures';
import {
  createProviderKeyRaw,
  getProviderKeyById,
  guardPassingBaseUrl,
  guardPassingBaseUrlAlternate,
  patchProviderKey,
} from '@e2e/core/provider-keys';

/**
 * Changing an AI provider's `base_url` is refused unless the credentials are
 * resubmitted (opik#9214).
 *
 * `LlmProviderApiKeyService.validateCredentialsResubmittedOnUrlChange` runs on
 * every PATCH: if the update carries a `base_url` that differs from the stored
 * one and does NOT carry a replacement credential, it answers 400 and the row
 * is left alone. The reason is not cosmetic — a provider's secret is
 * write-only, so a silent save would leave Opik holding a credential the user
 * entered for one host and sending it to another. That is a
 * credential-exfiltration shape, which is why it is worth a permanent guard
 * rather than a note.
 *
 * `configuration.ai-provider-edit` already read `covered: true`, but the spec
 * behind it — `token-auth-dialog.spec.ts` — updates a provider WITHOUT changing
 * the URL, so it exercises the branch this rule must NOT fire on and says
 * nothing about the branch where it must. Both branches are asserted here, in
 * that order, because a rule that fires on an unchanged URL is just as broken
 * as one that never fires and would be invisible to a spec that only tested the
 * refusal.
 *
 * API-level and no browser, which is the right surface rather than a shortcut:
 * the rule lives in the service, the dialog has no control that can send a URL
 * change with no credential beside it, and `@cap:configuration.ai-provider-edit`
 * names editing a provider rather than any widget. Driving a UI to observe a
 * 400 second-hand would be slower, flakier, and would assert the dialog's error
 * rendering instead of the rule.
 *
 * Portability: both URLs are `https://192.0.2.1/...` (TEST-NET-1), which the
 * destination guard accepts under `strict` as well as `relaxed` and which
 * nothing here ever connects to — see `guardPassingBaseUrl`. The estate's
 * loopback provider URLs would be refused outright on a `strict` deployment
 * such as cloud staging, and that refusal would land on the CREATE, before this
 * spec reached its subject at all.
 */
test.describe('AI Providers — base_url change rules', { tag: ['@area:configuration'] }, () => {
  test(
    'changing base_url needs the credentials again, and an unchanged one does not',
    { tag: ['@t2-cuj', '@cap:configuration.ai-provider-edit'] },
    async ({ providerKeys, testNamespace }) => {
      // Provider keys are WORKSPACE-GLOBAL: the name is run-prefixed so parallel
      // runs cannot collide, and the fixture owns the delete. The create is done
      // here rather than through one of the fixture's own creators because its
      // 201 is part of the assertion (it establishes that the guard accepts this
      // destination, without which every later status would be ambiguous) and
      // because no creator seeds a guard-passing URL this spec can then change.
      const providerName = `${testNamespace}-base-url`;
      providerKeys.register(providerName);

      const originalSecret = 'qa-original-secret-aaa';
      const resubmittedSecret = 'qa-resubmitted-secret-bbb';

      const providerId = await test.step('Create a Custom provider with a stored secret', async () => {
        const created = await createProviderKeyRaw({
          provider: 'custom-llm',
          provider_name: providerName,
          base_url: guardPassingBaseUrl,
          api_key: originalSecret,
          configuration: { models: `custom-llm/${providerName}/qa-never-called` },
        });
        expect(created.status, `create failed: ${created.body}`).toBe(201);
        // From the Location header rather than by listing back: the id this
        // request created is the only one the PATCHes below may touch.
        expect(created.id, 'the 201 carried no Location header to read the id from').toBeTruthy();
        return created.id as string;
      });

      const stored = await test.step('Read back what was stored', async () => {
        const key = await getProviderKeyById(providerId);
        expect(key.base_url).toBe(guardPassingBaseUrl);
        // The secret comes back masked, never in the clear. Captured so the
        // assertions below can say whether the STORED credential changed — the
        // half of this rule that matters, and one a base_url assertion alone
        // cannot see.
        const maskedSecret = (key as { api_key?: string }).api_key;
        expect(
          maskedSecret,
          'the provider stored no api_key, so there is no credential for the rule to protect',
        ).toBeTruthy();
        return { maskedSecret };
      });

      await test.step('An unchanged base_url with no credential still saves', async () => {
        // The branch the rule must NOT fire on, asserted first: it is the one
        // the existing smoke spec covers, and a rule that refused here would
        // make every ordinary provider edit impossible.
        const patched = await patchProviderKey(providerId, { base_url: guardPassingBaseUrl });
        expect(patched.status, `unchanged-URL PATCH was refused: ${patched.body}`).toBe(204);

        const after = await getProviderKeyById(providerId);
        expect(after.base_url).toBe(guardPassingBaseUrl);
        expect((after as { api_key?: string }).api_key).toBe(stored.maskedSecret);
      });

      await test.step('A NEW base_url with no credential is refused, naming what to resubmit', async () => {
        const patched = await patchProviderKey(providerId, {
          base_url: guardPassingBaseUrlAlternate,
        });
        expect(patched.status).toBe(400);
        expect(patched.body).toContain(
          'changing base_url requires resubmitting the provider credentials (api_key or auth_config)',
        );
      });

      await test.step('…and the refused update did not partially apply', async () => {
        // The point of the rule. A 400 that had already written the new URL
        // would leave the provider pointing at another host while still holding
        // the secret the user entered for the first one — so the stored
        // credential is asserted unchanged as well as the URL.
        const after = await getProviderKeyById(providerId);
        expect(after.base_url).toBe(guardPassingBaseUrl);
        expect((after as { api_key?: string }).api_key).toBe(stored.maskedSecret);
      });

      await test.step('Resubmitting the credential alongside the new URL saves both', async () => {
        const patched = await patchProviderKey(providerId, {
          base_url: guardPassingBaseUrlAlternate,
          api_key: resubmittedSecret,
        });
        expect(patched.status, `resubmitted PATCH was refused: ${patched.body}`).toBe(204);

        const after = await getProviderKeyById(providerId);
        expect(after.base_url).toBe(guardPassingBaseUrlAlternate);
        // The mask is derived from the stored value, so a changed mask is the
        // only external evidence that the new secret replaced the old one
        // rather than the URL moving on its own.
        expect((after as { api_key?: string }).api_key).not.toBe(stored.maskedSecret);
      });
    },
  );

  test(
    'the built-in free provider cannot be saved as a workspace key',
    // `ai-provider-add`, not `-edit`: this is the CREATE endpoint's own guard
    // (`saveApiKey`'s OPIK_FREE branch), and nothing here edits a stored
    // provider. Tagging it `-edit` would have filed a create-path refusal under
    // the wrong capability.
    { tag: ['@t2-cuj', '@cap:configuration.ai-provider-add'] },
    async () => {
      // Its own test rather than a step of the one above: it is a different
      // refusal on a different endpoint (`saveApiKey`'s OPIK_FREE guard, not
      // the base_url rule), and a failure in one says nothing about the other.
      //
      // Nothing is registered for cleanup because nothing can be created — the
      // assertion is that the create is refused. If it ever starts succeeding
      // this test fails, which is the point; it would leave a built-in key
      // behind, and a built-in key is one row per workspace with no name to
      // namespace, so deleting it blind could take a real credential with it.
      const refused = await createProviderKeyRaw({
        provider: 'opik-free',
        api_key: 'qa-should-never-be-stored',
      });
      expect(refused.status).toBe(400);
      expect(refused.body).toContain("provider is built in and can't be saved");
      expect(refused.id, 'a refused create must not report a created resource').toBeNull();
    },
  );
});
