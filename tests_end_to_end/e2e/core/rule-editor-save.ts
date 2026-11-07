import { expect, type Page, type Request } from '@playwright/test';
import type { OnlineEvaluationPage } from '../pom/online-evaluation.page';

/**
 * Reading what the rule editor panel actually SENT, as opposed to what the
 * backend ended up storing.
 *
 * Every reasoning / thinking-effort contract on this surface is decided in the
 * frontend — `convertLLMJudgeDataToLLMJudgeObject` builds
 * `code.model.custom_parameters` and the backend only ever sees the result — so
 * the outbound body is the reading that says WHERE a wrong value came from. A
 * spec asserting the persisted blob alone cannot tell a form that serialized
 * the wrong shape from a backend that rewrote a right one.
 *
 * Shared rather than copied into each spec: three specs added for 2.2.133
 * (the OpenAI/OpenRouter effort round trip, the pipeline-mode gate and the
 * OpenAI half of the model-switch reconciler) make exactly this reading, and
 * the subscribe-before-click ordering below is the part that is wrong by
 * default. `online-evaluation-edit-rule-model-switch-effort.spec.ts` carries
 * its own copy of these three functions and predates this module; it is left
 * alone deliberately, since changing a passing spec to share a helper is churn
 * with no assertion behind it.
 */

/** Matches the rule PATCH on both the `/opik/api` and bare `/api` mounts. */
export function isRuleUpdate(url: string, ruleId: string): boolean {
  return new URL(url).pathname.endsWith(`/v1/private/automations/evaluators/${ruleId}`);
}

/**
 * Press the editor's submit and return the PATCH request it sent.
 *
 * Both the request and the response are awaited. The panel calls
 * `setOpen(false)` outside the mutation's callbacks, so it hides the moment the
 * PATCH is dispatched — waiting only for it to close would leave a persisted
 * read free to observe the PRE-save blob and pass on a backend that threw the
 * update away.
 *
 * The subscriptions are armed before the click because the request is in flight
 * the instant the form submits.
 */
export async function submitAndCaptureRuleUpdate(
  page: Page,
  onlineEval: OnlineEvaluationPage,
  ruleId: string,
): Promise<Request> {
  const patched = page.waitForRequest(
    (request) => request.method() === 'PATCH' && isRuleUpdate(request.url(), ruleId),
  );
  const answered = page.waitForResponse(
    (response) =>
      response.request().method() === 'PATCH' && isRuleUpdate(response.url(), ruleId),
  );
  await onlineEval.submitPanel();
  const request = await patched;
  const response = await answered;
  expect(response.ok(), `the save was accepted (got ${response.status()})`).toBe(true);
  return request;
}

/**
 * The `code.model.custom_parameters` the form serialized into a request,
 * without requiring the block to be there.
 *
 * Needed by the cross-provider cases, whose whole expectation is that no block
 * is sent at all — {@link readSentCustomParameters} cannot express that, since
 * asserting absence through a helper that asserts presence is a contradiction.
 */
export function sentCustomParameters(request: Request): Record<string, unknown> | undefined {
  const body = request.postDataJSON() as {
    code?: { model?: { custom_parameters?: Record<string, unknown> } };
  };
  return body.code?.model?.custom_parameters;
}

/**
 * The same read, asserting the block is present.
 *
 * Asserted rather than defaulted: a payload carrying no block at all is a
 * different failure from one carrying an emptied block, and a `?? {}` would
 * hide the difference behind a passing `toEqual`.
 */
export function readSentCustomParameters(request: Request): Record<string, unknown> {
  const sent = sentCustomParameters(request);
  expect(sent, 'the update payload carries a model custom_parameters block').toBeDefined();
  return sent as Record<string, unknown>;
}
