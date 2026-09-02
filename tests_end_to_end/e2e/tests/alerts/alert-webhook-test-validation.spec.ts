import { test, expect } from '@e2e/fixtures';
import { AlertFormPage } from '@e2e/pom/alerts.page';

/**
 * The webhook test buttons validate the Endpoint URL before firing anything,
 * in the places OPIK-8198 moved them to.
 *
 * The PR relocated both: `Test connection` onto the Endpoint URL row inside
 * Webhook settings, `Test trigger` into each trigger's collapsed `Example
 * payload` accordion, and put Triggers above Webhook settings. A relocation
 * regresses quietly — the buttons still exist somewhere, so nothing errors.
 *
 * The load-bearing assertion is that a blank or malformed URL fires **no**
 * request: the regression worth catching is a validation short-circuit that
 * goes missing and starts POSTing whatever the user has half-typed. An absence
 * assertion proves nothing on its own, so the last step is a positive control —
 * a valid URL must produce exactly the request the earlier steps assert is
 * absent. Without it, a recorder wired to the wrong URL pattern would report
 * "no requests" forever.
 *
 * Scope: this covers the *pre-flight* half of webhook testing only. Asserting a
 * delivery succeeds needs an HTTP sink the Opik backend container can reach,
 * which the estate does not have — so `alerts.test-alert` stays `covered:
 * false` in the taxonomy. See the note there.
 */

const ERRORS_TRIGGER = 'Trace errors threshold';
const WEBHOOK_TEST_PATH = '/v1/private/alerts/webhooks/tests';
const VALID_URL = 'https://example.com/opik-e2e-webhook-test';

test.describe('Alerts — webhook test validation', { tag: ['@t2-cuj', '@area:alerts'] }, () => {
  test('the webhook test buttons validate the URL before issuing a request', { tag: ['@cap:alerts.test-alert'] }, async ({
    project,
    page,
  }) => {
    const alertForm = new AlertFormPage(page);

    // Every POST the page makes to an alerts endpoint, recorded from before the
    // first click. Scoped to `/v1/private/alerts` rather than the test path
    // alone so an accidental *alert create* is caught too.
    const alertPosts: string[] = [];
    page.on('request', (request) => {
      if (request.method() === 'POST' && request.url().includes('/v1/private/alerts')) {
        alertPosts.push(request.url());
      }
    });

    await test.step('Triggers renders before Webhook settings', async () => {
      await alertForm.gotoCreate(project.id);
      expect(await alertForm.sectionOrder()).toEqual([
        'alert-triggers-section',
        'alert-webhook-settings-section',
      ]);
      await expect(
        alertForm.testConnectionButton,
        'Test connection sits on the Endpoint URL row, inside Webhook settings',
      ).toHaveCount(1);
    });

    await test.step('Test connection with a blank URL is refused, and fires nothing', async () => {
      await expect(alertForm.endpointUrlInput).toHaveValue('');
      await alertForm.testConnectionButton.click();
      await alertForm.expectToastAndDismiss('Endpoint URL is required');
      expect(alertPosts, 'a blank Endpoint URL must not reach the network').toEqual([]);
    });

    await test.step('Test connection with a malformed URL is refused, and fires nothing', async () => {
      await alertForm.fillEndpointUrl('not-a-url');
      await alertForm.testConnectionButton.click();
      await alertForm.expectToastAndDismiss('Please enter a valid URL');
      expect(alertPosts, 'a malformed Endpoint URL must not reach the network').toEqual([]);
    });

    await test.step('Test trigger appears only inside the trigger\'s Example payload accordion', async () => {
      await alertForm.fillEndpointUrl('');
      await alertForm.addTrigger(ERRORS_TRIGGER);
      await expect(
        alertForm.testTriggerButton,
        'Test trigger is hidden while the Example payload accordion is collapsed',
      ).toHaveCount(0);

      await alertForm.expandExamplePayload();
      await expect(
        alertForm.testTriggerButton,
        'expanding Example payload reveals exactly one Test trigger button, inside Triggers',
      ).toHaveCount(1);
    });

    await test.step('Test trigger with a blank URL is refused, and fires nothing', async () => {
      await alertForm.testTriggerButton.click();
      await alertForm.expectToastAndDismiss('Endpoint URL is required');
      expect(
        alertPosts,
        'Test trigger must run the same validation as Test connection',
      ).toEqual([]);
    });

    await test.step('Control: a valid URL does issue the webhook test request', async () => {
      await alertForm.fillEndpointUrl(VALID_URL);
      const posted = page.waitForRequest(
        (request) =>
          request.method() === 'POST' && request.url().includes(WEBHOOK_TEST_PATH),
      );
      await alertForm.testConnectionButton.click();
      await posted;

      // Only the delivery attempt itself — no alert was created by testing one.
      expect(alertPosts.filter((url) => !url.includes(WEBHOOK_TEST_PATH))).toEqual([]);
      expect(
        alertPosts.filter((url) => url.includes(WEBHOOK_TEST_PATH)),
        'exactly one webhook test request, and only after the URL validated',
      ).toHaveLength(1);
    });
  });
});
