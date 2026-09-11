import { ExplainKind, ExplainTarget } from "@/types/assistant-sidebar";

type ExplainKindConfig = {
  /** Button aria-label / popover affordance text. */
  label: string;
  /** The verbatim question seeded into the chat on "Continue with Ollie". */
  question: (target: ExplainTarget) => string;
};

// The contextual explain actions (OPIK-6425): error / cost / duration across
// the Traces, Spans, and Threads tables (Threads have no per-row error). This
// registry is the single place a kind's label + seed question live, so the
// button/popover stay generic.
// Total Record (not Partial): adding a new ExplainKind without a config here is
// a compile error at this declaration, instead of a silently missing button.

// The seed question is identical per metric regardless of entity (error/cost/
// duration), so build the configs from shared factories to keep them in sync.
const errorConfig: ExplainKindConfig = {
  label: "Explain error",
  question: (target) => {
    const type = (target.payload as { exception_type?: string }).exception_type;
    return type ? `Explain this error: ${type}` : "Explain this error";
  },
};
const costConfig: ExplainKindConfig = {
  label: "Explain cost",
  question: () => "Explain this cost",
};
const durationConfig: ExplainKindConfig = {
  label: "Explain duration",
  question: () => "Explain this duration",
};

export const AI_EXPLAIN_REGISTRY: Record<ExplainKind, ExplainKindConfig> = {
  "trace.error": errorConfig,
  "trace.cost": costConfig,
  "trace.duration": durationConfig,
  "span.error": errorConfig,
  "span.cost": costConfig,
  "span.duration": durationConfig,
  "thread.duration": durationConfig,
  "thread.cost": costConfig,
};

// Returns `| undefined` so callers keep their defensive guards even though the
// registry is currently total.
export const getExplainConfig = (
  kind: ExplainKind,
): ExplainKindConfig | undefined => AI_EXPLAIN_REGISTRY[kind];
