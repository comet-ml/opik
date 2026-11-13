import * as path from 'node:path';
import { test, expect } from '@e2e/fixtures';
import { AgentPlaygroundPage } from '@e2e/pom/agent-playground.page';
import { startEndpointRunner, type EndpointRunner } from '@e2e/core/local-runner/connect';

const AGENTS_DIR = path.resolve(__dirname, '../../agents');

/**
 * The Agent playground's Test-input form is one of the four `zodResolver` forms
 * opik#9431 migrated to zod 4 with no spec of its own — and opik#9421 then
 * moved every one of them onto `@hookform/resolvers` 6 two commits later, a
 * major the migration was never validated against.
 *
 * `agent-playground-local-runner.spec.ts` is the only existing spec on this
 * page. It tags `drive-connected-agent` and asserts the rendered answer, which
 * means it would pass with both halves of this form broken: it never blanks a
 * field, so it never reaches the gate, and it reads the answer off the page
 * rather than the payload, so a field submitted as `undefined` that the agent
 * happened to render anyway would not fail it.
 *
 * The two halves asserted here are the two a resolver major can break
 * independently:
 *
 *  - the GATE — `AgentRunnerInputForm` runs `mode: 'onChange'` and reports
 *    `formState.isValid` upward as `hasAllRequiredParams`, which is the Run
 *    button's `disabled`. A resolver that stopped producing an issue for a
 *    blank required string leaves Run enabled over an empty form.
 *  - the SUBMIT — `handleSubmit`'s `data` is what builds the job body. A
 *    resolver that handed back a different object shape sends `inputs.query`
 *    as `undefined`, which this page would still render a plausible answer for.
 *
 * Driven against the golden entrypoint agent (`agents/playground-entrypoint`,
 * `run(query: str) -> str` returning `Answer for: {query}`), so the field name,
 * the agent name and the answer are all deterministic and offline.
 */

test.describe(
  'Agent playground — input form validation and submit',
  { tag: ['@t2-cuj', '@area:agent-playground'] },
  () => {
    test(
      'a blank required field disables Run, and the typed value reaches the job payload',
      { tag: ['@cap:agent-playground.agent-input-form'] },
      async ({ project, scratchDir, envConfig, page }) => {
        test.setTimeout(300_000);
        const playground = new AgentPlaygroundPage(page, project.id);
        let runner: EndpointRunner | null = null;

        // Namespaced through the test namespace the fixtures already mint, so
        // the value cannot collide with another worker's and cannot match
        // anything pre-existing in the shared workspace.
        const query = `input-form probe ${project.name}`;

        try {
          await test.step('Clone the entrypoint golden agent into a scratch dir', async () => {
            await scratchDir.clone(path.join(AGENTS_DIR, 'playground-entrypoint'));
          });

          // OSS local stack accepts any API key (no auth wall); the cloud envs
          // mint a real one via globalSetup and propagate it through
          // OPIK_API_KEY.
          const apiKey = envConfig.apiKey ?? 'opik-local-key';

          runner = await test.step('Start opik endpoint against the scratch agent', async () =>
            startEndpointRunner({
              projectName: project.name,
              projectId: project.id,
              workspace: envConfig.workspace,
              apiKey,
              cwd: scratchDir.path,
              command: ['python', path.join(scratchDir.path, 'agent.py')],
              apiUrl: envConfig.apiBaseUrl,
              apiBaseUrl: envConfig.apiBaseUrl,
            }));

          await test.step('Open the Agent playground and wait for the introspected form', async () => {
            await playground.goto();
            await playground.waitForHeading();
            await expect(playground.connectionBadge()).toBeVisible({ timeout: 60_000 });
            // The Connected badge and the "Test input" label both render before
            // the runner's introspected form mounts, so the field itself is the
            // only honest readiness signal.
            await expect(playground.inputField('query')).toBeVisible({ timeout: 60_000 });
          });

          await test.step('With the required field empty, Run is disabled', async () => {
            // The form's `defaultValues` set every field to '', so this is the
            // state the page opens in — no clearing needed to reach it, and
            // asserting it here rather than after a fill-then-clear means a
            // resolver that never produced the issue at all is caught on the
            // first check.
            await expect(
              playground.inputField('query'),
              'the field opens empty',
            ).toHaveValue('');
            await expect(
              playground.runButton(),
              'Run is gated on the form being valid',
            ).toBeDisabled();
          });

          await test.step('Filling the field enables Run', async () => {
            await playground.fillInput('query', query);
            // The control direction of the same gate: without this, "Run is
            // disabled" above would pass just as well on a page whose Run
            // button was disabled for an unrelated reason, or permanently.
            await expect(
              playground.runButton(),
              'a valid form releases the gate',
            ).toBeEnabled();
            await expect(
              playground.inputFieldError('query'),
              'and no validation message is left behind',
            ).toHaveCount(0);
          });

          await test.step('Clearing it again re-disables Run and reports the error on that field', async () => {
            // `mode: 'onChange'`, so this is where the issue→field mapping is
            // observable: the message has to come back under the field that is
            // empty, which is what `inputFieldError` scopes to.
            await playground.inputField('query').fill('');
            await expect(
              playground.runButton(),
              'the gate closes again when the value is removed',
            ).toBeDisabled();
            await expect(
              playground.inputFieldError('query'),
              'the message renders under the field it belongs to',
            ).toBeVisible();
          });

          await test.step('Re-filling it and running sends the typed value in the job body', async () => {
            await playground.fillInput('query', query);
            await expect(playground.runButton()).toBeEnabled();

            // Armed before the click: the request goes out on the click, so a
            // waiter started afterwards races it.
            const jobRequest = page.waitForRequest(
              (request) =>
                request.method() === 'POST' &&
                request.url().includes('/v1/private/local-runners/jobs'),
              { timeout: 60_000 },
            );
            await playground.clickRun();
            const request = await jobRequest;

            const body = request.postDataJSON() as {
              agent_name?: unknown;
              project_id?: unknown;
              inputs?: Record<string, unknown>;
            };
            // The agent name is the introspected entrypoint's own function
            // name, and the project is the one the runner paired against —
            // both asserted so a payload that carried the right query against
            // the wrong agent or project cannot pass.
            expect(body.agent_name, 'the introspected entrypoint').toBe('run');
            expect(body.project_id, 'the project the runner paired against').toBe(project.id);
            // The value, by equality on the whole `inputs` object rather than a
            // property lookup: `toEqual` on the object is what fails for a
            // query sent as `undefined`, for an extra field the form should
            // have dropped, and for a renamed key — a `body.inputs?.query`
            // check would quietly pass on the first and miss the second.
            expect(body.inputs, 'exactly the one typed field').toEqual({ query });
          });

          await test.step('And the agent answers with the value that was sent', async () => {
            // End of the chain: the payload above is what the runner invoked
            // the entrypoint with, so the rendered answer is the independent
            // confirmation that the value survived the whole round trip rather
            // than only looking right on the wire.
            await expect(playground.runningIndicator()).toHaveCount(0, { timeout: 180_000 });
            await expect(playground.resultText(query)).toBeVisible({ timeout: 30_000 });
          });
        } finally {
          runner?.stop();
        }
      },
    );
  },
);
