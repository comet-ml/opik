import { test, expect } from '@e2e/fixtures';
import { ConfigurationPage } from '@e2e/pom/configuration.page';
import {
  createProviderKeyRaw,
  findProviderKeyByName,
  getProviderKeyById,
  guardPassingBaseUrl,
  patchProviderKey,
} from '@e2e/core/provider-keys';

/**
 * The provider dialog's custom-header rules agree with what the backend stores
 * (opik#9255 for the dialog, opik#9245 for the server).
 *
 * The two were written to mirror each other in two separate PRs in the same
 * release: the frontend's `validateHeaders` in
 * `ManageAIProviderDialog/schema.ts` and the server's in
 * `LlmProviderApiKeyService.validateHeaders`. Four rules, and the mismatch is
 * invisible either way until a user hits it — a dialog that blocks something
 * the API accepts is a feature nobody can reach, and a dialog that lets through
 * something the API 400s is a save that silently fails.
 *
 * Both sides are unit-tested (`schema.test.ts`, `LlmProviderApiKeyResourceTest`)
 * and neither is end-to-end tested: a grep of the estate for custom-header UI
 * returns nothing, `token-auth-dialog.spec.ts` is entirely OAuth2
 * client-credentials and never fills a header row, and
 * `visual-tests/tests/configuration.spec.ts` screenshots the providers list
 * rather than the dialog. Unit tests on both sides of a mirror cannot see the
 * mirror.
 *
 * ## The shape of the spec
 *
 * Three tests, because the three fail for different reasons and a reader needs
 * to know which half broke:
 *
 *  1. The dialog's rules on ADD, ending in what the backend actually stored.
 *     The stored map is the assertion that makes this `surface: both` rather
 *     than a test of error copy: "Add closed" would be satisfied by a save that
 *     dropped every header.
 *  2. The dialog's rules on EDIT, from the stored map back onto the screen. The
 *     comment opik#9255 removed said the old case-SENSITIVE rule existed "so
 *     stored maps with case-variant keys remain editable", so the question
 *     worth a permanent test is whether a user can still edit a key whose
 *     headers are fine — and whether making them not-fine is blocked.
 *  3. The server's own rules, on create AND update, driven directly. The
 *     dialog cannot express two of them: a `null` header value and a blank
 *     header name both leave the browser as an absent row, so only an API call
 *     can put them on the wire.
 *
 * ## Why the custom provider branch, and not a cloud one
 *
 * `supportsProviderHeaders` offers the editor on a handful of built-in
 * providers too, and that branch carries one extra rule
 * (`BUILT_IN_PROVIDER_RESERVED_HEADERS` — `authorization`, `api-key`). It is
 * deliberately NOT driven here: a built-in provider holds one key per
 * workspace with no name to namespace, every built-in on a shared workspace
 * already holds a real credential, and the only way into that branch is the
 * Edit dialog — where a save would overwrite a secret other specs depend on.
 * The reserved-header rule is therefore untested by this spec and says so.
 *
 * Portability: the base URL is `https://192.0.2.1/v1` (TEST-NET-1), which the
 * destination guard accepts under `strict` as well as `relaxed` and which
 * nothing here ever connects to — see `guardPassingBaseUrl`. A loopback URL
 * would be refused outright on cloud staging, and that refusal would land on
 * the create, before this spec reached its subject.
 */

/** The dialog's own messages, as `validateHeaders` words them. */
const KEY_REQUIRED = 'Header key is required';
const KEY_NOT_UNIQUE = 'Header key must be unique, ignoring case';

/** The server's, from `LlmProviderApiKeyService.validateHeaders`. */
const SERVER_BLANK_NAME = 'header names must not be blank';
const SERVER_NULL_VALUE = (name: string) => `header value must not be null, header '${name}'`;
const SERVER_CASE_VARIANT = (name: string) =>
  `header names must differ in more than case, header '${name}'`;

/** The header map the dialog is driven to save: one named value, one empty. */
const STORED_HEADERS = { 'X-Team': 'a', 'X-Empty': '' } as const;

/** A name that differs from `X-Team` only in case. */
const CASE_VARIANT_OF_X_TEAM = 'x-team';

test.describe(
  'AI Providers — custom headers in the provider dialog',
  { tag: ['@area:configuration'] },
  () => {
    test(
      'the Add dialog enforces the header rules and saves exactly the named rows',
      { tag: ['@t2-cuj', '@cap:configuration.ai-provider-add'] },
      async ({ providerKeys, testNamespace, page }) => {
        test.setTimeout(180_000);

        // Provider keys are WORKSPACE-GLOBAL, so the name is run-prefixed and
        // the fixture owns the delete. Registered rather than seeded because
        // creating through the dialog IS the behaviour under test — and
        // registered before the dialog opens, so a half-created key left by a
        // mid-test failure is still removed.
        const providerName = `${testNamespace}-headers-add`;
        providerKeys.register(providerName);

        const config = new ConfigurationPage(page);
        await config.gotoAiProviders();

        const dialog = await config.openAddCustomProviderDialog();
        if (dialog === null) {
          // Loud rather than skipped: the Custom option being absent means this
          // deployment cannot reach the branch under test at all, which is a
          // finding about the deployment, not a reason to report a pass.
          throw new Error(
            'the add-provider dialog offers no Custom option on this deployment, so the ' +
              'custom-header editor cannot be reached',
          );
        }

        await test.step('Fill the provider, leaving the headers for the rules below', async () => {
          await dialog.getByRole('textbox', { name: 'Provider name' }).fill(providerName);
          await dialog.getByRole('textbox', { name: 'URL', exact: true }).fill(guardPassingBaseUrl);
          await dialog
            .getByRole('textbox', { name: 'Models list' })
            .fill(`custom-llm/${providerName}/qa-never-called`);
          await dialog
            .getByRole('textbox', { name: 'API key' })
            .fill('qa-placeholder-never-called');
        });

        await test.step('A value with no header name is refused, and the dialog stays open', async () => {
          await config.addHeaderRows(dialog, [{ key: '', value: STORED_HEADERS['X-Team'] }]);
          await config.submitAddProvider(dialog);

          const error = await config.headerKeyError(dialog, 0);
          await expect(error, 'the row names what is missing').toHaveText(KEY_REQUIRED);
          await expect(
            dialog,
            'a refused submit leaves the dialog open — closing it would lose the form',
          ).toBeVisible();
          expect(
            await findProviderKeyByName(providerName),
            'and nothing was created',
          ).toBeNull();
        });

        await test.step('Two names differing only in case are refused on the second row', async () => {
          await config.setHeaderKey(dialog, 0, 'X-Team');
          await config.addHeaderRows(dialog, [
            { key: CASE_VARIANT_OF_X_TEAM, value: 'b' },
          ]);
          await config.submitAddProvider(dialog);

          // On the SECOND row, not the first: the rule admits the first spelling
          // it sees and refuses the duplicate, so an error on row 0 would mean
          // the user is told to fix the name they entered correctly.
          const duplicateError = await config.headerKeyError(dialog, 1);
          await expect(duplicateError).toHaveText(KEY_NOT_UNIQUE);
          const firstRowError = await config.headerKeyError(dialog, 0);
          await expect(
            firstRowError,
            'the first spelling is accepted, so its row carries no error',
          ).toHaveCount(0);
          await expect(dialog).toBeVisible();
          expect(await findProviderKeyByName(providerName), 'and nothing was created').toBeNull();
        });

        await test.step('A name with an empty value, plus a fully blank row, is accepted', async () => {
          await config.setHeaderKey(dialog, 1, 'X-Empty');
          await config.headerValueInputs(dialog).nth(1).fill('');
          // The blank row is the third rule: the editor always leaves the user
          // one spare row, so a build that refused it would make the dialog
          // unsubmittable the moment anyone clicked "Add header" once too often.
          await config.addHeaderRows(dialog, [{ key: '', value: '' }]);

          await config.submitAddProvider(dialog);
          await dialog.waitFor({ state: 'hidden' });
        });

        await test.step('The backend stored exactly the named rows', async () => {
          // The assertion the error-copy steps above cannot make. `toEqual` on
          // the whole map rather than per-key checks: the empty VALUE has to
          // survive and the blank ROW has to be gone, and a `toMatchObject`
          // would pass on a save that also stored `{"": ""}`.
          await expect
            .poll(
              async () => {
                const key = await findProviderKeyByName(providerName);
                return key === null ? null : ((await getProviderKeyById(key.id)).headers ?? null);
              },
              {
                timeout: 30_000,
                intervals: [500, 1_000, 2_000],
                message: 'the created provider key reads back with its headers',
              },
            )
            .toEqual(STORED_HEADERS);

          const key = await findProviderKeyByName(providerName);
          expect(key, 'the key the poll just read is still listed').not.toBeNull();
          expect(key!.base_url, 'and the rest of the key is what was typed').toBe(
            guardPassingBaseUrl,
          );
        });
      },
    );

    test(
      'the Edit dialog repopulates the stored headers and blocks a case-variant rename',
      { tag: ['@t2-cuj', '@cap:configuration.ai-provider-edit'] },
      async ({ providerKeys, testNamespace, page }) => {
        test.setTimeout(180_000);

        const providerName = `${testNamespace}-headers-edit`;
        providerKeys.register(providerName);

        const providerId = await test.step('Seed a provider key that already has headers', async () => {
          // Seeded through the API rather than the dialog: the subject here is
          // the EDIT path, and building the row through the Add dialog first
          // would make an Add regression fail this test for the wrong reason.
          const created = await createProviderKeyRaw({
            provider: 'custom-llm',
            provider_name: providerName,
            base_url: guardPassingBaseUrl,
            api_key: 'qa-placeholder-never-called',
            configuration: { models: `custom-llm/${providerName}/qa-never-called` },
            headers: { ...STORED_HEADERS },
          });
          expect(created.status, `create failed: ${created.body}`).toBe(201);
          expect(created.id, 'the 201 carried no Location header to read the id from').toBeTruthy();
          return created.id as string;
        });

        await test.step('The seed really holds the headers — including the empty value', async () => {
          // The premise, as a server-side fact. A UI assertion over headers
          // that never landed is a test that cannot fail, and it would read as
          // coverage forever.
          const stored = await getProviderKeyById(providerId);
          expect(stored.headers, 'both rows stored, empty value kept').toEqual(STORED_HEADERS);
        });

        const config = new ConfigurationPage(page);
        await config.gotoAiProviders();
        const dialog = await config.openCustomProviderEdit(providerName);

        await test.step('Both stored rows are back on screen, empty value included', async () => {
          const rows = await config.readHeaderRows(dialog);
          // Sorted by name: the stored value is a JSON object, and nothing in
          // the contract fixes which order the backend serialises it in — so
          // asserting display order would be asserting an implementation detail.
          expect(
            [...rows].sort((a, b) => a.key.localeCompare(b.key)),
            'the editor is populated from the stored map, not reset',
          ).toEqual(
            Object.entries(STORED_HEADERS)
              .map(([key, value]) => ({ key, value }))
              .sort((a, b) => a.key.localeCompare(b.key)),
          );
        });

        await test.step('Renaming one row to a case variant of the other is refused', async () => {
          const rows = await config.readHeaderRows(dialog);
          const variantRow = rows.findIndex((row) => row.key === 'X-Empty');
          expect(variantRow, 'the X-Empty row is on screen to rename').toBeGreaterThanOrEqual(0);

          await config.setHeaderKey(dialog, variantRow, CASE_VARIANT_OF_X_TEAM);
          await config.submitEditProvider(dialog);

          const error = await config.headerKeyError(dialog, variantRow);
          await expect(error).toHaveText(KEY_NOT_UNIQUE);
          await expect(dialog, 'the refused update leaves the dialog open').toBeVisible();
        });

        await test.step('…and the stored headers are untouched', async () => {
          // The point of a blocked update. A dialog that showed the error AND
          // saved would be the worst of both, and only the stored map can tell
          // the two apart.
          const stored = await getProviderKeyById(providerId);
          expect(stored.headers, 'nothing was written').toEqual(STORED_HEADERS);
        });

        await test.step('A rename that keeps the names distinct still saves', async () => {
          // The control. Without it, "the case variant was blocked" is equally
          // satisfied by a build where no header rename can ever be saved —
          // which is exactly the regression the removed comment warned about.
          const rows = await config.readHeaderRows(dialog);
          const variantRow = rows.findIndex((row) => row.key === CASE_VARIANT_OF_X_TEAM);
          expect(variantRow, 'the renamed row is still on screen').toBeGreaterThanOrEqual(0);

          await config.setHeaderKey(dialog, variantRow, 'X-Renamed');
          await config.submitEditProvider(dialog);
          await dialog.waitFor({ state: 'hidden' });

          await expect
            .poll(async () => (await getProviderKeyById(providerId)).headers, {
              timeout: 30_000,
              intervals: [500, 1_000, 2_000],
              message: 'the update stored the renamed header',
            })
            .toEqual({ 'X-Team': 'a', 'X-Renamed': '' });
        });
      },
    );

    test(
      'the server enforces the same header rules on create and on update',
      // `ai-provider-add`, and deliberately not only `-edit`: both endpoints run
      // the same `validateHeaders`, and the create path is the one the Add
      // dialog mirrors. API-level because two of these rules cannot be
      // expressed from a browser at all — a blank name and a `null` value both
      // leave the dialog as an absent row.
      { tag: ['@t2-cuj', '@cap:configuration.ai-provider-add'] },
      async ({ providerKeys, testNamespace }) => {
        const providerName = `${testNamespace}-headers-api`;
        providerKeys.register(providerName);

        await test.step('A blank header name is refused on create', async () => {
          const refused = await createProviderKeyRaw({
            provider: 'custom-llm',
            provider_name: providerName,
            base_url: guardPassingBaseUrl,
            api_key: 'qa-placeholder-never-called',
            configuration: { models: `custom-llm/${providerName}/qa-never-called` },
            headers: { '': 'orphan' },
          });
          expect(refused.status).toBe(400);
          expect(refused.body).toContain(SERVER_BLANK_NAME);
          expect(refused.id, 'a refused create must not report a created resource').toBeNull();
        });

        await test.step('A null header value is refused on create, naming the header', async () => {
          const refused = await createProviderKeyRaw({
            provider: 'custom-llm',
            provider_name: providerName,
            base_url: guardPassingBaseUrl,
            api_key: 'qa-placeholder-never-called',
            configuration: { models: `custom-llm/${providerName}/qa-never-called` },
            headers: { 'X-Team': null },
          });
          expect(refused.status).toBe(400);
          // Names the header, never its value — which is the same promise
          // opik#9245 made about the logs.
          expect(refused.body).toContain(SERVER_NULL_VALUE('X-Team'));
          expect(refused.id).toBeNull();
        });

        await test.step('Two names differing only in case are refused on create', async () => {
          const refused = await createProviderKeyRaw({
            provider: 'custom-llm',
            provider_name: providerName,
            base_url: guardPassingBaseUrl,
            api_key: 'qa-placeholder-never-called',
            configuration: { models: `custom-llm/${providerName}/qa-never-called` },
            headers: { 'X-Team': 'a', [CASE_VARIANT_OF_X_TEAM]: 'b' },
          });
          expect(refused.status).toBe(400);
          expect(refused.body).toContain(SERVER_CASE_VARIANT(CASE_VARIANT_OF_X_TEAM));
          expect(refused.id).toBeNull();
        });

        const providerId = await test.step(
          'The map the dialog produces is accepted, empty value and all',
          async () => {
            // The control for all six refusals, and the agreement that matters:
            // what the dialog saves is what the server takes. Without this, a
            // server that refused every header map would pass every step above.
            const created = await createProviderKeyRaw({
              provider: 'custom-llm',
              provider_name: providerName,
              base_url: guardPassingBaseUrl,
              api_key: 'qa-placeholder-never-called',
              configuration: { models: `custom-llm/${providerName}/qa-never-called` },
              headers: { ...STORED_HEADERS },
            });
            expect(created.status, `create was refused: ${created.body}`).toBe(201);
            expect(created.id).toBeTruthy();
            const stored = await getProviderKeyById(created.id as string);
            expect(stored.headers, 'stored verbatim').toEqual(STORED_HEADERS);
            return created.id as string;
          },
        );

        for (const [label, headers, expected] of [
          ['a blank name', { '': 'orphan' }, SERVER_BLANK_NAME],
          ['a null value', { 'X-Team': null }, SERVER_NULL_VALUE('X-Team')],
          [
            'a case variant',
            { 'X-Team': 'a', [CASE_VARIANT_OF_X_TEAM]: 'b' },
            SERVER_CASE_VARIANT(CASE_VARIANT_OF_X_TEAM),
          ],
        ] as const) {
          await test.step(`${label} is refused on update too`, async () => {
            const refused = await patchProviderKey(providerId, { headers });
            expect(refused.status).toBe(400);
            expect(refused.body).toContain(expected);
          });
        }

        await test.step('None of the refused updates changed the stored map', async () => {
          const stored = await getProviderKeyById(providerId);
          expect(stored.headers, 'the key still holds what the accepted create stored').toEqual(
            STORED_HEADERS,
          );
        });

        await test.step('An update with distinct names is accepted', async () => {
          // The update-path control, for the same reason the create one exists.
          const accepted = await patchProviderKey(providerId, {
            headers: { 'X-Team': 'a', 'X-Other': 'b' },
          });
          expect(accepted.status, `update was refused: ${accepted.body}`).toBe(204);
          const stored = await getProviderKeyById(providerId);
          expect(stored.headers).toEqual({ 'X-Team': 'a', 'X-Other': 'b' });
        });
      },
    );
  },
);
