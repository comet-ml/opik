/**
 * Drop provider keys that are present but unusable, before any spec reads them.
 *
 * Every consumer of a provider key — `ensureModelAvailable`, the judge-model
 * ternary in test-suites-smoke — decides by presence: `process.env.X` being a
 * non-empty string is taken to mean "this provider works". Presence and
 * usability are different claims, and they came apart on 2026-09-22: the
 * Anthropic key was live but its account was out of credit, so the Anthropic
 * branch was chosen, the OpenAI fallback behind it was never reached, and two
 * @t1-smoke specs failed for hours with `credit balance is too low` — a
 * provider billing state surfacing as a red suite.
 *
 * Unsetting the variable turns that into the case the fallbacks already handle:
 * an absent key. A revoked, expired, credit-exhausted or typo'd key then
 * degrades to the next provider instead of failing the run.
 *
 * Deliberately narrow: only an authoritative *rejection* of the credential
 * unsets it. A timeout, DNS failure or 5xx leaves the key in place — a flaky
 * network must not silently downgrade the provider a run was asked to exercise.
 */

/** Cheapest Anthropic model; a good key bills ~1 token for this probe. */
const ANTHROPIC_PROBE_MODEL = 'claude-haiku-4-5';
const PROBE_TIMEOUT_MS = 15_000;

/**
 * Statuses meaning "this credential cannot be used", as opposed to "the request
 * failed". 401/403 are auth; an exhausted balance arrives as 400
 * invalid_request_error, which is why 400 counts here.
 */
function isCredentialRejection(status: number): boolean {
  return status === 401 || status === 403 || status === 400;
}

async function probeAnthropic(apiKey: string): Promise<{ usable: boolean; reason?: string }> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), PROBE_TIMEOUT_MS);
  try {
    const res = await fetch('https://api.anthropic.com/v1/messages', {
      method: 'POST',
      headers: {
        'x-api-key': apiKey,
        'anthropic-version': '2023-06-01',
        'content-type': 'application/json',
      },
      body: JSON.stringify({
        model: ANTHROPIC_PROBE_MODEL,
        max_tokens: 1,
        messages: [{ role: 'user', content: 'hi' }],
      }),
      signal: controller.signal,
    });

    if (res.ok) return { usable: true };

    if (isCredentialRejection(res.status)) {
      let detail = `HTTP ${res.status}`;
      try {
        const body = (await res.json()) as { error?: { message?: string } };
        if (body?.error?.message) detail = body.error.message;
      } catch {
        // Non-JSON error body; the status alone is reason enough.
      }
      return { usable: false, reason: detail };
    }

    // 429 or 5xx: the key may well be fine, so keep it.
    return { usable: true };
  } catch (err) {
    // Aborted, offline, TLS failure — no verdict on the credential itself.
    console.warn('[llm-key-preflight] Anthropic probe inconclusive (keeping key):', err);
    return { usable: true };
  } finally {
    clearTimeout(timer);
  }
}

/**
 * Probe each configured provider key and delete the unusable ones from
 * `process.env`, so downstream presence checks route to a working provider.
 * Runs before the first spec; a no-op when no key is set.
 */
export async function dropUnusableProviderKeys(): Promise<void> {
  const anthropic = process.env.ANTHROPIC_API_KEY;
  if (!anthropic) return;

  const { usable, reason } = await probeAnthropic(anthropic);
  if (usable) return;

  // Assign empty rather than `delete`: Playwright forwards globalSetup's
  // process.env to worker processes (the same channel OPIK_RUN_ID rides), and
  // an assignment survives that copy where a deleted key may not. Every
  // consumer tests truthiness, so '' reads exactly like unset.
  process.env.ANTHROPIC_API_KEY = '';
  // The SDK driver was spawned before this ran and still holds the dead key,
  // so mark the verdict for anything that asks after the fact.
  process.env.OPIK_ANTHROPIC_KEY_UNUSABLE = '1';
  console.warn(
    `[llm-key-preflight] ANTHROPIC_API_KEY is set but unusable (${reason}) — unset for this run. ` +
      'Specs needing an LLM provider will fall back to OpenAI/OpenRouter, or skip if none remains.',
  );
}

/**
 * Whether an Anthropic key is present *and* passed the preflight probe.
 *
 * Specs must use this rather than testing `process.env.ANTHROPIC_API_KEY`
 * directly: the SDK driver is a Playwright `webServer`, spawned before
 * globalSetup with its own copy of the environment, so a key blanked in
 * globalSetup still reaches the driver. Choosing the judge model from this
 * helper keeps the spec-side choice and the driver's actual credential in
 * agreement — an Anthropic judge is only ever requested when the key works.
 */
export function anthropicKeyUsable(): boolean {
  return !!process.env.ANTHROPIC_API_KEY && process.env.OPIK_ANTHROPIC_KEY_UNUSABLE !== '1';
}
