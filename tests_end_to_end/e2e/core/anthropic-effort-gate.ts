/**
 * Reading the Anthropic effort gate's own declaration of what it accepts.
 *
 * `AnthropicEffort.validateEffort` refuses a request during mapping — before
 * any provider is called — and since opik#8928 it decides from the curated
 * catalog (`registryService.curatedParamsOf`) rather than from the hardcoded
 * `EFFORT_LEVELS_BY_MODEL_ID` map that PR deleted. Its 400 quotes the whole
 * supported list, which is what makes the gate readable at all: one refused
 * request per model yields the gate's ENTIRE accept set, rather than the five
 * requests per model it would take to probe each level.
 *
 * ## Why a refused request is the only probe worth having
 *
 * The complementary probe — send a level the row DOES accept and check it is
 * not refused — cannot be done for free. The provider-key lookup runs BEFORE
 * this gate (with no key configured, every level alike answers `400 API key
 * not configured`, so the gate is unreachable), and once past the gate the
 * request goes to Anthropic for real. On a workspace holding a real key that
 * is a billed call per level, on a model `llm-model-policy` forbids this suite
 * from driving at all.
 *
 * Reading the declaration instead gives both directions from one free request:
 * a level in the declared set cannot be refused by the gate, and a level
 * outside it is refused — and the refusal is what the request itself proves.
 */
import { expect, test } from '@playwright/test';
import type { BackendClient } from './backend';

/**
 * An effort value no curated row can ever accept, used to force the gate to
 * answer with its supported list.
 *
 * Deliberately not a real level: a probe that sent `max` would be refused only
 * by the rows that happen to withhold `max` today, and would sail through to
 * Anthropic — billed — on the twelve rows that curate all five. The backend's
 * `AnthropicEffort` enum has exactly five members, so a value outside it is
 * refused by every row by construction, whatever the catalog later adds.
 *
 * It is NOT rejected as a malformed payload first: `effort` rides inside the
 * free-form `custom_parameters` map, so it reaches the gate as a string and the
 * gate is what judges it.
 */
export const UNACCEPTABLE_EFFORT = 'qa-not-an-effort-level';

/** The 400 raised for a level outside a row's non-empty curated list. */
const UNSUPPORTED_LEVEL = 'Unsupported custom_parameters.output_config.effort';

/** The 400 raised for a row whose `effort` is `{accepted: false}`. */
const PARAMETER_REFUSED = 'The model does not support custom_parameters.output_config.effort';

/** What the gate says it will accept for one model. */
export interface GateAcceptSet {
  /** Whether the gate admits the parameter at all for this model. */
  accepted: boolean;
  /**
   * The levels the gate quoted as supported, in the order it listed them.
   * Empty when `accepted` is false — the gate names no list in that case
   * because there is none.
   */
  levels: string[];
}

/**
 * Ask the gate what it accepts for `model`, by sending it something it cannot.
 *
 * Fails rather than returns on any answer that is not one of the gate's two
 * refusals. That is the point: a `200`, an auth error or an `API key not
 * configured` all mean the probe never reached the gate, and returning an
 * empty accept set for those would read downstream as "the gate accepts
 * nothing" — turning a broken probe into a confident wrong answer.
 */
export async function probeAnthropicEffortGate(
  backendClient: BackendClient,
  model: string,
): Promise<GateAcceptSet> {
  return test.step(`probe the effort gate for ${model}`, async () => {
    const { status, message } = await backendClient.postChatCompletion({
      model,
      customParameters: { output_config: { effort: UNACCEPTABLE_EFFORT } },
    });

    // Called out by name because it is the one realistic way to miss the gate,
    // and because it is not self-explanatory: the provider-key lookup runs
    // BEFORE the gate, so on a workspace with no Anthropic key every model and
    // every level alike answer with this, and the gate is simply unreachable.
    // Without this the caller would instead see a confusing complaint about the
    // message not quoting a supported list.
    expect(
      message,
      `the workspace needs an Anthropic provider key before the effort gate is reachable at ` +
        `all — the key lookup runs first. Request a fixture that ensures one ` +
        `(modelRegistryProviders, or providerKeys.ensureBuiltIn('anthropic')).`,
    ).not.toContain('API key not configured');

    expect(
      status,
      `the gate must refuse '${UNACCEPTABLE_EFFORT}' for ${model} during request mapping; ` +
        `a different status means the probe never reached it (got: ${message})`,
    ).toBe(400);

    // The id is quoted back in both shapes. Asserted because the gate is being
    // read as a statement about THIS model: a message naming another one would
    // otherwise be parsed as its answer.
    expect(message, 'the refusal names the model it is about').toContain(`model '${model}'`);

    if (message.includes(PARAMETER_REFUSED)) {
      // This shape carries no list, so there is nothing to parse. Checked
      // before the other one rather than after: both strings mention the
      // parameter, and only this one means "no levels at all".
      return { accepted: false, levels: [] };
    }

    expect(
      message,
      `the refusal must be one of the gate's two shapes, not an unrelated 400`,
    ).toContain(UNSUPPORTED_LEVEL);

    const quoted = /supported '\[([^\]]*)\]'/.exec(message);
    expect(
      quoted,
      `the gate must quote the levels it supports for ${model} — without the list there is ` +
        `nothing to compare the catalog against (message: ${message})`,
    ).not.toBeNull();

    const levels = quoted![1]!
      .split(',')
      .map((level) => level.trim())
      .filter((level) => level.length > 0);
    expect(
      levels,
      'a row the gate admits the parameter for must quote at least one level',
    ).not.toHaveLength(0);

    return { accepted: true, levels };
  });
}
