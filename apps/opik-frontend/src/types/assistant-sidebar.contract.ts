/**
 * VER-4 — compile-time bridge-contract assertions (ships with G1b).
 *
 * `npm run typecheck` (`tsc --noEmit`) FAILS if any of the 7 explain/chat/
 * console events is missing from `HostEventMap` / `SidebarEventMap`. This is
 * the guard against the two repos' bridge type maps silently skewing — the
 * exact failure mode that produced the pre-existing `runner:*` divergence and
 * would otherwise surface only as a permanent thinking-pulse bug at INT.
 *
 * No runtime footprint: this module is never imported, exports only types, and
 * is excluded from the bundle. It exists purely so tsc evaluates the
 * assertions below.
 *
 * SCOPE: the explain set only (per G1b). The pre-existing `runner:*` skew
 * between `opik-frontend` (`runner:state-changed`) and `ollie-console`
 * (`runner:paired` / `runner:pair-failed` / `runner:connected`) is deliberately
 * OUT of scope here — reconciling it is tracked as a separate follow-up ticket
 * (owner: whoever owns both repos). The `BRIDGE_PROTOCOL_VERSION` bump to 2
 * means "explain events added", NOT "full map coherence".
 *
 * Keep `ollie-console/src/bridge.ts` byte-identical and mirror an equivalent
 * assertion there (`ollie-console/src/__tests__/bridge.test.ts`).
 */
import { HostEventMap, SidebarEventMap } from "@/types/assistant-sidebar";

/**
 * `T` must contain every key of `U`. If it does not, `T` violates the
 * `Record<keyof U, unknown>` constraint and tsc errors where the alias is
 * instantiated below — even though the result is never used at runtime.
 *
 * (The plan's equivalent value-level form is
 * `const _c: AssertAllKeys<HostEventMap, Expected> = {} as HostEventMap`; the
 * type-only form here enforces the identical constraint with no runtime binding,
 * so it stays clear of unused-var / object-literal-cast lint.)
 */
type AssertAllKeys<T extends Record<keyof U, unknown>, U> = T;

/** The explain/chat events the host emits to the shell. */
interface ExpectedHostExplainEvents {
  "explain:run": unknown;
  "explain:cancel": unknown;
  "chat:continue": unknown;
}

/** The handshake/explain events the shell emits back to the host. */
interface ExpectedSidebarExplainEvents {
  "console:ready": unknown;
  "explain:chunk": unknown;
  "explain:done": unknown;
  "explain:error": unknown;
}

// The assertions. tsc fails to compile if HostEventMap / SidebarEventMap is
// missing any expected explain event key.
export type HostExplainEventsContract = AssertAllKeys<
  HostEventMap,
  ExpectedHostExplainEvents
>;
export type SidebarExplainEventsContract = AssertAllKeys<
  SidebarEventMap,
  ExpectedSidebarExplainEvents
>;
