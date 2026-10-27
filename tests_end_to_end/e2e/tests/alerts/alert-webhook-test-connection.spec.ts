import { test, expect, ALERT_EVENT_TITLE, ALERT_EVENT_TYPE } from '@e2e/fixtures';
import { AlertsPage } from '@e2e/pom/alerts.page';

const PROMPT_CREATED = ALERT_EVENT_TITLE[ALERT_EVENT_TYPE.promptCreated];

/** The mutation `useWebhookTestMutation` POSTs to. Matched as a path fragment. */
const WEBHOOK_TEST_PATH = '/v1/private/alerts/webhooks/tests';

/**
 * A host that can never resolve, anywhere, on any network.
 *
 * `.invalid` is reserved by RFC 6761 §6.4 precisely so that it is guaranteed
 * NOT to be delegated — so the backend's resolver failure is a property of the
 * DNS root, not of this runner's connectivity or of whatever happens to be
 * unreachable today. That is what makes the failure half of this spec
 * deterministic without the suite having to own a receiver.
 *
 * Fixed rather than namespaced: a hostname label caps at 63 characters and
 * `testNamespace` alone can exceed that, and uniqueness buys nothing here —
 * nothing is created, and every worker wants the same unresolvable answer.
 */
const UNRESOLVABLE_HOST = 'opik-e2e-no-such-host.invalid';

/**
 * The "Test connection" control on the alert form (OPIK-8198 / opik#8099).
 *
 * `alerts.test-alert` was the area's one uncovered capability, and 8099 both
 * moved and rebuilt these controls, so nothing in the estate drove them.
 *
 * Deliberately scoped to the two halves that are deterministic without a
 * receiver the suite controls:
 *
 *  - the client-side URL guard, which answers before any request is made, and
 *  - a host under `.invalid`, which can never resolve.
 *
 * NOT covered here, and still worth a human's time on a release: the SUCCESS
 * path. `useWebhookTest` renders "Webhook connection test successful!" only
 * when a real endpoint answers, and asserting that needs a receiver the suite
 * owns and the backend can reach — which, against managed cloud staging, it
 * cannot. This is the gap the taxonomy's `test-alert` note was holding the
 * capability open for; the two halves below are the part that can be pinned
 * honestly, not the whole control.
 *
 * Neither test submits, so no alert is created and there is nothing to tear
 * down.
 */
test.describe('Alerts — webhook connection test', { tag: ['@t2-cuj', '@area:alerts'] }, () => {
  test(
    'An invalid endpoint URL is refused client-side, without issuing a request',
    { tag: ['@cap:alerts.test-alert'] },
    async ({ project, page }) => {
      // Recorded from before the form opens, so a request issued at any point
      // in the flow is caught — not only one issued after the click.
      const testRequests: string[] = [];
      page.on('request', (request) => {
        if (request.url().includes(WEBHOOK_TEST_PATH)) testRequests.push(request.method());
      });

      const alerts = new AlertsPage(page);
      const editor = await test.step('Open the create form', async () => {
        await alerts.goto(project.id);
        await alerts.waitForReady();
        return alerts.openCreateForm();
      });

      await test.step('Add a trigger and type a URL that is not one', async () => {
        await editor.addTrigger(PROMPT_CREATED);
        await editor.fillWebhookUrl('not-a-url');
        await editor.clickTestConnection();
      });

      // The toast is the guard's own answer, so its arrival is what says the
      // click was handled — and therefore the point at which "no request was
      // issued" is a real claim rather than a race with a slow network.
      await test.step("The toast carries zod's message for a malformed URL", async () => {
        await expect(editor.toast('Please enter a valid URL')).toHaveCount(1);
      });

      // The whole point of the client-side guard: the round trip never
      // happens. Asserting only the toast would pass just as well if the
      // backend were asked and its answer discarded.
      await test.step('No webhook test was POSTed', async () => {
        expect(
          testRequests,
          `the URL guard must answer before the round trip, but ${WEBHOOK_TEST_PATH} was called`,
        ).toEqual([]);
      });
    },
  );

  test(
    'An unreachable host answers with a failure the toast explains',
    { tag: ['@cap:alerts.test-alert'] },
    async ({ project, page }) => {
      const alerts = new AlertsPage(page);
      const editor = await test.step('Open the create form', async () => {
        await alerts.goto(project.id);
        await alerts.waitForReady();
        return alerts.openCreateForm();
      });

      await test.step('Add a trigger and point the endpoint at an unresolvable host', async () => {
        await editor.addTrigger(PROMPT_CREATED);
        await editor.fillWebhookUrl(`https://${UNRESOLVABLE_HOST}/hook`);
      });

      const body = await test.step('Test the connection and read the mutation', async () => {
        const responsePromise = page.waitForResponse(
          (response) =>
            response.url().includes(WEBHOOK_TEST_PATH) && response.request().method() === 'POST',
        );
        await editor.clickTestConnection();
        const response = await responsePromise;

        // A reachability probe that could not reach anything is still a
        // successful probe: the endpoint reports the outcome in its body and
        // must not answer 4xx/5xx for it.
        expect(response.status(), 'the webhook test endpoint answers the probe itself').toBe(200);
        return (await response.json()) as {
          status?: string;
          status_code?: number;
          error_message?: string;
        };
      });

      await test.step('The response reports a failure that names the host', async () => {
        expect(body.status, 'status of a probe against an unresolvable host').toBe('failure');
        // 0, not absent and not an HTTP code: no response was ever received,
        // so there is no status to report. `toBe(0)` rather than a falsy check
        // — an absent field would satisfy the latter and mean something else.
        expect(body.status_code, 'no HTTP response was received, so there is no status code').toBe(
          0,
        );
        expect(
          body.error_message,
          'the probe must say why it failed, not merely that it did',
        ).toBeTruthy();
        expect(body.error_message, 'the reason names the host that could not be resolved').toContain(
          UNRESOLVABLE_HOST,
        );
      });

      // The user never sees the response body. What they get is the toast, and
      // a destructive toast carrying a reason is the difference between "that
      // URL is wrong" and "something went wrong" — so the reason reaching the
      // screen is asserted, not just its presence on the wire.
      await test.step('The destructive toast carries that same reason', async () => {
        const failureToast = editor.toast('Webhook test failed');
        await expect(failureToast).toHaveCount(1);
        await expect(failureToast).toContainText(UNRESOLVABLE_HOST);
      });
    },
  );
});
